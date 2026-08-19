package com.spop.poverlay.sensor.tread

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import kotlin.coroutines.resume

private const val DETECT_TIMEOUT_MS = 3000L

/**
 * Bind-probe Tread detection (research doc section 8): a Tread is identified by the
 * affernet service producing a non-null `ITreadInterface` binder, not by
 * `Build.MODEL` (which is shared across Bike+/Tread/Row).
 *
 * Binds with [Context.BIND_AUTO_CREATE], waits up to [DETECT_TIMEOUT_MS] for a
 * non-null binding, then always unbinds. Returns false on timeout/null/exception.
 * This never sends any transaction to the tread.
 */
suspend fun detectIsTread(context: Context): Boolean {
    var connection: ServiceConnection? = null
    return try {
        val bound = withTimeoutOrNull(DETECT_TIMEOUT_MS) {
            suspendCancellableCoroutine<Boolean> { cont ->
                val conn = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                        if (cont.isActive) cont.resume(service != null)
                    }

                    override fun onNullBinding(name: ComponentName?) {
                        if (cont.isActive) cont.resume(false)
                    }

                    override fun onServiceDisconnected(name: ComponentName?) {}
                }
                connection = conn
                val started = context.bindService(
                    Intent(SERVICE_ACTION).apply { setPackage("com.onepeloton.affernetservice") },
                    conn,
                    Context.BIND_AUTO_CREATE
                )
                if (!started && cont.isActive) cont.resume(false)
            }
        } ?: false
        Timber.d("Tread bind-probe result: $bound")
        bound
    } catch (e: Exception) {
        Timber.w(e, "Tread bind-probe failed")
        false
    } finally {
        connection?.let {
            try {
                context.unbindService(it)
            } catch (e: Exception) {
                Timber.w(e, "Failed to unbind tread probe")
            }
        }
    }
}
