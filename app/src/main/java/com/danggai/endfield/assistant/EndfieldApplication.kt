package com.danggai.endfield.assistant

import android.app.Application
import android.util.Log
import com.danggai.endfield.assistant.worker.AttendanceScheduler

class EndfieldApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        Log.d("AttendanceDebug", "앱 시작 - WorkManager 자동출석 등록 확인")
        AttendanceScheduler.ensureScheduled(this)
    }
}