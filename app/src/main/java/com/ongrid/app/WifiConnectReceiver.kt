package com.ongrid.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.Constraints
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.ongrid.app.data.local.DreamScheduleType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Receives [android.net.ConnectivityManager.CONNECTIVITY_ACTION] broadcasts and triggers
 * a one-shot [DreamWorker] if there are any active
 * [com.ongrid.app.data.local.DreamScheduleType.WIFI_CONNECT] schedules.
 *
 * Declared in AndroidManifest with a [android.net.ConnectivityManager.CONNECTIVITY_ACTION]
 * intent filter (uses `<uses-permission CHANGE_NETWORK_STATE>`  — already present in the
 * manifest for network scanning).
 */
class WifiConnectReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (!isWifiConnected(context)) return

        scope.launch {
            val app = context.applicationContext as OnGridApplication
            val wifiSchedules = app.database.dreamScheduleDao()
                .allEnabledSchedules()
                .filter { it.scheduleType == DreamScheduleType.WIFI_CONNECT }

            wifiSchedules.forEach { schedule ->
                WorkManager.getInstance(context).enqueue(
                    OneTimeWorkRequestBuilder<DreamWorker>()
                        .setConstraints(
                            Constraints.Builder().setRequiresBatteryNotLow(false).build()
                        )
                        .setInputData(workDataOf(DreamWorker.INPUT_KEY_AGENT_ID to schedule.agentId))
                        .addTag("dream_wifi_trigger")
                        .build()
                )
            }
        }
    }

    private fun isWifiConnected(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }
}
