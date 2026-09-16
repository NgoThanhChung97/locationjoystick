package com.locationjoystick.core.location

import android.content.Context
import android.util.Log
import com.locationjoystick.core.common.constants.AppConstants
import com.locationjoystick.core.common.util.BearingTracker
import com.locationjoystick.core.data.LocationRepository
import com.locationjoystick.core.data.RoamingRepository
import com.locationjoystick.core.data.RouteRepository
import com.locationjoystick.core.data.WalkToEngine
import com.locationjoystick.core.model.LatLng
import com.locationjoystick.core.model.MockLocationState
import com.locationjoystick.core.model.MockMode
import com.locationjoystick.core.model.RouteType
import com.locationjoystick.core.model.Waypoint
import com.locationjoystick.core.routing.OsrmClient
import com.locationjoystick.core.routing.OsrmFailureReason
import com.locationjoystick.core.routing.RouteReplayEngine
import com.locationjoystick.core.routing.RouteReplayer
import com.locationjoystick.core.routing.RoutingErrorReporter
import com.locationjoystick.core.routing.TeleportRouteEngine
import com.locationjoystick.core.routing.osrmFailureMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val TAG = "ReplayOrchestrator"

/**
 * Owns all route-replay and walk-to orchestration logic extracted from [MockLocationService].
 *
 * `followRoadsToStart` governs both the pre-replay walk from the current position to the
 * route's first waypoint AND every leg between the route's own saved waypoints during
 * replay (see [expandWaypointsForFollowRoads]) — not just the pre-replay walk.
 *
 * Communicates back to the service via lambdas:
 * - [onStateChange]: writes into the service's `_state` MutableStateFlow
 * - [onPositionChange]: updates `currentLat` / `currentLon` @Volatile fields
 * - [onSpeedChange]: updates `currentSpeedMs` @Volatile field
 * - [pushLocationUpdate]: pushes a GPS tick via the test provider
 * - [startUpdateLoop]: restarts the idle update loop after replay ends
 */
