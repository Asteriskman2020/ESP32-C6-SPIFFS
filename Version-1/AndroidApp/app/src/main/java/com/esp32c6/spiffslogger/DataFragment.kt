package com.esp32c6.spiffslogger

import android.app.AlertDialog
import android.bluetooth.BluetoothDevice
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.cardview.widget.CardView
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONArray
import org.json.JSONException

class DataFragment : Fragment(), BleManager.Callback {

    private lateinit var bleManager: BleManager

    // Views
    private lateinit var dotView:        View
    private lateinit var tvDeviceName:   TextView
    private lateinit var tvStatus:       TextView
    private lateinit var btnScan:        Button
    private lateinit var btnDisconnect:  Button
    private lateinit var btnDownload:    Button
    private lateinit var btnClear:       Button
    private lateinit var recyclerView:   RecyclerView
    private lateinit var tvLog:          TextView
    private lateinit var logScroll:      ScrollView

    private val adapter = SensorAdapter()
    private val logBuffer = StringBuilder()

    // Scan results
    private val scanResults = mutableListOf<BluetoothDevice>()
    private val scanNames   = mutableListOf<String>()

    // Colors
    private val orange   = Color.parseColor("#F57C00")
    private val darkOrng = Color.parseColor("#E65100")
    private val bgCard   = Color.parseColor("#FFE0B2")
    private val bgPage   = Color.parseColor("#FFF3E0")
    private val green    = Color.parseColor("#388E3C")
    private val grey     = Color.parseColor("#9E9E9E")
    private val red      = Color.parseColor("#D32F2F")

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        bleManager = (requireActivity() as MainActivity).bleManager
        bleManager.setCallback(this)

