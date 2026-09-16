# Route System

Waypoints on map → polyline. Save, edit, replay, loop, record real-time.

Key files: `:feature:routes:impl/RoutesScreen.kt`, `:feature:routes:impl/RouteCreatorScreen.kt`, `:feature:routes:impl/RoutesViewModel.kt`, `:core:database/RouteDao.kt`, `:core:routing/RouteReplayEngine.kt`

## Route Types

- **STRAIGHT** (`RouteType.STRAIGHT`): straight segments, no network.
- **GUIDED** (`RouteType.GUIDED`): OSRM road-following. On fail → `osrmError = true` in `CreatorState`. No silent fallback.
- **TELEPORT** (`RouteType.TELEPORT`): instant jumps between waypoints, waiting at each — see "Teleport Routes" below.

## Storage

`RouteEntity` + `WaypointEntity` one-to-many. Waypoints: `routeId`, `lat`, `lon`, `orderIndex`. Query via `@Transaction @Query` → `Flow<RouteWithWaypoints>`.

Routes can also be imported from GPX files via the Routes screen overflow menu → "Import GPX". Max file size: 10 MB. Parsed and saved as `RouteType.STRAIGHT` routes.

## Replay

- Interpolate waypoints at speed (m/s).
- Bearing: recomputed each tick from the previous to the current interpolated position
  via `calculateBearing` (`core/common/util/GeoUtils.kt`), published through
  `LocationRepository.currentBearing` and picked up by `MockLocationService` — covers
  route replay, ephemeral replay, the walk-to-start-of-route phase, and waypoint jumps.
