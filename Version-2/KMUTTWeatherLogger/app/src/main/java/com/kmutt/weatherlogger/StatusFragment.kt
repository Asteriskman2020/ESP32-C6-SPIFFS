package com.kmutt.weatherlogger

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import java.net.URL

class StatusFragment : Fragment() {

    private lateinit var bleManager: BleManager

    // Device status
    private lateinit var tvBleStatus: TextView
    private lateinit var tvNtpStatus: TextView
    private lateinit var tvCurFile: TextView
    private lateinit var tvRecords: TextView

    // Storage status — file slots
    private val fileSlotIcons = arrayOfNulls<TextView>(5)
    private val fileSlotNames = arrayOfNulls<TextView>(5)
    private val fileSlotCounts = arrayOfNulls<TextView>(5)

    // DB status
    private lateinit var tvDbServer: TextView
    private lateinit var tvDbStatus: TextView
    private lateinit var btnTestConn: Button

    private var isConnected = false
    private val lastFileList = mutableListOf<FileInfo>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_status, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        bleManager = (requireActivity() as MainActivity).bleManager

        tvBleStatus = view.findViewById(R.id.tvBleStatusValue)
        tvNtpStatus = view.findViewById(R.id.tvNtpStatusValue)
        tvCurFile   = view.findViewById(R.id.tvCurFileValue)
        tvRecords   = view.findViewById(R.id.tvRecordsValue)

        for (i in 0 until 5) {
            val suffix = (i + 1).toString()
            fileSlotIcons[i]  = view.findViewById(
                resources.getIdentifier("tvSlotIcon$suffix", "id", requireContext().packageName))
            fileSlotNames[i]  = view.findViewById(
                resources.getIdentifier("tvSlotName$suffix", "id", requireContext().packageName))
            fileSlotCounts[i] = view.findViewById(
                resources.getIdentifier("tvSlotCount$suffix", "id", requireContext().packageName))
        }

        tvDbServer  = view.findViewById(R.id.tvDbServer)
        tvDbStatus  = view.findViewById(R.id.tvDbStatusValue)
        btnTestConn = view.findViewById(R.id.btnTestDbConn)

        btnTestConn.setOnClickListener { testDbConnection() }

        updateFromBleCallback()
        refreshDbSettings()
    }

    override fun onResume() {
        super.onResume()
        // Attach a simple status callback for this tab
        bleManager.callback = object : BleManager.BleCallback {
            override fun onConnected(name: String) {
                requireActivity().runOnUiThread { onBleConnected(name) }
            }
            override fun onDisconnected() {
                requireActivity().runOnUiThread { onBleDisconnected() }
            }
            override fun onScanResult(device: android.bluetooth.BluetoothDevice, rssi: Int) {}
            override fun onScanStopped() {}
            override fun onFileList(json: String) {
                requireActivity().runOnUiThread { parseAndUpdateSlots(json) }
            }
            override fun onFileContent(filename: String, content: String) {}
            override fun onStatusUpdate(status: String) {
                requireActivity().runOnUiThread { handleStatus(status) }
            }
            override fun onLog(msg: String) {}
        }
        refreshDbSettings()
    }

    private fun onBleConnected(name: String) {
        isConnected = true
        tvBleStatus.text = "Connected: $name"
        tvBleStatus.setTextColor(android.graphics.Color.parseColor("#388E3C"))
        bleManager.requestFileList()
    }

    private fun onBleDisconnected() {
        isConnected = false
        tvBleStatus.text = "Disconnected"
        tvBleStatus.setTextColor(android.graphics.Color.parseColor("#D32F2F"))
        tvNtpStatus.text = "-"
        tvCurFile.text   = "-"
        tvRecords.text   = "-"
    }

    private fun handleStatus(status: String) {
        when {
            status.startsWith("CONNECTED") -> {
                tvNtpStatus.text = "Checking..."
            }
            status.contains("NTP_OK") -> {
                tvNtpStatus.text = "Synced"
                tvNtpStatus.setTextColor(android.graphics.Color.parseColor("#388E3C"))
            }
        }
    }

    private fun parseAndUpdateSlots(json: String) {
        lastFileList.clear()
        try {
            val arr = org.json.JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                lastFileList.add(FileInfo(
                    name    = obj.optString("name", ""),
                    size    = obj.optInt("size", 0),
                    records = obj.optInt("records", 0)
                ))
            }
        } catch (_: Exception) {}

        // Update file slots
        for (i in 0 until 5) {
            val slotName = "data${i + 1}.json"
            val fi = lastFileList.find { it.name == slotName }
            fileSlotIcons[i]?.text  = if (fi != null) "●" else "○"
            fileSlotIcons[i]?.setTextColor(
                android.graphics.Color.parseColor(if (fi != null) "#388E3C" else "#9E9E9E")
            )
            fileSlotNames[i]?.text  = slotName
            fileSlotCounts[i]?.text = if (fi != null) "${fi.records}/20" else "Empty"
        }

        // Update current file info from largest populated slot
        val curFile = lastFileList.lastOrNull()
        tvCurFile.text = curFile?.name ?: "-"
        tvRecords.text = curFile?.records?.toString() ?: "-"
    }

    private fun updateFromBleCallback() {
        tvBleStatus.text = "Disconnected"
        tvBleStatus.setTextColor(android.graphics.Color.parseColor("#D32F2F"))
        tvNtpStatus.text = "-"
        tvCurFile.text   = "-"
        tvRecords.text   = "-"
        for (i in 0 until 5) {
            fileSlotIcons[i]?.text  = "○"
            fileSlotIcons[i]?.setTextColor(android.graphics.Color.parseColor("#9E9E9E"))
            fileSlotNames[i]?.text  = "data${i + 1}.json"
            fileSlotCounts[i]?.text = "Empty"
        }
    }

    private fun refreshDbSettings() {
        val prefs = requireContext().getSharedPreferences("db_prefs", Context.MODE_PRIVATE)
        val addr = prefs.getString("db_address", "") ?: ""
        val port = prefs.getString("db_port", "3306") ?: "3306"
        tvDbServer.text = if (addr.isNotEmpty()) "$addr:$port" else "Not configured"
        tvDbStatus.text = if (addr.isNotEmpty()) "Configured" else "Not configured"
        tvDbStatus.setTextColor(
            if (addr.isNotEmpty()) android.graphics.Color.parseColor("#F57C00")
            else android.graphics.Color.parseColor("#9E9E9E")
        )
    }

    private fun testDbConnection() {
        val prefs = requireContext().getSharedPreferences("db_prefs", Context.MODE_PRIVATE)
        val addr = prefs.getString("db_address", "") ?: ""
        val port = prefs.getString("db_port", "3306") ?: "3306"
        if (addr.isEmpty()) {
            Toast.makeText(requireContext(), "No server configured", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(requireContext(), "Testing $addr:$port...", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val url = URL("http://$addr:$port")
                val conn = url.openConnection()
                conn.connectTimeout = 3000
                conn.readTimeout    = 3000
                conn.connect()
                requireActivity().runOnUiThread {
                    Toast.makeText(requireContext(), "Connection OK!", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                requireActivity().runOnUiThread {
                    Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }
}