internal class ReplayOrchestrator(
    private val context: Context,
    private val locationRepository: LocationRepository,
    private val routeRepository: RouteRepository,
    private val roamingRepository: RoamingRepository,
    private val routeReplayEngine: RouteReplayEngine,
    private val teleportRouteEngine: TeleportRouteEngine,
    private val walkToEngine: WalkToEngine,
    private val osrmClient: OsrmClient,
    private val routingErrorReporter: RoutingErrorReporter,
    private val scope: CoroutineScope,
    private val onStateChange: (MockLocationState) -> Unit,
    private val onPositionChange: (lat: Double, lon: Double) -> Unit,
    private val onSpeedChange: (speedMs: Float) -> Unit,
    private val pushLocationUpdate: () -> Unit,
    private val startUpdateLoop: () -> Unit,
) {
    /** Tracks the active scope.launch job so new starts can cancel it before launching. */
    private var activeReplayJob: Job? = null

    /**
     * Which engine is driving the current replay. Set once in [handleStart]/[handleEphemeralStart],
     * read by every pause/resume/stop/jump lifecycle method so they don't need to know which
     * engine is actually running — replaces a repeated `if (isTeleportRouteActive) ... else ...`
     * at every call site.
     */
    @Volatile private var activeReplayer: RouteReplayer = routeReplayEngine

    private val bearingTracker = BearingTracker()

    fun handleStart(
        routeId: String,
        isBackward: Boolean,
        speedMs: Double,
        isLoopingOverride: Boolean? = null,
        returnPosition: LatLng? = null,
        followRoadsToStart: Boolean = false,
    ) {
        val previous = activeReplayJob
        activeReplayJob =
            scope.launch {
                previous?.cancelAndJoin()
                routeReplayEngine.stop()
                teleportRouteEngine.stop()
                val route = routeRepository.getRouteWithWaypoints(routeId).first() ?: return@launch
                if (route.waypoints.size < 2) return@launch
                val orderedWaypoints = if (isBackward) route.waypoints.reversed() else route.waypoints
                val isLooping = isLoopingOverride ?: route.isLooping

                if (route.routeType == RouteType.TELEPORT) {
                    activeReplayer = teleportRouteEngine
                    startTeleportReplayWithWaypoints(
                        waypoints = orderedWaypoints,
                        isLooping = isLooping,
                        randomizeOrder = route.randomizeTeleportOrder,
                        persistMetadata = buildPersistMetadata(routeId, isBackward, orderedWaypoints.map { it.position }),
                        onComplete = buildStartOnComplete(returnPosition, speedMs),
                    )
                    return@launch
                }

                activeReplayer = routeReplayEngine
                val latLngs = orderedWaypoints.map { it.position }
                try {
                    if (followRoadsToStart) locationRepository.setRoadRouteFetchInFlight(true)
                    val (replayWaypoints, boundaryIndices) =
                        if (followRoadsToStart) expandWaypointsForFollowRoads(latLngs) else latLngs to null

                    startReplayWithWaypoints(
                        waypoints = replayWaypoints,
                        speedMs = speedMs,
                        isLooping = isLooping,
                        followRoadsToStart = followRoadsToStart,
                        boundaryIndices = boundaryIndices,
                        persistMetadata = buildPersistMetadata(routeId, isBackward, replayWaypoints),
                        onComplete = buildStartOnComplete(returnPosition, speedMs),
                    )
                } finally {
                    if (followRoadsToStart) locationRepository.setRoadRouteFetchInFlight(false)
                }
            }
    }

    /** Shared `persistMetadata` builder for [handleStart]'s two route-type branches. */
    private fun buildPersistMetadata(
        routeId: String,
        isBackward: Boolean,
        metadataWaypoints: List<LatLng>,
    ): suspend () -> Unit =
        {
            locationRepository.setActiveRouteId(routeId)
            locationRepository.setIsReplayBackward(isBackward)
            locationRepository.setRouteWaypoints(metadataWaypoints)
        }

    /** Shared `onComplete` builder for [handleStart]'s two route-type branches. */
    private fun buildStartOnComplete(
        returnPosition: LatLng?,
        speedMs: Double,
    ): suspend () -> Unit =
        {
            locationRepository.setRouteWaypoints(null)
            locationRepository.setActiveRouteId(null)
            if (returnPosition != null) {
                walkToPosition(returnPosition, speedMs)
            }
            finishReplay()
            locationRepository.emitCompletion("Route complete")
        }

    fun handleEphemeralStart(
        waypoints: List<LatLng>,
        speedMs: Double,
    ) {
        val previous = activeReplayJob
        activeReplayJob =
            scope.launch {
                previous?.cancelAndJoin()
                routeReplayEngine.stop()
                // Ephemeral (walk-here) replay has no persisted route, so it's never TELEPORT type.
                activeReplayer = routeReplayEngine
                startReplayWithWaypoints(
                    waypoints = waypoints,
                    speedMs = speedMs,
                    isLooping = false,
                    persistMetadata = null,
                )
            }
    }

    /**
     * Propagates a live speed-profile change into the active replay. Caller must only invoke
     * this while a replay is actually running (mode == ROUTE_REPLAY), since it also overwrites
     * the service's reported `currentSpeedMs`. No-op on the engine for a teleport route — nothing
     * moves at a "speed" — but still reports 0 so speed-cycle UI doesn't show a stale value.
     */
    fun updateSpeed(speedMs: Double) {
        onSpeedChange(activeReplayer.updateSpeed(speedMs))
    }

    fun handlePause() {
        activeReplayer.pause()
        onStateChange(MockLocationState.PAUSED)
        locationRepository.pauseSpoofing()
        Log.i(TAG, "Replay paused")
    }

    fun handleResume(speedMs: Double) {
        onStateChange(MockLocationState.RUNNING)
        locationRepository.startSpoofing()
        activeReplayer.resume(
            onPositionUpdate = ::tickPosition,
            onComplete = {
                // Matches startReplayWithWaypoints' default onComplete (used when a replay finishes
                // without ever being paused) — a natural completion must not force IDLE/stopSpoofing.
                // Forcing IDLE here previously killed a group-sync leader's broadcast on completion.
                finishReplay()
                locationRepository.emitCompletion("Route complete")
            },
        )
        Log.i(TAG, "Replay resumed at ${speedMs}m/s")
    }

    fun handleJumpToNextWaypoint() = handleJumpToWaypoint(forward = true)

    fun handleJumpToPreviousWaypoint() = handleJumpToWaypoint(forward = false)

    private fun handleJumpToWaypoint(forward: Boolean) {
        if (locationRepository.currentMode.value != MockMode.ROUTE_REPLAY) return
        val onReplayComplete: () -> Unit = {
            finishReplay()
            locationRepository.emitCompletion("Route complete")
        }
        val target =
            if (forward) {
                activeReplayer.jumpToNextWaypoint(::tickPosition, onReplayComplete)
            } else {
                activeReplayer.jumpToPreviousWaypoint(::tickPosition, onReplayComplete)
            }
        target?.let(::tickPosition)
        Log.i(TAG, "Jumped to ${if (forward) "next" else "previous"} waypoint")
    }

    /**
     * Pushes one position tick: updates the service's reported lat/lon, the shared
     * repository state, and the test-provider location.
     */
    private fun tickPosition(pos: LatLng) {
        // Mirrors updatePositionWithVector's gate: rejects a stale tick from a replay whose async cancel hasn't landed yet.
        if (locationRepository.currentMode.value != MockMode.ROUTE_REPLAY) return
        onPositionChange(pos.latitude, pos.longitude)
        bearingTracker.advance(pos)?.let { locationRepository.setBearingInternal(it) }
        try {
            locationRepository.setPositionInternal(pos)
            pushLocationUpdate()
        } catch (e: Exception) {
            Log.e(TAG, "Position update failed", e)
        }
    }

    suspend fun handleStop() {
        activeReplayJob?.cancelAndJoin()
        activeReplayJob = null
        locationRepository.setRouteWaypoints(null)
        activeReplayer.stop()
        resetModeIfStillReplaying()
        locationRepository.setActiveRouteId(null)
        if (locationRepository.mockLocationState.value == MockLocationState.RUNNING) {
            startUpdateLoop()
        }
        Log.i(TAG, "Replay stopped; service remains active in TELEPORT mode")
    }

    suspend fun handleCancel() {
        activeReplayJob?.cancelAndJoin()
        activeReplayJob = null
        locationRepository.setRouteWaypoints(null)
        activeReplayer.stop()
        resetModeIfStillReplaying()
        locationRepository.setActiveRouteId(null)
        if (locationRepository.mockLocationState.value == MockLocationState.RUNNING) {
            startUpdateLoop()
        }
        Log.i(TAG, "Replay cancelled; service remains active in TELEPORT mode")
    }

    /**
     * Only resets mode to TELEPORT if we are still in ROUTE_REPLAY, to avoid clobbering a mode
     * set by another subsystem while `cancelAndJoin` was suspended (e.g. a new walk-to started
     * immediately after a cancel was requested — see MapController.walkTo/walkViaRoads).
     */
    private fun resetModeIfStillReplaying() {
        if (locationRepository.currentMode.value == MockMode.ROUTE_REPLAY) {
            finishReplay()
        }
    }

    /** Shared teardown for a completed/reset replay: clear route waypoints, zero speed, go idle. */
    private fun finishReplay() {
        locationRepository.setRouteWaypoints(null)
        onSpeedChange(0f)
        locationRepository.setMockMode(MockMode.TELEPORT)
        activeReplayer = routeReplayEngine
    }

    /**
     * Shared setup for both [startReplayWithWaypoints] and [startTeleportReplayWithWaypoints]:
     * stop roaming if active, bail out on too few waypoints, report the starting speed, flip
     * mode/state to running, persist metadata, optionally walk to the start position (only the
     * walking engine needs this — a teleport route jumps to its first point directly), then let
     * the caller start its own engine.
     */
    private suspend fun startReplaySetup(
        waypointCount: Int,
        reportedSpeedMs: Float,
        persistMetadata: (suspend () -> Unit)?,
        walkToStart: (suspend () -> Unit)?,
        startEngine: () -> Unit,
    ) {
        if (locationRepository.currentMode.value == MockMode.ROAMING) roamingRepository.stopRoaming()
        if (waypointCount < 2) return

        onSpeedChange(reportedSpeedMs)

        // Set mode BEFORE state so the state observer sees ROUTE_REPLAY and
        // correctly skips starting the background update loop.
        locationRepository.setMockMode(MockMode.ROUTE_REPLAY)
        persistMetadata?.invoke()
        // Trigger RUNNING after mode is set.
        onStateChange(MockLocationState.RUNNING)
        locationRepository.startSpoofing()

        walkToStart?.invoke()

        startEngine()
    }

    /**
     * Shared engine for both named-route and ephemeral replay.
     *
     * @param waypoints Ordered list of positions to replay (≥2).
     * @param speedMs Playback speed in m/s.
     * @param isLooping Whether to loop at the end.
     * @param persistMetadata If non-null, invoked before replay starts to persist route metadata.
     * @param onComplete Invoked on the service scope when the replay engine signals completion.
     */
    private suspend fun startReplayWithWaypoints(
        waypoints: List<LatLng>,
        speedMs: Double,
        isLooping: Boolean,
        followRoadsToStart: Boolean = false,
        boundaryIndices: List<Int>? = null,
        persistMetadata: (suspend () -> Unit)? = null,
        onComplete: suspend () -> Unit = {
            finishReplay()
            locationRepository.emitCompletion("Route complete")
        },
    ) {
        startReplaySetup(
            waypointCount = waypoints.size,
            reportedSpeedMs = speedMs.toFloat(),
            persistMetadata = persistMetadata,
            walkToStart = { walkToPosition(waypoints.first(), speedMs, followRoadsToStart) },
            startEngine = {
                routeReplayEngine.start(
                    waypoints = waypoints,
                    speedMs = speedMs,
                    isLooping = isLooping,
                    onPositionUpdate = ::tickPosition,
                    onComplete = { scope.launch { onComplete() } },
                    boundaryIndices = boundaryIndices,
                )
                // If pause was requested during walk-to-start (before engine launched),
                // ensure the engine is paused now that it has been initialized.
                if (locationRepository.mockLocationState.value == MockLocationState.PAUSED) routeReplayEngine.pause()
            },
        )
    }

    /**
     * Starts a teleport-route replay: same setup as [startReplayWithWaypoints] but skips
     * the walk-to-start step entirely — a teleport route jumps to its first point too, no
     * walk-to-start — and drives [teleportRouteEngine] instead of [routeReplayEngine].
     *
     * @param waypoints Ordered list of waypoints to replay (≥2), each carrying its own wait
     *   duration.
     * @param isLooping Whether to loop at the end.
     * @param persistMetadata If non-null, invoked before replay starts to persist route metadata.
     * @param onComplete Invoked on the service scope when the replay engine signals completion.
     */
    private suspend fun startTeleportReplayWithWaypoints(
        waypoints: List<Waypoint>,
        isLooping: Boolean,
        randomizeOrder: Boolean = false,
        persistMetadata: (suspend () -> Unit)? = null,
        onComplete: suspend () -> Unit = {
            finishReplay()
            locationRepository.emitCompletion("Route complete")
        },
    ) {
        startReplaySetup(
            waypointCount = waypoints.size,
            reportedSpeedMs = 0f,
            persistMetadata = persistMetadata,
            walkToStart = null,
            startEngine = {
                teleportRouteEngine.start(
                    waypoints = waypoints,
                    isLooping = isLooping,
                    randomizeOrder = randomizeOrder,
                    onPositionUpdate = ::tickPosition,
                    onComplete = { scope.launch { onComplete() } },
                )
                // If pause was requested before the engine launched, ensure it's paused now.
                if (locationRepository.mockLocationState.value == MockLocationState.PAUSED) teleportRouteEngine.pause()
            },
        )
    }

    /**
     * Pre-plans a road-following path between the route's own saved waypoints. The
     * fallback-count/summary-report step is shared with
     * [com.locationjoystick.core.routing.RoamingEngine.planRoadFollowingRoute] via
     * [com.locationjoystick.core.routing.RoutingErrorReporter.reportRoadFollowingFallbacks];
     * the leg-resolution loop itself is intentionally separate — this one resolves a fixed
     * waypoint list known upfront, while `RoamingEngine`'s generates waypoints dynamically
     * within a distance budget (see that method's own doc comment).
     *
     * @return the expanded waypoint list plus the indices within it that are the route's
     *   real, named stops ("boundary indices"), so jump-to-waypoint keeps targeting real stops.
     */
    private suspend fun expandWaypointsForFollowRoads(waypoints: List<LatLng>): Pair<List<LatLng>, List<Int>> {
        // A dense route (recording / GPX import) already traces the real path point-by-point, and
        // road-snapping its legs means one sequential OSRM request per consecutive pair — hundreds
        // of serial round trips, minutes of loading, for no benefit. Above the cap, replay on the
        // route's own points instead; every point is its own boundary so jump-to-waypoint still
        // works, and the walk-to-start leg still road-follows (it's a single request).
        if (waypoints.size > AppConstants.RouteConstants.MAX_FOLLOW_ROADS_EXPANSION_WAYPOINTS) {
            return waypoints to waypoints.indices.toList()
        }
        val expanded = mutableListOf(waypoints.first())
        val boundaryIndices = mutableListOf(0)
        var fallbackCount = 0
        for (i in 0 until waypoints.size - 1) {
            val leg =
                osrmClient.resolveRoute(
                    OsrmClient.PROFILE_FOOT,
                    waypoints[i],
                    waypoints[i + 1],
                    followRoads = true,
                    onFallback = { fallbackCount++ },
                )
            expanded.addAll(leg.drop(1))
            boundaryIndices.add(expanded.size - 1)
        }
        routingErrorReporter.reportRoadFollowingFallbacks(fallbackCount, waypoints.size - 1)
        return expanded to boundaryIndices
    }

    private suspend fun walkToPosition(
        target: LatLng,
        speedMs: Double,
        followRoads: Boolean = false,
    ) {
        val startPos = locationRepository.currentPosition.value ?: return
        if (!followRoads) {
            walkToEngine.walkToOnce(startPos, target, speedMs, ::tickPosition)
            return
        }
        val legs =
            osrmClient.resolveRoute(
                OsrmClient.PROFILE_FOOT,
                startPos,
                target,
                followRoads = true,
                onFallback = ::reportWalkToStartFallback,
            )
        // Clear now — the walk itself can run far longer than the OSRM time budget and must not
        // keep the UI showing a loading state.
        locationRepository.setRoadRouteFetchInFlight(false)
        for (i in 0 until legs.size - 1) {
            walkToEngine.walkToOnce(legs[i], legs[i + 1], speedMs, ::tickPosition)
        }
    }

    private fun reportWalkToStartFallback(reason: OsrmFailureReason) {
        routingErrorReporter.report(
            context.getString(R.string.walk_to_start_fallback_message, osrmFailureMessage(context, reason)),
        )
    }
}
