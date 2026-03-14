package com.kmutt.datalogger

data class SensorRecord(
    val index: Int,
    val timestamp: Long,
    val dateTime: String,
    val tempAht: Float,
    val humidity: Float,
    val pressure: Float,
    val tempBmp: Float
) {
    companion object {
        // Parse from CSV line: "1,1705312200,14-01-2024 10:30:00,25.10,60.50,1013.20,26.30"
        fun fromCsvLine(line: String): SensorRecord? {
            return try {
                val parts = line.split(",")
                if (parts.size < 7) return null
                SensorRecord(
                    index     = parts[0].trim().toInt(),
                    timestamp = parts[1].trim().toLong(),
                    dateTime  = parts[2].trim(),
                    tempAht   = parts[3].trim().toFloat(),
                    humidity  = parts[4].trim().toFloat(),
                    pressure  = parts[5].trim().toFloat(),
                    tempBmp   = parts[6].trim().toFloat()
                )
            } catch (e: Exception) {
                null
            }
        }
    }

    fun formatTime(): String = dateTime
}
