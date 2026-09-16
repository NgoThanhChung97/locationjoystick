package com.locationjoystick.core.designsystem.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import com.locationjoystick.core.common.constants.AppConstants
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Dismisses a road-following start/walk sheet once its OSRM fetch resolves.
 *
 * The fetch runs in a service and is surfaced to the UI as the transient [isRoadRouteFetchInFlight]
 * flag. Relying on observing that flag's `false → true → false` round trip is fragile: a fast
 * failure (offline) or an instant/cached resolve can flip it back before the rising edge propagates
 * through the service → repository → ViewModel → Compose `StateFlow` chain (StateFlow conflates), so
 * the rising edge is lost and a sheet waiting for it stays stuck on its loading spinner forever.
 *
 * This waits for the rising edge only up to
 * [AppConstants.OsrmConstants.ROAD_START_OBSERVE_TIMEOUT_MS]; whether or not it arrives, once the
 * fetch is (or is presumed) resolved it calls [onResolved] exactly once, so the sheet always closes.
 *
 * @param awaiting `true` once the user has kicked off a road-following start/walk from this sheet.
 * @param isRoadRouteFetchInFlight the live in-flight flag observed from the ViewModel.
 * @param onResolved invoked once the fetch resolves (or is presumed resolved) — dismiss the sheet.
 */
@Composable
fun RoadFetchDismissEffect(
    awaiting: Boolean,
    isRoadRouteFetchInFlight: Boolean,
    onResolved: () -> Unit,
) {
    // rememberUpdatedState makes the plain parameter readable as snapshot state, so snapshotFlow
    // below re-emits when the flag changes instead of capturing its value at effect-launch time.
    val inFlight = rememberUpdatedState(isRoadRouteFetchInFlight)
    LaunchedEffect(awaiting) {
        if (!awaiting) return@LaunchedEffect
        val roseToInFlight =
            withTimeoutOrNull(AppConstants.OsrmConstants.ROAD_START_OBSERVE_TIMEOUT_MS) {
                snapshotFlow { inFlight.value }.first { it }
            } != null
        // Only wait for the falling edge if we actually saw the rising one; an instant/failed fetch
        // never surfaces `true`, in which case we treat it as already resolved.
        if (roseToInFlight) {
            snapshotFlow { inFlight.value }.first { !it }
        }
        onResolved()
    }
}
