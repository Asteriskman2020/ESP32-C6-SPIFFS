package com.kmutt.weatherlogger

data class SensorRecord(
    val index: Int,
    val timestamp: Long,   // Unix seconds
    val tempAht: Float,
    val humidity: Float,
    val pressure: Float,
    val tempBmp: Float
) {
    fun formatTime(): String = java.text.SimpleDateFormat("MM/dd HH:mm:ss", java.util.Locale.getDefault())
        .format(java.util.Date(timestamp * 1000L))
}
