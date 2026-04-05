package com.danggai.endfield.assistant.api

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object CryptoUtil {

    fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val bytes = md.digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun hmacSha256(data: String, key: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        val secret = SecretKeySpec(key.toByteArray(), "HmacSHA256")
        mac.init(secret)
        return mac.doFinal(data.toByteArray())
    }

    fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * 출석 조회용 sign
     */
    fun sign(timestamp: String, cred: String): String {
        return md5("timestamp=$timestamp&cred=$cred")
    }

    /**
     * 출석 수령용 sign (GAS 완전 동일)
     */
    fun sign2(path: String, timestamp: String, token: String): String {

        val json =
            """{"platform":"3","timestamp":"$timestamp","dId":"","vName":"1.0.0"}"""

        val payload = path + timestamp + json

        val hmac = hmacSha256(payload, token)

        val hex = bytesToHex(hmac)

        return md5(hex)
    }
}
