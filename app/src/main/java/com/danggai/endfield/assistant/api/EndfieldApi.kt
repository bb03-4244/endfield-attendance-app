package com.danggai.endfield.assistant.api

import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

object EndfieldApi {

    private val client = OkHttpClient()

    fun getCred(token: String): Pair<String, String>? {

        try {

            // STEP 1 grant 요청
            val body = JSONObject().apply {
                put("token", token)
                put("appCode", "6eb76d4e13aa36e6")
                put("type", 0)
            }

            val req = Request.Builder()
                .url("https://as.gryphline.com/user/oauth2/v2/grant")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val res = client.newCall(req).execute()

            val text = res.body!!.string()

            Log.d("AttendanceDebug", "grant 응답: $text")

            val json = JSONObject(text)

            val data = json.optJSONObject("data")

            if (data == null) {

                Log.e("AttendanceDebug", "grant 실패")

                return null
            }

            val code = data.getString("code")

            // STEP 2 cred 생성
            val body2 = JSONObject().apply {
                put("code", code)
                put("kind", 1)
            }

            val req2 = Request.Builder()
                .url("https://zonai.skport.com/web/v1/user/auth/generate_cred_by_code")
                .post(body2.toString().toRequestBody("application/json".toMediaType()))
                .addHeader("platform", "3")
                .build()

            val res2 = client.newCall(req2).execute()

            val text2 = res2.body!!.string()

            Log.d("AttendanceDebug", "cred 응답: $text2")

            val json2 = JSONObject(text2)

            val data2 = json2.optJSONObject("data")

            if (data2 == null) {

                Log.e("AttendanceDebug", "cred 생성 실패")

                return null
            }

            val cred = data2.getString("cred")
            val token2 = data2.getString("token")

            return Pair(cred, token2)

        } catch (e: Exception) {

            Log.e("AttendanceDebug", "getCred 에러", e)

            return null
        }
    }
}
