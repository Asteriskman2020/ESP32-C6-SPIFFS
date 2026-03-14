package com.kmutt.datalogger

import android.content.Context
import android.os.Handler
import android.os.Looper
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class DatabaseManager(private val context: Context) {

    interface UploadCallback {
        fun onSuccess(count: Int)
        fun onError(msg: String)
    }

    interface TestCallback {
        fun onSuccess()
        fun onError(msg: String)
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val mainHandler = Handler(Looper.getMainLooper())

    private fun getPrefs() = context.getSharedPreferences("db_prefs", Context.MODE_PRIVATE)

    fun getServerAddress(): String = getPrefs().getString("db_address", "") ?: ""
    fun getServerPort(): String    = getPrefs().getString("db_port", "8086") ?: "8086"
    fun getOrg(): String           = getPrefs().getString("db_user", "") ?: ""
    fun getToken(): String         = getPrefs().getString("db_pass", "") ?: ""
    fun getBucket(): String        = getPrefs().getString("db_bucket", "") ?: ""

    // Convert parsed CSV records to InfluxDB line protocol
    // Format: measurement field1=val,field2=val,... unix_timestamp_seconds
    private fun csvToLineProtocol(csvContent: String): Pair<String, Int> {
        val sb = StringBuilder()
        var count = 0
        val lines = csvContent.lines()
        for (i in lines.indices) {
            val line = lines[i].trim()
            if (i == 0 || line.isEmpty()) continue  // skip header
            val rec = SensorRecord.fromCsvLine(line) ?: continue
            sb.append("weather ")
            sb.append("temp_aht=").append(rec.tempAht).append(",")
            sb.append("humidity=").append(rec.humidity).append(",")
            sb.append("pressure=").append(rec.pressure).append(",")
            sb.append("temp_bmp=").append(rec.tempBmp)
            sb.append(" ").append(rec.timestamp).append("\n")
            count++
        }
        return Pair(sb.toString(), count)
    }

    fun uploadCsv(filename: String, csvContent: String, callback: UploadCallback) {
        val addr   = getServerAddress()
        val port   = getServerPort()
        val org    = getOrg()
        val bucket = getBucket()
        val token  = getToken()

        if (addr.isEmpty()) {
            mainHandler.post { callback.onError("No server address configured") }
            return
        }
        if (bucket.isEmpty()) {
            mainHandler.post { callback.onError("No bucket configured") }
            return
        }

        val (lineProtocol, count) = csvToLineProtocol(csvContent)
        if (lineProtocol.isEmpty()) {
            mainHandler.post { callback.onError("No valid records to upload") }
            return
        }

        val url = "http://$addr:$port/api/v2/write?org=$org&bucket=$bucket&precision=s"
        val body = lineProtocol.toRequestBody("text/plain; charset=utf-8".toMediaTypeOrNull())
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Token $token")
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainHandler.post { callback.onError("Upload failed: ${e.message}") }
            }
            override fun onResponse(call: Call, response: Response) {
                val code = response.code
                val bodyStr = response.body?.string() ?: ""
                response.close()
                if (code in 200..299) {
                    mainHandler.post { callback.onSuccess(count) }
                } else {
                    mainHandler.post { callback.onError("InfluxDB error $code: $bodyStr") }
                }
            }
        })
    }

    fun testConnection(callback: TestCallback) {
        val addr  = getServerAddress()
        val port  = getServerPort()
        val token = getToken()

        if (addr.isEmpty()) {
            mainHandler.post { callback.onError("No server address configured") }
            return
        }

        val url = "http://$addr:$port/health"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Token $token")
            .get()
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainHandler.post { callback.onError("Connection failed: ${e.message}") }
            }
            override fun onResponse(call: Call, response: Response) {
                val code    = response.code
                val bodyStr = response.body?.string() ?: ""
                response.close()
                if (code in 200..299 && bodyStr.contains("\"pass\"")) {
                    mainHandler.post { callback.onSuccess() }
                } else if (code in 200..299) {
                    mainHandler.post { callback.onSuccess() }
                } else {
                    mainHandler.post { callback.onError("InfluxDB returned $code") }
                }
            }
        })
    }
}
