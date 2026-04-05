package com.danggai.endfield.assistant

import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.danggai.endfield.assistant.api.AttendanceResult
import com.danggai.endfield.assistant.databinding.ActivityMainBinding
import com.danggai.endfield.assistant.manager.AttendanceManager
import com.danggai.endfield.assistant.worker.AttendanceScheduler

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var attendanceManager: AttendanceManager

    private val guidePrefs by lazy {
        getSharedPreferences("startup_guide_pref", MODE_PRIVATE)
    }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                Toast.makeText(this, "알림 권한이 허용되었습니다.", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(
                    this,
                    "알림 권한이 꺼져 있으면 자동출석 결과 알림이 보이지 않을 수 있습니다.",
                    Toast.LENGTH_LONG
                ).show()
                openAppNotificationSettings()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = Color.parseColor("#F5F6FA")
        window.navigationBarColor = Color.parseColor("#F5F6FA")

        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.isAppearanceLightStatusBars = true
        controller.isAppearanceLightNavigationBars = true

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        attendanceManager = AttendanceManager(this)

        updateUI()
        setIdleResultCard()
        setSanityPlaceholder()

        binding.btnCheckIn.setOnClickListener {
            handleManualAttendance()
        }

        binding.btnLogin.setOnClickListener {
            handleLoginButton()
        }

        showStartupGuidesIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    private fun updateUI() {
        val token = PreferenceManager.getUserToken(this)
        val uid = PreferenceManager.getUserUid(this)
        val nickname = PreferenceManager.getUserNickname(this)

        if (!token.isNullOrBlank() && !uid.isNullOrBlank()) {
            binding.txtStatus.text = uid
            binding.txtUserSub.text = if (!nickname.isNullOrBlank()) {
                "닉네임: $nickname"
            } else {
                "로그인된 계정"
            }

            binding.btnLogin.text = "로그인 정보 삭제 후 다시 로그인"
            binding.btnCheckIn.isEnabled = true
        } else {
            binding.txtStatus.text = "로그인이 필요합니다."
            binding.txtUserSub.text = "로그인 후 자동출석과 수동출석을 사용할 수 있습니다."

            binding.btnLogin.text = "로그인하기"
            binding.btnCheckIn.isEnabled = false
        }
    }

    private fun showStartupGuidesIfNeeded() {
        val startupGuideShown = guidePrefs.getBoolean(KEY_STARTUP_GUIDE_SHOWN, false)
        if (startupGuideShown) return

        guidePrefs.edit().putBoolean(KEY_STARTUP_GUIDE_SHOWN, true).apply()
        showNotificationPermissionDialog()
    }

    private fun showNotificationPermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle("알림 권한 필요")
            .setMessage("자동출석 결과를 바로 확인하려면 알림 권한을 허용해 주세요.")
            .setCancelable(false)
            .setPositiveButton("허용하기") { _, _ ->
                requestNotificationPermissionIfNeeded()
                showBatteryOptimizationDialog()
            }
            .setNegativeButton("나중에") { _, _ ->
                showBatteryOptimizationDialog()
            }
            .show()
    }

    private fun showBatteryOptimizationDialog() {
        AlertDialog.Builder(this)
            .setTitle("배터리 최적화 제외 권장")
            .setMessage("자동출석이 백그라운드에서 더 안정적으로 실행되려면 배터리 최적화 제외를 권장합니다.")
            .setCancelable(false)
            .setPositiveButton("설정 열기") { _, _ ->
                openBatteryOptimizationRequest()
            }
            .setNegativeButton("나중에", null)
            .show()
    }

    private fun handleLoginButton() {
        val token = PreferenceManager.getUserToken(this)
        val uid = PreferenceManager.getUserUid(this)

        if (!token.isNullOrBlank() && !uid.isNullOrBlank()) {
            PreferenceManager.clearUser(this)
            WebLoginManager.clearWebSession(this)
            AttendanceScheduler.onUserLoggedOut(this)

            Toast.makeText(
                this,
                "로그인 정보와 웹 세션을 삭제했습니다.",
                Toast.LENGTH_SHORT
            ).show()

            updateUI()
            setIdleResultCard()
            startActivity(Intent(this, LoginActivity::class.java))
            return
        }

        startActivity(Intent(this, LoginActivity::class.java))
    }

    private fun handleManualAttendance() {
        val token = PreferenceManager.getUserToken(this)
        val uid = PreferenceManager.getUserUid(this)

        if (token.isNullOrBlank() || uid.isNullOrBlank()) {
            Toast.makeText(this, "로그인이 필요합니다.", Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, LoginActivity::class.java))
            return
        }

        val serverId = getSharedPreferences("pref", MODE_PRIVATE)
            .getString("serverId", "2") ?: "2"

        binding.btnCheckIn.isEnabled = false
        binding.btnCheckIn.text = "출석 확인 중..."

        binding.txtResultTitle.text = "출석 결과"
        binding.txtResult.text = "수동 출석을 확인하고 있습니다..."
        binding.layoutReward.visibility = View.GONE

        attendanceManager.runAttendanceAsync(
            accountToken = token,
            roleId = uid,
            serverId = serverId
        ) { result ->
            runOnUiThread {
                binding.btnCheckIn.isEnabled = true
                binding.btnCheckIn.text = "수동 출석체크"
                handleAttendanceResult(result)
            }
        }
    }

    private fun handleAttendanceResult(result: AttendanceResult) {
        val nickname = PreferenceManager.getUserNickname(this) ?: "관리자"
        val uid = PreferenceManager.getUserUid(this) ?: "-"

        when {
            result.success && result.alreadyDone -> {
                binding.txtResultTitle.text = "출석 결과"
                binding.txtResult.text = "이미 오늘 출석이 완료되어 있습니다."

                showRewardSection(
                    rewardName = result.rewardName,
                    rewardCount = result.rewardCount
                )

                showManualNotification(
                    title = "출석 완료 · 이미 처리됨",
                    content = "이미 오늘 출석이 완료되어 있습니다.",
                    nickname = nickname,
                    roleId = uid
                )
            }

            result.success -> {
                binding.txtResultTitle.text = "출석 성공"
                binding.txtResult.text = "출석이 정상적으로 완료되었습니다."

                showRewardSection(
                    rewardName = result.rewardName,
                    rewardCount = result.rewardCount
                )

                val rewardText = buildRewardText(result.rewardName, result.rewardCount)

                showManualNotification(
                    title = "출석 완료 · $rewardText",
                    content = rewardText,
                    nickname = nickname,
                    roleId = uid
                )
            }

            else -> {
                binding.txtResultTitle.text = "출석 실패"
                binding.txtResult.text = result.message
                binding.layoutReward.visibility = View.GONE

                showManualNotification(
                    title = "출석 실패",
                    content = result.message,
                    nickname = nickname,
                    roleId = uid
                )
            }
        }
    }

    private fun showRewardSection(rewardName: String?, rewardCount: Int?) {
        val rewardText = buildRewardText(rewardName, rewardCount)

        binding.layoutReward.visibility = View.VISIBLE
        binding.txtRewardLabel.text = "오늘 보상"
        binding.txtReward.text = rewardText

        val rewardRes = getRewardIconRes(rewardName)
        if (rewardRes != null) {
            binding.imgReward.visibility = View.VISIBLE
            binding.imgReward.setImageResource(rewardRes)
        } else {
            binding.imgReward.visibility = View.GONE
        }
    }

    private fun buildRewardText(rewardName: String?, rewardCount: Int?): String {
        return when {
            !rewardName.isNullOrBlank() && rewardCount != null -> "$rewardName x$rewardCount"
            !rewardName.isNullOrBlank() -> rewardName
            else -> "오늘 보상 정보를 불러오지 못했습니다."
        }
    }

    private fun setIdleResultCard() {
        binding.txtResultTitle.text = "출석 결과"
        binding.txtResult.text = "아직 실행되지 않음"
        binding.layoutReward.visibility = View.GONE
    }

    private fun setSanityPlaceholder() {
        binding.txtSanityTitle.text = "이성"
        binding.txtSanityValue.text = "연동하지 않음"
        binding.txtSanitySub.text = "안정성을 위해 제외함"
        binding.imgSanity.setImageResource(R.drawable.ic_sanity)
        binding.imgSanity.visibility = View.VISIBLE
    }

    private fun getRewardIconRes(rewardName: String?): Int? {
        return when (rewardName) {
            "중급 작전 기록" -> R.drawable.endfield_attendance_1
            "초급 인지 매개체" -> R.drawable.endfield_attendance_2
            "고급 작전 기록" -> R.drawable.endfield_attendance_3
            "무기 점검 장치" -> R.drawable.endfield_attendance_4
            "프로토콜 프리즘" -> R.drawable.endfield_attendance_5
            "원형 복합체" -> R.drawable.endfield_attendance_6
            "탈로시안 화폐" -> R.drawable.endfield_attendance_7
            "오로베릴" -> R.drawable.endfield_attendance_8
            else -> null
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

        val granted = ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

        if (!granted) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            openAppNotificationSettings()
        }
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(packageName)
    }

    private fun openBatteryOptimizationRequest() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return

        if (isIgnoringBatteryOptimizations()) {
            openAppDetailsSettings()
            return
        }

        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (_: Exception) {
            try {
                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                startActivity(intent)
            } catch (_: Exception) {
                openAppDetailsSettings()
            }
        }
    }

    private fun openAppNotificationSettings() {
        try {
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            }
            startActivity(intent)
        } catch (_: Exception) {
            openAppDetailsSettings()
        }
    }

    private fun openAppDetailsSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
        }
        startActivity(intent)
    }

    private fun showManualNotification(
        title: String,
        content: String,
        nickname: String,
        roleId: String
    ) {
        createMainNotificationChannel()

        val detailText = when {
            title.contains("이미 처리됨") -> "이미 오늘 출석이 완료되어 있습니다"
            title.startsWith("출석 완료 ·") -> "$content 획득"
            else -> if (content.isNotBlank()) content else "출석 처리 중 오류가 발생했습니다"
        }

        val bigText = """
            관리자  $nickname
            UID     $roleId
            
            $detailText
        """.trimIndent()

        val notification = NotificationCompat.Builder(this, MAIN_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText("엔드필드 출석 결과")
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        val manager = NotificationManagerCompat.from(this)

        if (!manager.areNotificationsEnabled()) {
            openAppNotificationSettings()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

            if (!granted) {
                requestNotificationPermissionIfNeeded()
                return
            }
        }

        manager.notify(MAIN_NOTIFICATION_ID, notification)
    }

    private fun createMainNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                MAIN_CHANNEL_ID,
                "출석 알림",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "출석 결과 알림"
            }
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val MAIN_CHANNEL_ID = "endfield_main_channel"
        private const val MAIN_NOTIFICATION_ID = 2001
        private const val KEY_STARTUP_GUIDE_SHOWN = "startup_guide_shown"
    }
}