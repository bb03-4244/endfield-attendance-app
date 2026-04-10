package com.danggai.endfield.assistant.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return

        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.d("AttendanceDebug", "부팅/업데이트 후 WorkManager 자동출석 재등록")
                AttendanceScheduler.ensureScheduled(context)
            }
        }
    }
}