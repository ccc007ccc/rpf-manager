package com.gtav.rpfmanager.astc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.gtav.rpfmanager.R
import com.gtav.rpfmanager.RpfCore
import com.gtav.rpfmanager.MainActivity
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*

class AstcService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cancelled = AtomicBoolean(false)
    @Volatile private var nativeHandle = 0L
    private var worker: Job? = null
    private var renew: Job? = null
    @Volatile private var timeoutReason: String? = null
    private var lastUpdate = 0L
    private lateinit var wakeLock: PowerManager.WakeLock
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.astc_title), NotificationManager.IMPORTANCE_LOW))
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:AstcConversion").apply { setReferenceCounted(false) }
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == CANCEL) {
            cancelled.set(true)
            if (nativeHandle != 0L) RpfCore.cancelOperation(nativeHandle)
            if (worker?.isActive != true) stopSelf()
            return START_NOT_STICKY
        }
        if (worker?.isActive == true) return START_NOT_STICKY
        val request = runCatching { AstcRequest.parse(intent?.getStringExtra(REQUEST) ?: "") }.getOrNull()
        if (request == null) { stopSelf(); return START_NOT_STICKY }
        cancelled.set(false)
        timeoutReason = null
        val initial = AstcState(AstcPhase.Preparing, request)
        AstcJobs.mutable.value = initial
        ServiceCompat.startForeground(this, NOTIFICATION, notification(initial),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        worker = scope.launch {
            renew = launch {
                while (isActive) {
                    wakeLock.acquire(120_000)
                    delay(60_000)
                }
            }
            try {
                AstcRunner(applicationContext, request, cancelled, {
                    nativeHandle = it
                    if (it != 0L && cancelled.get()) RpfCore.cancelOperation(it)
                }, ::changed).run()
            } catch (error: Throwable) {
                changed(AstcJobs.state.value.copy(phase = AstcPhase.Failed, detail = error.message ?: "Native conversion failed"))
            } finally {
                renew?.cancelAndJoin()
                if (wakeLock.isHeld) wakeLock.release()
                ServiceCompat.stopForeground(this@AstcService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }
    private fun changed(next: AstcState) {
        val state = timeoutReason?.let { next.copy(phase = if (next.running) AstcPhase.Stopping else AstcPhase.Failed, detail = it) } ?: next
        AstcJobs.mutable.value = state
        val now = SystemClock.elapsedRealtime()
        if (!state.running || now - lastUpdate >= 1000) {
            lastUpdate = now
            runCatching { AstcStateStore.save(this, state) }
            if (state.running && (Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)) {
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(state))
            }
        }
    }
    private fun notification(state: AstcState): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .putExtra(OPEN_TOOLS, true).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val cancel = PendingIntent.getService(this, 1, Intent(this, AstcService::class.java).setAction(CANCEL), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(if (state.request?.scanOnly == true) R.string.astc_scan else R.string.astc_title))
            .setContentText(getString(R.string.astc_notification_progress, state.completed, state.total, state.converted))
            .setContentIntent(open).setOnlyAlertOnce(true).setOngoing(true)
            .setProgress(state.total, state.completed, state.total == 0)
            .addAction(0, getString(R.string.astc_stop), cancel).build()
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        timeoutReason = getString(R.string.astc_service_timeout)
        cancelled.set(true)
        renew?.cancel()
        if (nativeHandle != 0L) RpfCore.cancelOperation(nativeHandle)
        changed(AstcJobs.state.value.copy(phase = AstcPhase.Stopping, detail = getString(R.string.astc_service_timeout)))
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    override fun onDestroy() {
        cancelled.set(true)
        renew?.cancel()
        if (nativeHandle != 0L) RpfCore.cancelOperation(nativeHandle)
        if (wakeLock.isHeld) wakeLock.release()
        // Let a synchronous JNI call observe its cancel flag and release its FDs.
        worker?.invokeOnCompletion { scope.cancel() } ?: scope.cancel()
        super.onDestroy()
    }
    companion object {
        const val REQUEST = "astc_request"
        const val CANCEL = "com.gtav.rpfmanager.astc.CANCEL"
        const val OPEN_TOOLS = "com.gtav.rpfmanager.astc.OPEN"
        private const val CHANNEL = "astc_conversion"
        private const val NOTIFICATION = 4106
    }
}
