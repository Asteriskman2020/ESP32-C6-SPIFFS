package com.kmutt.weatherlogger

import android.bluetooth.BluetoothDevice
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONArray
import org.json.JSONObject

class FilesFragment : Fragment(), BleManager.BleCallback {

    private lateinit var bleManager: BleManager

    // Connection card views
    private lateinit var tvStatusDot: TextView
    private lateinit var tvDeviceName: TextView
    private lateinit var tvStatusText: TextView
    private lateinit var btnScan: Button
    private lateinit var btnDisconnect: Button

    // Files card views
    private lateinit var btnRefresh: Button
    private lateinit var rvFiles: RecyclerView

    // Data preview card views
    private lateinit var tvRecordCount: TextView
    private lateinit var rvRecords: RecyclerView

    private lateinit var fileAdapter: FileAdapter
    private lateinit var recordAdapter: RecordAdapter

    private val foundDevices = mutableListOf<Pair<BluetoothDevice, Int>>()
    private var isConnected = false
    private var isScanning = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_files, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        bleManager = (requireActivity() as MainActivity).bleManager
        bleManager.callback = this

        tvStatusDot  = view.findViewById(R.id.tvStatusDot)
        tvDeviceName = view.findViewById(R.id.tvDeviceName)
        tvStatusText = view.findViewById(R.id.tvStatusText)
        btnScan      = view.findViewById(R.id.btnScan)
        btnDisconnect = view.findViewById(R.id.btnDisconnect)
        btnRefresh   = view.findViewById(R.id.btnRefresh)
        rvFiles      = view.findViewById(R.id.rvFiles)
        tvRecordCount = view.findViewById(R.id.tvRecordCount)
        rvRecords    = view.findViewById(R.id.rvRecords)

        fileAdapter = FileAdapter(
            onDownload = { fi -> bleManager.requestFile(fi.name) },
            onClear    = { fi -> confirmClearFile(fi) }
        )
        rvFiles.layoutManager = LinearLayoutManager(requireContext())
        rvFiles.adapter = fileAdapter
        rvFiles.isNestedScrollingEnabled = false

        recordAdapter = RecordAdapter()
        rvRecords.layoutManager = LinearLayoutManager(requireContext())
        rvRecords.adapter = recordAdapter
        rvRecords.isNestedScrollingEnabled = false

        btnScan.setOnClickListener {
            if (!isScanning) startScanDialog()
        }
        btnDisconnect.setOnClickListener {
            bleManager.disconnect()
            updateConnectionUi(false, "", "Disconnected")
        }
        btnRefresh.setOnClickListener {
            if (isConnected) {
                bleManager.requestFileList()
                showToast("Refreshing file list...")
            } else {
                showToast("Not connected")
            }
        }

