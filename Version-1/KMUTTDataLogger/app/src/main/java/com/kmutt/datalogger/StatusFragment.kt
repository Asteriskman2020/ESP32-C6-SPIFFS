package com.kmutt.datalogger

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment

class StatusFragment : Fragment() {

    private lateinit var bleManager: BleManager
    private lateinit var databaseManager: DatabaseManager

    private lateinit var tvBleStatus:  TextView
    private lateinit var tvCurFile:    TextView
    private lateinit var tvRecords:    TextView
    private lateinit var tvNtpStatus:  TextView

    private val slotIcons  = arrayOfNulls<TextView>(5)
    private val slotNames  = arrayOfNulls<TextView>(5)
    private val slotCounts = arrayOfNulls<TextView>(5)

    private lateinit var tvDbStatus: TextView
    private lateinit var tvDbServer: TextView
    private lateinit var btnTestDb:  Button

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_status, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        bleManager      = (requireActivity() as MainActivity).bleManager
        databaseManager = (requireActivity() as MainActivity).databaseManager

        tvBleStatus = view.findViewById(R.id.tvBleStatusValue)
        tvCurFile   = view.findViewById(R.id.tvCurFileValue)
        tvRecords   = view.findViewById(R.id.tvRecordsValue)
        tvNtpStatus = view.findViewById(R.id.tvNtpStatusValue)

        for (i in 0 until 5) {
            val suffix = (i + 1).toString()
            slotIcons[i]  = view.findViewById(
                resources.getIdentifier("tvSlotIcon$suffix", "id", requireContext().packageName))
            slotNames[i]  = view.findViewById(
                resources.getIdentifier("tvSlotName$suffix", "id", requireContext().packageName))
            slotCounts[i] = view.findViewById(
                resources.getIdentifier("tvSlotCount$suffix", "id", requireContext().packageName))
        }

        tvDbStatus = view.findViewById(R.id.tvDbStatusValue)
        tvDbServer = view.findViewById(R.id.tvDbServer)
        btnTestDb  = view.findViewById(R.id.btnTestDbConn)

        btnTestDb.setOnClickListener { testDbConnection() }

        resetUi()
        refreshDbSettings()
    }

    override fun onResume() {
        super.onResume()
        // Attach lightweight callback that doesn't steal FilesFragment connection
        val prevCallback = bleManager.callback
        bleManager.callback = object : BleManager.BleCallback {
            override fun onConnected(name: String) {
                requireActivity().runOnUiThread {
                    tvBleStatus.text = "Connected: $name"
                    tvBleStatus.setTextColor(android.graphics.Color.parseColor("#43A047"))
                    bleManager.requestFileList()
                }
            }
            override fun onDisconnected() {
                requireActivity().runOnUiThread { resetUi() }
            }
            override fun onScanResult(device: android.bluetooth.BluetoothDevice, rssi: Int) {}
            override fun onScanStopped() {}
            override fun onFileList(files: List<FileInfo>) {
                requireActivity().runOnUiThread { updateSlots(files) }
            }
            override fun onFileContent(filename: String, csvContent: String) {}
            override fun onFileCleared(filename: String) {}
            override fun onLog(msg: String) {}
        }
        refreshDbSettings()
    }

    private fun resetUi() {
        tvBleStatus.text = "Disconnected"
        tvBleStatus.setTextColor(android.graphics.Color.parseColor("#E53935"))
        tvCurFile.text   = "-"
        tvRecords.text   = "-"
        tvNtpStatus.text = "-"
        for (i in 0 until 5) {
            slotIcons[i]?.text  = "○"
            slotIcons[i]?.setTextColor(android.graphics.Color.parseColor("#9E9E9E"))
            slotNames[i]?.text  = "Slot ${i + 1}"
            slotCounts[i]?.text = "Empty"
        }
    }

    private fun updateSlots(files: List<FileInfo>) {
        for (i in 0 until 5) {
            val fi = if (i < files.size) files[i] else null
            slotIcons[i]?.text  = if (fi != null) "●" else "○"
            slotIcons[i]?.setTextColor(
                android.graphics.Color.parseColor(if (fi != null) "#43A047" else "#9E9E9E")
            )
            slotNames[i]?.text  = fi?.name ?: "Slot ${i + 1}"
            slotCounts[i]?.text = if (fi != null) "${fi.records}/20 records" else "Empty"
        }
        val last = files.lastOrNull()
        tvCurFile.text = last?.name ?: "-"
        tvRecords.text = last?.records?.toString() ?: "-"
        tvNtpStatus.text = if (files.isNotEmpty()) "Synced" else "-"
    }

    private fun refreshDbSettings() {
        val addr = databaseManager.getServerAddress()
        val port = databaseManager.getServerPort()
        tvDbServer.text = if (addr.isNotEmpty()) "$addr:$port" else "Not configured"
        tvDbStatus.text = if (addr.isNotEmpty()) "Configured" else "Not configured"
        tvDbStatus.setTextColor(
            if (addr.isNotEmpty()) android.graphics.Color.parseColor("#FB8C00")
            else android.graphics.Color.parseColor("#9E9E9E")
        )
    }

    private fun testDbConnection() {
        refreshDbSettings()
        val addr = databaseManager.getServerAddress()
        if (addr.isEmpty()) {
            Toast.makeText(requireContext(), "No server configured", Toast.LENGTH_SHORT).show()
            return
        }
        tvDbStatus.text = "Testing..."
        databaseManager.testConnection(object : DatabaseManager.TestCallback {
            override fun onSuccess() {
                tvDbStatus.text = "Connected OK"
                tvDbStatus.setTextColor(android.graphics.Color.parseColor("#43A047"))
            }
            override fun onError(msg: String) {
                tvDbStatus.text = "Error: $msg"
                tvDbStatus.setTextColor(android.graphics.Color.parseColor("#E53935"))
            }
        })
    }
}
