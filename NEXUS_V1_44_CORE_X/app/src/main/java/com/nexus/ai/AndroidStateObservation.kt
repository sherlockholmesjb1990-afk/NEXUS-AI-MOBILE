package com.nexus.ai

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager

/** V1.24: read-only, permission-aware Android state observations. */
class AndroidStateObservationAdapter(private val context: Context) : ObservationAdapter {
    override val source = "android_state"

    override fun observe(subjectId: String?, cycleId: String?): List<NexusObservation> {
        val now = System.currentTimeMillis()
        val expiry = now + 5_000L
        return listOf(
            batteryObservation(now, expiry, cycleId),
            connectivityObservation(now, expiry, cycleId),
            screenObservation(now, expiry, cycleId),
            appProcessObservation(now, expiry, cycleId)
        )
    }

    private fun batteryObservation(now: Long, expiry: Long, cycleId: String?): NexusObservation {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val charging = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)?.let {
            it == BatteryManager.BATTERY_STATUS_CHARGING || it == BatteryManager.BATTERY_STATUS_FULL
        } ?: false
        val known = pct in 0..100
        return NexusObservation(
            kind = ObservationKind.ANDROID_STATE,
            subjectId = "android:battery",
            state = if (known) ObservationState.ACTIVE else ObservationState.UNKNOWN,
            fact = if (known) "Bateria=${pct}%; carregando=$charging." else "Estado da bateria indisponível.",
            confidence = if (known) 1.0 else 0.0,
            source = source,
            createdAt = now,
            cycleId = cycleId,
            expiresAt = expiry
        )
    }

    private fun connectivityObservation(now: Long, expiry: Long, cycleId: String?): NexusObservation {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return NexusObservation(
                kind = ObservationKind.ANDROID_STATE,
                subjectId = "android:network",
                state = ObservationState.UNKNOWN,
                fact = "Conectividade indisponível.",
                confidence = 0.0,
                source = source,
                createdAt = now,
                expiresAt = expiry,
                cycleId = cycleId
            )
        val network = cm.activeNetwork
        val caps = network?.let { cm.getNetworkCapabilities(it) }
        val connected = caps != null
        val transport = when {
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "WIFI"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "CELLULAR"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "ETHERNET"
            connected -> "OTHER"
            else -> "NONE"
        }
        return NexusObservation(
            kind = ObservationKind.ANDROID_STATE,
            subjectId = "android:network",
            state = if (connected) ObservationState.ACTIVE else ObservationState.PENDING,
            fact = "Rede ativa=$connected; transporte=$transport.",
            confidence = 1.0,
            source = source,
            createdAt = now,
            expiresAt = expiry,
            cycleId = cycleId
        )
    }

    private fun screenObservation(now: Long, expiry: Long, cycleId: String?): NexusObservation {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val interactive = pm?.isInteractive
        return NexusObservation(
            kind = ObservationKind.ANDROID_STATE,
            subjectId = "android:screen",
            state = if (interactive == true) ObservationState.ACTIVE else if (interactive == false) ObservationState.PENDING else ObservationState.UNKNOWN,
            fact = "Tela interativa=$interactive.",
            confidence = if (interactive == null) 0.0 else 1.0,
            source = source,
            createdAt = now,
            expiresAt = expiry,
            cycleId = cycleId
        )
    }

    private fun appProcessObservation(now: Long, expiry: Long, cycleId: String?): NexusObservation {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return NexusObservation(
                kind = ObservationKind.ANDROID_STATE,
                subjectId = "android:app",
                state = ObservationState.UNKNOWN,
                fact = "Estado do processo indisponível.",
                confidence = 0.0,
                source = source,
                createdAt = now,
                expiresAt = expiry,
                cycleId = cycleId
            )
        val importance = am.runningAppProcesses?.firstOrNull { it.processName == context.packageName }?.importance
        val foreground = importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        val known = importance != null
        return NexusObservation(
            kind = ObservationKind.ANDROID_STATE,
            subjectId = "android:app",
            state = if (!known) ObservationState.UNKNOWN else if (foreground) ObservationState.ACTIVE else ObservationState.PENDING,
            fact = "Processo do NEXUS conhecido=$known; foreground=$foreground; sdk=${Build.VERSION.SDK_INT}.",
            confidence = if (known) 1.0 else 0.0,
            source = source,
            createdAt = now,
            expiresAt = expiry,
            cycleId = cycleId
        )
    }
}
