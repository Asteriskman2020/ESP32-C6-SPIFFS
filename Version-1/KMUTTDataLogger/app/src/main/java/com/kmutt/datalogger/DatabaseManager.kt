package com.kmutt.datalogger

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
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
    fun getServerPort(): String    = getPrefs().getString("db_port", "3000") ?: "3000"
    fun getUser(): String          = getPrefs().getString("db_user", "") ?: ""
    fun getPass(): String          = getPrefs().getString("db_pass", "") ?: ""

    private fun buildBaseAuth(): String {
        val credentials = "${getUser()}:${getPass()}"
        return "Basic " + Base64.encodeToString(credentials.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }

    fun uploadCsv(filename: String, csvContent: String, callback: UploadCallback) {
        val addr = getServerAddress()
        val port = getServerPort()
        if (addr.isEmpty()) {
            mainHandler.post { callback.onError("No server configured") }
            return
        }

        val url = "http://$addr:$port/api/upload"
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                filename,
                csvContent.toRequestBody("text/csv".toMediaTypeOrNull())
            )
            .build()

        val request = Request.Builder()
            .url(url)
            .header("Authorization", buildBaseAuth())
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
                    // Count records in CSV (lines - 1 for header)
                    val count = csvContent.lines().count { it.isNotBlank() } - 1
                    mainHandler.post { callback.onSuccess(count.coerceAtLeast(0)) }
                } else {
                    mainHandler.post { callback.onError("Server error $code: $bodyStr") }
                }
            }
        })
    }

    fun testConnection(callback: TestCallback) {
        val addr = getServerAddress()
        val port = getServerPort()
        if (addr.isEmpty()) {
            mainHandler.post { callback.onError("No server configured") }
            return
        }

        val url = "http://$addr:$port/api/status"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", buildBaseAuth())
            .get()
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainHandler.post { callback.onError("Connection failed: ${e.message}") }
            }
            override fun onResponse(call: Call, response: Response) {
                val code = response.code
                response.close()
                if (code in 200..299) {
                    mainHandler.post { callback.onSuccess() }
                } else {
                    mainHandler.post { callback.onError("Server returned $code") }
                }
            }
        })
    }
}