- Advance: `speed * deltaTime`. `RouteInterpolator.interpolateAlongRoute()` consumes this whole
  per-tick budget across as many consecutive waypoints as it spans in one call (dense geometry,
  e.g. many closely spaced saved waypoints, can put several within a single tick's travel
  distance) rather than carrying leftover distance forward only one segment and dropping any
  remainder beyond it (issue #75).
- Snap at `AppConstants.LocationConstants.WALK_ARRIVAL_THRESHOLD_METERS`.
- Loop: smooth interpolation last→first waypoint.
- Pausing keeps pushing the frozen position to the mock location provider
  every tick (1 Hz) instead of stopping ticks entirely — otherwise the mock
  fix goes stale and some location consumers fall back to the device's real
  GPS position until the replay resumes.

### Per-Route Speed Profile

A route may pin a speed profile via the Route Detail (edit) screen — a dropdown (`ExposedDropdownMenuBox`) below the name field, showing "None" plus all 5 presets. Newly created routes (from the Route Creator) default to the **Drive** profile (`AppConstants.ProfileConstants.PROFILE_ID_DRIVE`), set in `RouteCreatorViewModel.saveRoute` — except `RouteType.TELEPORT` routes, which never read `speedProfileId` and stay `null`. A pin can still be changed (or cleared back to "None") on the Route Detail screen; "None" means replay starts at whatever speed profile is currently active globally, while a pinned profile seeds replay at that profile's speed instead. Either way, this only seeds the replay's starting speed (resolved via `SettingsRepository.getRouteSpeedMs(route.speedProfileId)` in `StartRouteReplayUseCase`) — the user can still change speed mid-replay via the widget's Speed Cycle button (or Settings → GPS) like any other movement mode; a pin does not lock the speed for the session.

The speed profile control is hidden entirely for `RouteType.TELEPORT` routes, since teleport replay never reads `speedProfileId` (see "Teleport Routes" below).

### Next / Previous Waypoint (Teleport)

While a named route replay is active (running or paused), "Previous
waypoint" / "Next waypoint" buttons instantly teleport the spoofed position
to the adjacent stop in the route — skipping interpolation between them.
Available on all three route-control surfaces (see
@docs/features/widget.md, "Route Controls Across Surfaces"), alongside
Pause/Resume/Stop.

Implemented as `RouteReplayEngine.jumpToNextWaypoint()` /
`jumpToPreviousWaypoint()`, reusing the engine's existing
`resumeWaypointIndex` pointer rather than tracking a separate discrete
index. When Follow roads has expanded the waypoint list, jumps snap to the
nearest *named* waypoint via the engine's boundary-index list rather than
the nearest expanded point. Not available for ephemeral (walk-here "Add next point") replay,
which has no persisted waypoint list. Gated by `hideTeleportFeatures` like every other teleport entry point
(@docs/features/hide-teleport.md), **and** by a separate, independent
opt-in toggle — `AppSettings.showRouteJumpButtons` (Settings → Menus →
Privacy → "Show route jump buttons", DataStore key
`show_route_jump_buttons`, default `false`). Both must allow the buttons
for them to show: `!hideTeleportFeatures && showRouteJumpButtons`.
Jumping to the last waypoint while replay is running lets it complete
naturally on the next tick, same as reaching it by walking.

### Start Flow

Starting a saved route shows a bottom sheet (Routes screen, map long-press
sheet, widget panel/floating map) with four checkboxes — Loop, Reverse,
Return to location, and **Follow roads** — plus a standalone **Teleport**
button and, at the bottom, **Cancel** / **Start**.

- **Follow roads** — when checked, road-following (via OSRM, foot profile)
  applies to both the walk from the current position to the route's first
  waypoint AND every leg between the route's own saved waypoints during
  replay. Unchecked, both use straight-line interpolation (today's default
  behavior). Per-leg OSRM failures fall back to a straight line for that
  leg only; if any between-waypoint legs fell back, one summary message is
  reported via `RoutingErrorReporter` (e.g. "Road-following partially
  unavailable — 2 of 5 legs used straight-line paths"), mirroring
  `RoamingEngine.planRoadFollowingRoute` (@docs/features/roaming.md).
  Between-waypoint road-snapping resolves one OSRM request per consecutive
  pair, sequentially, so it only runs when the route has at most
  `AppConstants.RouteConstants.MAX_FOLLOW_ROADS_EXPANSION_WAYPOINTS` (25)
  saved waypoints. A denser route (a recording or GPX import with hundreds
  of points) already traces the real path point-by-point, so
  `ReplayOrchestrator.expandWaypointsForFollowRoads` skips the expansion and
  replays on the route's own waypoints instead of firing hundreds of serial
  requests (minutes of loading); the walk-to-start leg still road-follows,
  and every own-waypoint stays a jump-to-waypoint boundary.
- **Teleport** — instantly teleports to the route's first waypoint (last,
  if Reverse is checked). Does not start replay; the sheet stays open so
  the user can still press Start afterward. Hidden when
  `hideTeleportFeatures` is on (@docs/features/hide-teleport.md).
- **Start** — walks (straight or via roads, per Follow roads) from the
  current position to the first waypoint, then begins replay honoring
  Loop/Reverse/Return to location and Follow roads for the between-waypoint
  legs too.
- While a road-following start's OSRM fetch is in flight, the Start button shows a spinner and is
  disabled; the sheet stays open and closes automatically once the fetch resolves — the same
  loading treatment "Walk via roads" / "Add next point via roads" already use elsewhere.

Implemented via a `followRoadsToStart: Boolean` flag (name unchanged, scope
widened) threaded through `StartRouteReplayUseCase` / `RoutesViewModel.startReplay()`
into `ReplayOrchestrator.handleStart()`, which now expands the route's
waypoint list into a road-resolved path (`expandWaypointsForFollowRoads`,
via `OsrmClient.resolveRoute()` per leg) before handing it to
`RouteReplayEngine`, in addition to resolving the walk-to-start leg the
same way it already did. `RouteReplayEngine` tracks which indices in the
(possibly expanded) waypoint list are the route's real, named stops
("boundary indices"), so Previous/Next Waypoint (below) keeps jumping
between actual stops rather than the denser road-following points.

The same `replayWaypoints` list feeds `LocationRepository.routeWaypoints`, so the
map's polyline (main screen and floating widget map, via `MapController`'s existing
generic `routeTrace` plumbing) shows the resolved road-following path during replay,
not the route's saved straight-line waypoints — reverting to the saved shape once the
session ends or restarts without Follow roads.

## Teleport Routes