        updateConnectionUi(false, "", "Not connected")
    }

    private fun startScanDialog() {
        foundDevices.clear()
        isScanning = true
        btnScan.isEnabled = false
        bleManager.callback = this
        bleManager.startScan()
        showToast("Scanning for devices...")

        val deviceNames = mutableListOf<String>()
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("Select Device")
            .setItems(arrayOf("Scanning...")) { _, _ -> }
            .setNegativeButton("Cancel") { _, _ -> bleManager.stopScan(); isScanning = false; btnScan.isEnabled = true }
            .create()
        dialog.show()

        // Reuse the callback's onScanResult to update dialog — simplified approach: collect for 8s then show
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        handler.postDelayed({
            dialog.dismiss()
            bleManager.stopScan()
            isScanning = false
            btnScan.isEnabled = true
            if (foundDevices.isEmpty()) {
                showToast("No devices found")
            } else {
                showDevicePickerDialog()
            }
        }, 8000)
    }

    private fun showDevicePickerDialog() {
        val names = foundDevices.map { (dev, rssi) ->
            val name = try { dev.name ?: dev.address } catch (_: Exception) { dev.address }
            "$name (${dev.address}) RSSI:$rssi"
        }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle("Select Device")
            .setItems(names) { _, idx ->
                bleManager.connect(foundDevices[idx].first)
                updateConnectionUi(false, foundDevices[idx].first.address, "Connecting...")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmClearFile(fi: FileInfo) {
        AlertDialog.Builder(requireContext())
            .setTitle("Clear File")
            .setMessage("Delete ${fi.name}?")
            .setPositiveButton("Delete") { _, _ -> bleManager.clearFile(fi.name) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateConnectionUi(connected: Boolean, name: String, status: String) {
        isConnected = connected
        tvStatusDot.setBackgroundResource(
            when {
                connected  -> R.drawable.dot_connected
                status.contains("Connecting") -> R.drawable.dot_scanning
                else       -> R.drawable.dot_disconnected
            }
        )
        tvDeviceName.text = if (connected) name else ""
        tvStatusText.text = status
        btnDisconnect.isEnabled = connected
    }

    private fun parseRecordsFromJson(content: String): List<SensorRecord> {
        val records = mutableListOf<SensorRecord>()
        try {
            // Try as JSON array
            val arr = JSONArray(content)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                records.add(parseRecord(obj))
            }
        } catch (_: Exception) {
            // Try line by line
            content.lines().forEach { line ->
                val trimmed = line.trim().trimEnd(',')
                if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                    try { records.add(parseRecord(JSONObject(trimmed))) } catch (_: Exception) {}
                }
            }
        }
        return records
    }

    private fun parseRecord(obj: JSONObject): SensorRecord {
        return SensorRecord(
            index     = obj.optInt("i", 0),
            timestamp = obj.optLong("ts", 0),
            tempAht   = obj.optDouble("t", 0.0).toFloat(),
            humidity  = obj.optDouble("h", 0.0).toFloat(),
            pressure  = obj.optDouble("p", 0.0).toFloat(),
            tempBmp   = obj.optDouble("tb", 0.0).toFloat()
        )
    }

    // ─── BleCallback ────────────────────────────────────────────────────────
    override fun onConnected(name: String) {
        requireActivity().runOnUiThread {
            updateConnectionUi(true, name, "Connected")
            showToast("Connected to $name")
            bleManager.requestFileList()
        }
    }

    override fun onDisconnected() {
        requireActivity().runOnUiThread {
            updateConnectionUi(false, "", "Disconnected")
            showToast("Disconnected")
        }
    }

    override fun onScanResult(device: BluetoothDevice, rssi: Int) {
        val existing = foundDevices.indexOfFirst { it.first.address == device.address }
        if (existing < 0) foundDevices.add(Pair(device, rssi))
    }

    override fun onScanStopped() { /* handled in postDelayed */ }

    override fun onFileList(json: String) {
        requireActivity().runOnUiThread {
            val files = mutableListOf<FileInfo>()
            try {
                val arr = JSONArray(json)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    files.add(FileInfo(
                        name    = obj.optString("name", ""),
                        size    = obj.optInt("size", 0),
                        records = obj.optInt("records", 0)
                    ))
                }
            } catch (e: Exception) {
                showToast("Parse error: ${e.message}")
            }
            fileAdapter.submitList(files)
        }
    }

    override fun onFileContent(filename: String, content: String) {
        requireActivity().runOnUiThread {
            val records = parseRecordsFromJson(content)
            recordAdapter.submitList(records)
            tvRecordCount.text = "Records: ${records.size}"
            showToast("Loaded ${records.size} records from $filename")
        }
    }

    override fun onStatusUpdate(status: String) {
        requireActivity().runOnUiThread {
            if (status.contains("CLEARED")) showToast("File cleared")
        }
    }

    override fun onLog(msg: String) {
        // Log.d("FilesFragment", msg)
    }

    private fun showToast(msg: String) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        bleManager.callback = this
    }
}

// ─── FileAdapter ────────────────────────────────────────────────────────────
class FileAdapter(
    private val onDownload: (FileInfo) -> Unit,
    private val onClear:    (FileInfo) -> Unit
) : RecyclerView.Adapter<FileAdapter.VH>() {

    private val items = mutableListOf<FileInfo>()

    fun submitList(list: List<FileInfo>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_file, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val fi = items[position]
        holder.tvName.text = fi.name
        holder.tvInfo.text = "${fi.size} bytes • ${fi.records} records"
        holder.btnDownload.setOnClickListener { onDownload(fi) }
        holder.btnClear.setOnClickListener    { onClear(fi) }
    }

    override fun getItemCount() = items.size

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvName:      TextView = v.findViewById(R.id.tvFileName)
        val tvInfo:      TextView = v.findViewById(R.id.tvFileInfo)
        val btnDownload: Button   = v.findViewById(R.id.btnDownload)
        val btnClear:    Button   = v.findViewById(R.id.btnClearFile)
    }
}

// ─── RecordAdapter ──────────────────────────────────────────────────────────
class RecordAdapter : RecyclerView.Adapter<RecordAdapter.VH>() {

    private val items = mutableListOf<SensorRecord>()

    fun submitList(list: List<SensorRecord>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_record, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val r = items[position]
        holder.tvIndex.text    = r.index.toString()
        holder.tvTime.text     = r.formatTime()
        holder.tvTempAht.text  = String.format("%.1f", r.tempAht)
        holder.tvHumidity.text = String.format("%.1f", r.humidity)
        holder.tvPressure.text = String.format("%.1f", r.pressure)
        holder.tvTempBmp.text  = String.format("%.1f", r.tempBmp)
    }

    override fun getItemCount() = items.size

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvIndex:    TextView = v.findViewById(R.id.tvRecIndex)
        val tvTime:     TextView = v.findViewById(R.id.tvRecTime)
        val tvTempAht:  TextView = v.findViewById(R.id.tvRecTempAht)
        val tvHumidity: TextView = v.findViewById(R.id.tvRecHumidity)
        val tvPressure: TextView = v.findViewById(R.id.tvRecPressure)
        val tvTempBmp:  TextView = v.findViewById(R.id.tvRecTempBmp)
    }
}
