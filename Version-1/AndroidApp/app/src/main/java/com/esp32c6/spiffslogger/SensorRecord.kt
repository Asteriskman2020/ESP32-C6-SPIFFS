package com.esp32c6.spiffslogger

data class SensorRecord(
    val index: Int,
    val tempAht: Float,
    val humidity: Float,
    val pressure: Float,
    val tempBmp: Float
)