        val scroll = NestedScrollView(requireContext()).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(bgPage)
        }

        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }

        // ── CONNECTION card ──────────────────────────────────────────
        val connCard = makeCard()
        val connLayout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
        }
        val connRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        dotView = View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(16, 16).also { it.marginEnd = 8 }
            setBackgroundColor(grey)
        }
        tvDeviceName = TextView(requireContext()).apply {
            text = "Not connected"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#212121"))
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        connRow.addView(dotView)
        connRow.addView(tvDeviceName)
        tvStatus = TextView(requireContext()).apply {
            text = "Disconnected"
            textSize = 13f
            setTextColor(Color.parseColor("#757575"))
            setPadding(0, 4, 0, 8)
        }
        val btnRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        btnScan = makeButton("SCAN", orange).apply {
            setOnClickListener { startScan() }
        }
        btnDisconnect = makeButton("DISCONNECT", red).apply {
            layoutParams = (layoutParams as LinearLayout.LayoutParams).also { it.marginStart = 8 }
            setOnClickListener { doDisconnect() }
        }
        btnRow.addView(btnScan)
        btnRow.addView(btnDisconnect)
        connLayout.addView(makeCardTitle("CONNECTION"))
        connLayout.addView(connRow)
        connLayout.addView(tvStatus)
        connLayout.addView(btnRow)
        connCard.addView(connLayout)
        root.addView(connCard)

        // ── ACTIONS card ─────────────────────────────────────────────
        val actCard = makeCard()
        val actLayout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
        }
        val actBtnRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        btnDownload = makeButton("DOWNLOAD FILE", orange).apply {
            setOnClickListener { doDownload() }
        }
        btnClear = makeButton("CLEAR FILE", red).apply {
            layoutParams = (layoutParams as LinearLayout.LayoutParams).also { it.marginStart = 8 }
            setOnClickListener { doClear() }
        }
        actBtnRow.addView(btnDownload)
        actBtnRow.addView(btnClear)
        actLayout.addView(makeCardTitle("ACTIONS"))
        actLayout.addView(actBtnRow)
        actCard.addView(actLayout)
        root.addView(actCard)

        // ── DATA HISTORY card ────────────────────────────────────────
        val dataCard = makeCard()
        val dataLayout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
        }
        dataLayout.addView(makeCardTitle("DATA HISTORY"))
        dataLayout.addView(makeHeaderRow())
        recyclerView = RecyclerView(requireContext()).apply {
            layoutManager = LinearLayoutManager(requireContext())
            this.adapter = this@DataFragment.adapter
            isNestedScrollingEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        dataLayout.addView(recyclerView)
        dataCard.addView(dataLayout)
        root.addView(dataCard)

        // ── EVENT LOG card ────────────────────────────────────────────
        val logCard = makeCard()
        val logLayout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
        }
        logScroll = ScrollView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 400
            )
        }
        tvLog = TextView(requireContext()).apply {
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#212121"))
            setPadding(4, 4, 4, 4)
        }
        logScroll.addView(tvLog)
        logLayout.addView(makeCardTitle("EVENT LOG"))
        logLayout.addView(logScroll)
        logCard.addView(logLayout)
        root.addView(logCard)

        scroll.addView(root)
        return scroll
    }

    // ── Card helpers ──────────────────────────────────────────────────
    private fun makeCard(): CardView {
        return CardView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = 16 }
            setCardBackgroundColor(bgCard)
            radius = 12f
            cardElevation = 4f
            val p = 16
            setContentPadding(p, p, p, p)
        }
    }

    private fun makeCardTitle(text: String): TextView {
        return TextView(requireContext()).apply {
            this.text = text
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(orange)
            setPadding(0, 0, 0, 8)
        }
    }

    private fun makeButton(text: String, color: Int): Button {
        return Button(requireContext()).apply {
            this.text = text
            setBackgroundColor(color)
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(16, 8, 16, 8)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
    }

    private fun makeHeaderRow(): LinearLayout {
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(orange)
            setPadding(4, 6, 4, 6)
            fun hdr(t: String, weight: Float = 1f): TextView {
                return TextView(requireContext()).apply {
                    text = t
                    textSize = 10f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    if (weight == 0f)
                        layoutParams = LinearLayout.LayoutParams(dpToPx(28), LinearLayout.LayoutParams.WRAP_CONTENT)
                    else
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight)
                }
            }
            addView(hdr("#", 0f))
            addView(hdr("T°C(AHT)"))
            addView(hdr("Hum%"))
            addView(hdr("Pres hPa", 1.3f))
            addView(hdr("T°C(BMP)"))
        }
    }

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density + 0.5f).toInt()

    // ── Actions ───────────────────────────────────────────────────────
    private fun startScan() {
        scanResults.clear()
        scanNames.clear()
        setStatus("Scanning…", Color.parseColor("#F57C00"))
        addLog("Starting BLE scan…")
        bleManager.startScan(3000L)
    }

    private fun doDisconnect() {
        bleManager.disconnect()
        setStatus("Disconnected", grey)
        dotView.setBackgroundColor(grey)
        tvDeviceName.text = "Not connected"
        addLog("Disconnected by user")
    }

    private fun doDownload() {
        addLog("Sending READFILE command…")
        tvStatus.text = "Downloading…"
        bleManager.sendCommand("READFILE")
    }

    private fun doClear() {
        addLog("Sending CLEARFILE command…")
        bleManager.sendCommand("CLEARFILE")
        adapter.clear()
    }

    private fun setStatus(msg: String, color: Int) {
        activity?.runOnUiThread {
            tvStatus.text = msg
            tvStatus.setTextColor(color)
        }
    }

    private fun addLog(msg: String) {
        activity?.runOnUiThread {
            logBuffer.append(msg).append("\n")
            tvLog.text = logBuffer.toString()
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    // ── BleManager.Callback ───────────────────────────────────────────
    override fun onConnected(name: String) {
        activity?.runOnUiThread {
            dotView.setBackgroundColor(green)
            tvDeviceName.text = name
            setStatus("Connected", green)
            addLog("Connected to $name")
        }
    }

    override fun onDisconnected() {
        activity?.runOnUiThread {
            dotView.setBackgroundColor(grey)
            tvDeviceName.text = "Not connected"
            setStatus("Disconnected", grey)
            addLog("Disconnected")
        }
    }

    override fun onScanResult(device: BluetoothDevice, rssi: Int) {
        activity?.runOnUiThread {
            val name = try { device.name ?: device.address } catch (_: SecurityException) { device.address }
            if (scanResults.none { it.address == device.address }) {
                scanResults.add(device)
                scanNames.add("$name  (${device.address})  RSSI: $rssi")
                addLog("Found: $name  RSSI: $rssi")
            }
        }
    }

    override fun onScanStopped() {
        activity?.runOnUiThread {
            setStatus("Scan complete", grey)
            if (scanResults.isEmpty()) {
                addLog("No devices found")
                return@runOnUiThread
            }
            AlertDialog.Builder(requireContext())
                .setTitle("Select Device")
                .setItems(scanNames.toTypedArray()) { _, i ->
                    bleManager.connect(scanResults[i])
                    setStatus("Connecting…", orange)
                    addLog("Connecting to ${scanNames[i]}…")
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    override fun onFileChunk(chunk: String) {
        addLog("Chunk: ${chunk.take(40)}${if (chunk.length > 40) "…" else ""}")
    }

    override fun onFileComplete(fullContent: String) {
        addLog("File received (${fullContent.length} bytes)")
        activity?.runOnUiThread {
            tvStatus.text = "File downloaded"
            parseAndDisplay(fullContent)
        }
    }

    override fun onStatusUpdate(status: String) {
        addLog("Status: $status")
        activity?.runOnUiThread {
            tvStatus.text = status
            if (status == "CLEARED") adapter.clear()
        }
    }

    override fun onLog(msg: String) {
        addLog(msg)
    }

    // ── JSON parsing ──────────────────────────────────────────────────
    private fun parseAndDisplay(content: String) {
        if (content.isBlank() || content == "[]") {
            addLog("File is empty")
            adapter.clear()
            return
        }
        try {
            val arr = JSONArray(content)
            val records = mutableListOf<SensorRecord>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                records.add(SensorRecord(
                    index    = obj.optInt("i", i + 1),
                    tempAht  = obj.optDouble("t", 0.0).toFloat(),
                    humidity = obj.optDouble("h", 0.0).toFloat(),
                    pressure = obj.optDouble("p", 0.0).toFloat(),
                    tempBmp  = obj.optDouble("tb", 0.0).toFloat()
                ))
            }
            adapter.updateData(records)
            addLog("Parsed ${records.size} records")
        } catch (e: JSONException) {
            addLog("JSON parse error: ${e.message}")
        }
    }
}
