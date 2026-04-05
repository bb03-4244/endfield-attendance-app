package com.danggai.endfield.assistant.api

data class AttendanceResult(
    val success: Boolean,
    val alreadyDone: Boolean = false,
    val message: String,
    val rewardName: String? = null,
    val rewardCount: Int? = null,
    val rewardIcon: String? = null
)