package com.blake.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.os.CancellationSignal
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/** Where a spend happened. Uses the platform LocationManager (no Google Play services);
 * returns null whenever permission is missing or no fix arrives in time, and the spend is
 * saved without a location in that case. */
object LocationHelper {
    val PERMISSIONS = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    private const val MAX_CACHED_AGE_MS = 15 * 60 * 1000L
    private const val FRESH_FIX_TIMEOUT_MS = 4_000L

    fun hasPermission(context: Context): Boolean = PERMISSIONS.any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    suspend fun current(context: Context): Location? {
        if (!hasPermission(context)) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        return try {
            val providers = lm.getProviders(true)
            val cached = providers.mapNotNull { lm.getLastKnownLocation(it) }
                .filter { System.currentTimeMillis() - it.time <= MAX_CACHED_AGE_MS }
                .minByOrNull { it.accuracy }
            if (cached != null) return cached

            val provider = when {
                LocationManager.GPS_PROVIDER in providers -> LocationManager.GPS_PROVIDER
                LocationManager.NETWORK_PROVIDER in providers -> LocationManager.NETWORK_PROVIDER
                else -> return null
            }
            val executor = Executors.newSingleThreadExecutor()
            try {
                withTimeoutOrNull(FRESH_FIX_TIMEOUT_MS) {
                    suspendCancellableCoroutine<Location?> { cont ->
                        val signal = CancellationSignal()
                        cont.invokeOnCancellation { signal.cancel() }
                        LocationManagerCompat.getCurrentLocation(lm, provider, signal, executor) { loc ->
                            if (cont.isActive) cont.resume(loc)
                        }
                    }
                }
            } finally {
                executor.shutdown()
            }
        } catch (e: SecurityException) {
            null
        }
    }
}
