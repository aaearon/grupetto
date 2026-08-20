package com.spop.poverlay.sensor.tread

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

const val SERVICE_ACTION = "com.onepeloton.affernetservice.ITreadInterface"
private const val SERVICE_PACKAGE = "com.onepeloton.affernetservice"
private const val SERVICE_INTENT = "com.onepeloton.affernetservice.AffernetService"

suspend fun getTreadBinder(context: Context) = suspendCoroutine<IBinder> { ctx ->
    // The service callbacks below can fire more than once (e.g. onServiceConnected
    // succeeds and onBindingDied fires later), and resuming a continuation twice throws
    // IllegalStateException. Guard so the first of {connected, null binding, died} wins
    // and every later callback is a no-op.
    val resumed = AtomicBoolean(false)
    val connection = object : ServiceConnection {
        private fun resumeOnce(block: (Continuation<IBinder>) -> Unit) {
            if (resumed.compareAndSet(false, true)) {
                block(ctx)
            }
        }

        override fun onServiceConnected(p0: ComponentName?, iBinder: IBinder?) {
            Timber.i("Tread sensor service connected $p0")
            if (iBinder == null) {
                Timber.i("Tread sensor service resolution failed $p0")
                resumeOnce { it.resumeWithException(Exception("Tread sensor service resolution failed")) }
            } else {
                resumeOnce { it.resume(iBinder) }
            }
        }

        override fun onBindingDied(name: ComponentName?) {
            super.onBindingDied(name)
            Timber.i("Tread sensor service binding died $name")
            resumeOnce { it.resumeWithException(Exception("Tread sensor service resolution failed")) }
        }

        override fun onNullBinding(name: ComponentName?) {
            Timber.i("Tread sensor service null binding $name")
            resumeOnce { it.resumeWithException(Exception("Tread sensor service resolution failed")) }
        }

        override fun onServiceDisconnected(p0: ComponentName?) {
            Timber.i("Tread sensor service disconnected $p0")
        }
    }

    val bound = context.bindService(
        Intent(SERVICE_INTENT).apply {
            setAction(SERVICE_ACTION)
            setPackage(SERVICE_PACKAGE)
        }, connection, Context.BIND_AUTO_CREATE
    )
    // bindService returns false when the bind could not even be initiated; no callback
    // will ever arrive, so resume with an exception (and unbind to avoid leaking the
    // connection) instead of hanging the coroutine forever.
    if (!bound) {
        Timber.i("Tread sensor service bind could not be initiated")
        context.unbindService(connection)
        if (resumed.compareAndSet(false, true)) {
            ctx.resumeWithException(Exception("Tread sensor service bind could not be initiated"))
        }
    }
}
