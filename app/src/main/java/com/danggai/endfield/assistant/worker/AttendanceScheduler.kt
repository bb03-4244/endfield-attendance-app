package com.danggai.endfield.assistant.worker

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object AttendanceScheduler {

    private const val TAG = "AttendanceDebug"

    private const val PERIODIC_WORK_NAME = "endfield_attendance_periodic_work"
    private const val IMMEDIATE_WORK_NAME = "endfield_attendance_immediate_work"

    // 예전 AlarmManager 방식 제거용
    private const val LEGACY_REQUEST_CODE_ATTENDANCE = 1001
    private const val LEGACY_ACTION_ATTENDANCE_ALARM =
        "com.danggai.endfield.assistant.ACTION_ATTENDANCE_ALARM"

    fun ensureScheduled(context: Context) {
        cancelLegacyAlarm(context)

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val periodicWork = PeriodicWorkRequestBuilder<EndfieldWorker>(
            15, TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.LINEAR,
                10,
                TimeUnit.MINUTES
            )
            .addTag(PERIODIC_WORK_NAME)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            periodicWork
        )

        Log.d(TAG, "WorkManager 자동출석 주기 등록 완료 (15분 간격)")
    }

    fun enqueueImmediate(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<EndfieldWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.LINEAR,
                10,
                TimeUnit.MINUTES
            )
            .addTag(IMMEDIATE_WORK_NAME)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            IMMEDIATE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )

        Log.d(TAG, "즉시 자동출석 작업 등록")
    }

    fun cancelAll(context: Context) {
        cancelLegacyAlarm(context)

        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK_NAME)
        WorkManager.getInstance(context).cancelUniqueWork(IMMEDIATE_WORK_NAME)

        Log.d(TAG, "자동출석 WorkManager 작업 취소")
    }

    fun onUserLoggedIn(context: Context) {
        ensureScheduled(context)
        enqueueImmediate(context)
    }

    fun onUserLoggedOut(context: Context) {
        // 로그아웃 후 계속 돌 필요 없으면 취소
        cancelAll(context)
    }

    private fun cancelLegacyAlarm(context: Context) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

            val intent = Intent().apply {
                setClassName(
                    context.packageName,
                    "com.danggai.endfield.assistant.worker.AlarmReceiver"
                )
                action = LEGACY_ACTION_ATTENDANCE_ALARM
            }

            val pendingIntent = PendingIntent.getBroadcast(
                context,
                LEGACY_REQUEST_CODE_ATTENDANCE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()

            Log.d(TAG, "기존 AlarmManager 예약 정리 완료")
        } catch (e: Exception) {
            Log.e(TAG, "기존 AlarmManager 예약 정리 중 오류", e)
        }
    }
}