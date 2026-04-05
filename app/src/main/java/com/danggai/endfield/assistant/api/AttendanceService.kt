package com.danggai.endfield.assistant.api

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class AttendanceService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val baseUrl = "https://zonai.skport.com/web/v1"

    fun runAttendance(
        accountToken: String,
        uid: String,
        serverId: String
    ): AttendanceResult {

        Log.d("AttendanceDebug", "runAttendance 시작")

        return try {
            // 1) grant
            val grantBody = """
                {
                    "token":"$accountToken",
                    "appCode":"6eb76d4e13aa36e6",
                    "type":0
                }
            """.trimIndent()

            val grantRequest = Request.Builder()
                .url("https://as.gryphline.com/user/oauth2/v2/grant")
                .post(grantBody.toRequestBody("application/json".toMediaType()))
                .build()

            val grantRes = client.newCall(grantRequest).execute()
            val grantText = grantRes.body?.string() ?: "{}"
            val grantJson = JSONObject(grantText)

            if (grantJson.optInt("status", -1) != 0) {
                return AttendanceResult(
                    success = false,
                    message = "grant 실패"
                )
            }

            val code = grantJson.getJSONObject("data").getString("code")

            // 2) generate cred
            val credBody = """
                {
                    "code":"$code",
                    "kind":1
                }
            """.trimIndent()

            val credRequest = Request.Builder()
                .url("$baseUrl/user/auth/generate_cred_by_code")
                .addHeader("platform", "3")
                .post(credBody.toRequestBody("application/json".toMediaType()))
                .build()

            val credRes = client.newCall(credRequest).execute()
            val credText = credRes.body?.string() ?: "{}"
            val credJson = JSONObject(credText)

            if (credJson.optInt("code", -1) != 0) {
                return AttendanceResult(
                    success = false,
                    message = "cred 실패"
                )
            }

            val credData = credJson.getJSONObject("data")
            val cred = credData.getString("cred")
            val token = credData.getString("token")

            val role = "3_${uid}_${serverId}"

            // 3) CHECK
            val checkTimestamp = (System.currentTimeMillis() / 1000).toString()
            val checkSign = generateGetSign(checkTimestamp, cred)

            val checkRequest = Request.Builder()
                .url("$baseUrl/game/endfield/attendance")
                .get()
                .addHeader("cred", cred)
                .addHeader("sk-game-role", role)
                .addHeader("platform", "3")
                .addHeader("sk-language", "ko")
                .addHeader("timestamp", checkTimestamp)
                .addHeader("vname", "1.0.0")
                .addHeader(
                    "User-Agent",
                    "Skport/0.7.0 (com.gryphline.skport; build:700089; Android 33;) OkHttp/5.1.0"
                )
                .addHeader("sign", checkSign)
                .build()

            val checkRes = client.newCall(checkRequest).execute()
            val checkText = checkRes.body?.string() ?: "{}"
            val checkJson = JSONObject(checkText)

            if (checkJson.optInt("code", -1) != 0) {
                return AttendanceResult(
                    success = false,
                    message = "출석 조회 실패"
                )
            }

            val checkData = checkJson.optJSONObject("data")
                ?: return AttendanceResult(false, message = "출석 데이터 없음")

            val calendar = checkData.optJSONArray("calendar")
                ?: return AttendanceResult(false, message = "calendar 없음")

            val resourceInfoMap = checkData.optJSONObject("resourceInfoMap")

            val currentTs = checkData.optString("currentTs")
            val todayIndex = getTodayIndexFromTimestamp(currentTs)

            var todayAwardId: String? = null
            var rewardName: String? = null
            var rewardCount: Int? = null
            var rewardIcon: String? = null

            // 오늘 날짜 기준 보상 먼저 찾기
            if (todayIndex in 0 until calendar.length()) {
                val todayItem = calendar.optJSONObject(todayIndex)
                todayAwardId = todayItem?.optString("awardId")

                val rewardObj = todayAwardId?.let { resourceInfoMap?.optJSONObject(it) }
                rewardName = rewardObj?.optString("name")
                rewardCount = rewardObj?.optInt("count")
                rewardIcon = rewardObj?.optString("icon")
            }

            // 출석 가능한 오늘 항목도 다시 확인
            var claimAwardId: String? = null
            for (i in 0 until calendar.length()) {
                val item = calendar.optJSONObject(i) ?: continue
                val available = item.optBoolean("available", false)
                val done = item.optBoolean("done", false)

                if (available && !done) {
                    claimAwardId = item.optString("awardId")

                    val rewardObj = claimAwardId?.let { resourceInfoMap?.optJSONObject(it) }
                    rewardName = rewardObj?.optString("name")
                    rewardCount = rewardObj?.optInt("count")
                    rewardIcon = rewardObj?.optString("icon")
                    break
                }
            }

            if (claimAwardId == null) {
                Log.d("AttendanceDebug", "이미 오늘 출석 완료")

                return AttendanceResult(
                    success = true,
                    alreadyDone = true,
                    message = "이미 오늘 출석 완료",
                    rewardName = rewardName,
                    rewardCount = rewardCount,
                    rewardIcon = rewardIcon
                )
            }

            // 4) CLAIM
            val claimTimestamp = (System.currentTimeMillis() / 1000).toString()
            val claimSign = generatePostSign(
                path = "/web/v1/game/endfield/attendance",
                timestamp = claimTimestamp,
                token = token
            )

            val claimRequest = Request.Builder()
                .url("$baseUrl/game/endfield/attendance")
                .post(ByteArray(0).toRequestBody("application/json".toMediaType()))
                .addHeader("accept", "*/*")
                .addHeader("accept-language", "ko,ja;q=0.9,en;q=0.8")
                .addHeader("content-type", "application/json")
                .addHeader("cred", cred)
                .addHeader("sk-game-role", role)
                .addHeader("platform", "3")
                .addHeader("sk-language", "ko")
                .addHeader("timestamp", claimTimestamp)
                .addHeader("vname", "1.0.0")
                .addHeader("origin", "https://game.skport.com")
                .addHeader("referer", "https://game.skport.com/")
                .addHeader(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36"
                )
                .addHeader("sign", claimSign)
                .build()

            val claimRes = client.newCall(claimRequest).execute()
            val claimText = claimRes.body?.string() ?: "{}"
            val claimJson = JSONObject(claimText)

            if (claimJson.optInt("code", -1) != 0) {
                return AttendanceResult(
                    success = false,
                    message = "출석 수령 실패: ${claimJson.optString("message")}",
                    rewardName = rewardName,
                    rewardCount = rewardCount,
                    rewardIcon = rewardIcon
                )
            }

            // 5) 최종 확인
            val finalTimestamp = (System.currentTimeMillis() / 1000).toString()
            val finalSign = generateGetSign(finalTimestamp, cred)

            val finalRequest = Request.Builder()
                .url("$baseUrl/game/endfield/attendance")
                .get()
                .addHeader("cred", cred)
                .addHeader("sk-game-role", role)
                .addHeader("platform", "3")
                .addHeader("sk-language", "ko")
                .addHeader("timestamp", finalTimestamp)
                .addHeader("vname", "1.0.0")
                .addHeader(
                    "User-Agent",
                    "Skport/0.7.0 (com.gryphline.skport; build:700089; Android 33;) OkHttp/5.1.0"
                )
                .addHeader("sign", finalSign)
                .build()

            client.newCall(finalRequest).execute().use { }

            Log.d("AttendanceDebug", "출석 성공: ${rewardName ?: "보상 지급 완료"}")

            AttendanceResult(
                success = true,
                alreadyDone = false,
                message = "출석 성공",
                rewardName = rewardName,
                rewardCount = rewardCount,
                rewardIcon = rewardIcon
            )

        } catch (e: Exception) {
            Log.e("AttendanceDebug", "출석 오류", e)

            AttendanceResult(
                success = false,
                message = e.message ?: "알 수 없는 오류"
            )
        }
    }

    private fun getTodayIndexFromTimestamp(currentTs: String?): Int {
        return try {
            val seconds = currentTs?.toLongOrNull() ?: return -1
            val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Seoul")).apply {
                timeInMillis = seconds * 1000L
            }
            cal.get(Calendar.DAY_OF_MONTH) - 1
        } catch (_: Exception) {
            -1
        }
    }

    private fun generateGetSign(timestamp: String, cred: String): String {
        return md5("timestamp=$timestamp&cred=$cred")
    }

    private fun generatePostSign(
        path: String,
        timestamp: String,
        token: String
    ): String {
        val json = """{"platform":"3","timestamp":"$timestamp","dId":"","vName":"1.0.0"}"""
        val raw = path + timestamp + json
        val hmacHex = hmacSha256Hex(raw, token)
        return md5(hmacHex)
    }

    private fun hmacSha256Hex(data: String, key: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        val secretKey = SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256")
        mac.init(secretKey)

        val bytes = mac.doFinal(data.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}