A `RouteType.TELEPORT` route replays by instantly jumping between waypoints instead of
interpolating movement — driven by `TeleportRouteEngine` (`:core:routing`), not
`RouteReplayEngine`. Each `Waypoint.waitSeconds` (min
`AppConstants.RouteConstants.MIN_TELEPORT_WAIT_SECONDS`, default
`AppConstants.RouteConstants.DEFAULT_TELEPORT_WAIT_SECONDS`) is how long the spoofed
position stays frozen at that stop before jumping to the next one. Like paused route
replay, the frozen position is still pushed to the mock provider every tick so the fix
never goes stale.

- **Placement**: in the route creator, placing a point on a `TELEPORT` route (map tap,
  search result, or favorite) shows a modal asking for the wait duration in seconds
  before the point is added. The Route Detail (edit) screen also lets each waypoint's
  wait duration be edited after the route is saved (an edit icon on each waypoint row,
  teleport routes only) — reuses the same validation as the creator's placement-time
  modal (minimum `AppConstants.RouteConstants.MIN_TELEPORT_WAIT_SECONDS`). Saves live on
  confirm, like the per-route speed profile control above.
- **Bulk wait-time edit**: the Route Detail screen (teleport routes only) shows a "Set
  wait time for all waypoints" button above the waypoint list, reusing the same wait
  dialog and validation as the per-waypoint edit. Overwrites `waitSeconds` on every
  waypoint in the route in one write (`RouteRepository.setAllWaypointsWaitSeconds`,
  `RouteDao.updateWaitSecondsForRoute`) — added so a large route (dozens of stops) can
  be retimed without opening each waypoint individually (issue #72 follow-up).
- **Randomize order**: a per-route toggle (`Route.randomizeTeleportOrder`, Route Detail
  screen, teleport routes only) shuffles the waypoint jump order. `TeleportRouteEngine`
  shuffles once on `start()` and again every time the loop restarts (`isLooping = true`
  and the last waypoint is reached), never mid-loop. Loop/no-loop is controlled entirely
  by the existing Loop checkbox on the start sheet — this toggle only changes the order,
  not whether it repeats. Persists on the route (round-trips through `ExportData` like
  `speedProfileId`) rather than being chosen per-start, matching the speed-profile pin
  pattern above.
- **No road-following**: "Follow roads" is hidden entirely on the start sheet for a
  teleport route — it has no meaning when nothing walks between points.
- **Loop / Reverse / Return to location**: unaffected — these only decide which
  waypoints replay in what order, not whether movement between them is instant.
- **Next / Previous waypoint jumps**: using the route-jump buttons
  (@docs/features/widget.md, "Route Controls Across Surfaces") while a teleport replay
  is waiting at a point resets that point's wait timer — landing on a waypoint, from
  either direction, always restarts its full configured wait.
- **Speed profile**: a teleport route's `speedProfileId` is never read during replay —
  nothing moves at a "speed" — so the widget's Speed Cycle button reports 0 m/s while a
  teleport replay is active.

## Recording

- Collect location every `AppConstants.LocationConstants.UPDATE_INTERVAL_MS` ms.
- Simplify via Ramer-Douglas-Peucker.
- Save on stop.

## Hot Routes

Settings → Routes → "Show hot routes" toggle (default off). When enabled, upserts curated GPX-based routes into the routes DB. When disabled, removes only entries this feature inserted.

Key files: `:core:data/RouteRepository.kt` (`HOT_ROUTES` list + `upsertHotRoutes`/`removeHotRoutes`), `:core:datastore/AppPreferencesDataSource.kt` (`hot_routes_enabled` key)

**Upsert rule**: match by name + city. IDs prefixed with `hot_route_`. If a route with same name already exists, coordinates are updated and original ID is preserved.

**Remove rule**: delete all routes whose ID starts with `hot_route_`.

**Export/import**: `hotRoutesEnabled` + `selectedHotRouteIds` fields in `ExportData`. Importing a backup with it `true` re-applies the upsert.

Route assets are bundled GPX files under `assets/hot_routes/`. All hot routes are saved as `RouteType.STRAIGHT` or `RouteType.GUIDED` depending on the asset.

## Edge Cases

- <2 waypoints → replay disabled.
- Resume after restart: persist waypoint index in DataStore.