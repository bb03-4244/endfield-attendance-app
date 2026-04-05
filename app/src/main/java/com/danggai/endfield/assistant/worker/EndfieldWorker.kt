package com.danggai.endfield.assistant.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.danggai.endfield.assistant.PreferenceManager
import com.danggai.endfield.assistant.R
import com.danggai.endfield.assistant.manager.AttendanceManager
import java.util.Calendar
import java.util.TimeZone

class EndfieldWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    override fun doWork(): Result {
        Log.d(TAG, "Worker 시작")

        val accountToken = PreferenceManager.getUserToken(applicationContext)
        val roleId = PreferenceManager.getUserUid(applicationContext)
        val nickname = PreferenceManager.getUserNickname(applicationContext) ?: "관리자"

        if (accountToken.isNullOrBlank() || roleId.isNullOrBlank()) {
            Log.d(TAG, "Worker 종료 - 로그인 정보 없음")
            return Result.success()
        }

        if (!isAfterResetHour()) {
            Log.d(TAG, "아직 출석 처리 시간 전이라 스킵")
            return Result.success()
        }

        val todayKst = getTodayKstString()
        if (isHandledToday(todayKst)) {
            Log.d(TAG, "오늘 자동출석 처리 이미 완료됨 - 스킵 ($todayKst)")
            return Result.success()
        }

        val serverId = applicationContext
            .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SERVER_ID, "2") ?: "2"

        return try {
            val result = AttendanceManager(applicationContext).runAttendance(
                accountToken = accountToken,
                roleId = roleId,
                serverId = serverId
            )

            when {
                result.success && result.alreadyDone -> {
                    markHandledToday(todayKst)
                    clearFailureThrottle()

                    Log.d(TAG, "이미 오늘 출석 완료")
                    showNotification(
                        title = "이미 오늘 출석 완료됨",
                        content = "이미 오늘 출석이 완료되어 있습니다",
                        nickname = nickname,
                        roleId = roleId
                    )
                    Result.success()
                }

                result.success -> {
                    markHandledToday(todayKst)
                    clearFailureThrottle()

                    val rewardText = if (!result.rewardName.isNullOrBlank() && result.rewardCount != null) {
                        "${result.rewardName} x${result.rewardCount}"
                    } else {
                        "보상 지급 완료"
                    }

                    Log.d(TAG, "출석 성공: $rewardText")
                    showNotification(
                        title = "출석 완료 · $rewardText",
                        content = rewardText,
                        nickname = nickname,
                        roleId = roleId
                    )
                    Result.success()
                }

                else -> {
                    Log.e(TAG, "출석 실패: ${result.message}")

                    maybeShowFailureNotification(
                        nickname = nickname,
                        roleId = roleId,
                        message = result.message.ifBlank { "출석 처리 실패" }
                    )

                    Result.retry()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "출석 오류", e)

            maybeShowFailureNotification(
                nickname = nickname,
                roleId = roleId,
                message = e.message ?: "알 수 없는 오류"
            )

            Result.retry()
        }
    }

    private fun isAfterResetHour(): Boolean {
        val seoul = TimeZone.getTimeZone("Asia/Seoul")
        val now = Calendar.getInstance(seoul)
        return now.get(Calendar.HOUR_OF_DAY) >= 1
    }

    private fun getTodayKstString(): String {
        val seoul = TimeZone.getTimeZone("Asia/Seoul")
        val cal = Calendar.getInstance(seoul)

        return String.format(
            "%04d-%02d-%02d",
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH)
        )
    }

    private fun isHandledToday(todayKst: String): Boolean {
        val pref = applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return pref.getString(KEY_LAST_AUTO_HANDLED_DATE, null) == todayKst
    }

    private fun markHandledToday(todayKst: String) {
        val pref = applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        pref.edit()
            .putString(KEY_LAST_AUTO_HANDLED_DATE, todayKst)
            .apply()
    }

    private fun clearFailureThrottle() {
        val pref = applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        pref.edit()
            .remove(KEY_LAST_FAILURE_NOTIFY_AT)
            .apply()
    }

    private fun maybeShowFailureNotification(
        nickname: String,
        roleId: String,
        message: String
    ) {
        val pref = applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val lastNotifyAt = pref.getLong(KEY_LAST_FAILURE_NOTIFY_AT, 0L)

        val canNotify = now - lastNotifyAt >= FAILURE_NOTIFY_INTERVAL_MS

        if (canNotify) {
            showNotification(
                title = "자동출석 실패",
                content = message,
                nickname = nickname,
                roleId = roleId
            )

            pref.edit()
                .putLong(KEY_LAST_FAILURE_NOTIFY_AT, now)
                .apply()
        } else {
            Log.d(TAG, "실패 알림은 너무 자주 떠서 이번에는 생략")
        }
    }

    private fun showNotification(
        title: String,
        content: String,
        nickname: String,
        roleId: String
    ) {
        createNotificationChannel()

        val detailText = when {
            title.startsWith("출석 완료 ·") -> "$content 획득"
            title.contains("이미") -> "이미 오늘 출석이 완료되어 있습니다"
            else -> if (content.isNotBlank()) content else "출석 처리 중 오류가 발생했습니다"
        }

        val bigText = """
            관리자  $nickname
            UID     $roleId
            
            $detailText
        """.trimIndent()

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(
                if (content.isNotBlank()) content else "엔드필드 자동출석 결과"
            )
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        val manager = NotificationManagerCompat.from(applicationContext)

        if (!manager.areNotificationsEnabled()) {
            Log.e(TAG, "알림 차단 상태 - 시스템에서 앱 알림이 꺼져 있음")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                applicationContext,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED

            if (!granted) {
                Log.e(TAG, "알림 권한 없음 - POST_NOTIFICATIONS 미허용")
                return
            }
        }

        try {
            manager.notify(NOTIFICATION_ID_AUTO_ATTENDANCE, notification)
            Log.d(TAG, "알림 표시 성공: $title")
        } catch (e: Exception) {
            Log.e(TAG, "알림 표시 실패", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val channel = NotificationChannel(
                CHANNEL_ID,
                "자동출석 알림",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "엔드필드 자동출석 결과 알림"
            }

            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val TAG = "AttendanceDebug"
        private const val CHANNEL_ID = "endfield_auto_attendance_channel"
        private const val NOTIFICATION_ID_AUTO_ATTENDANCE = 1002

        private const val PREF_NAME = "pref"
        private const val KEY_SERVER_ID = "serverId"
        private const val KEY_LAST_AUTO_HANDLED_DATE = "lastAutoHandledDate"
        private const val KEY_LAST_FAILURE_NOTIFY_AT = "lastAutoFailureNotifyAt"

        private const val FAILURE_NOTIFY_INTERVAL_MS = 2 * 60 * 60 * 1000L // 2시간
    }
}