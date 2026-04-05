package com.danggai.endfield.assistant.manager

import android.content.Context
import android.util.Log
import com.danggai.endfield.assistant.api.AttendanceResult
import com.danggai.endfield.assistant.api.AttendanceService
import java.util.concurrent.Executors

class AttendanceManager(
    private val context: Context
) {
    private val service = AttendanceService()
    private val executor = Executors.newSingleThreadExecutor()

    fun runAttendance(
        accountToken: String,
        roleId: String,
        serverId: String
    ): AttendanceResult {
        Log.d("AttendanceDebug", "Manager 실행")
        return service.runAttendance(
            accountToken = accountToken,
            uid = roleId,
            serverId = serverId
        )
    }

    fun runAttendanceAsync(
        accountToken: String,
        roleId: String,
        serverId: String,
        callback: (AttendanceResult) -> Unit
    ) {
        executor.execute {
            val result = runAttendance(
                accountToken = accountToken,
                roleId = roleId,
                serverId = serverId
            )
            callback(result)
        }
    }
}