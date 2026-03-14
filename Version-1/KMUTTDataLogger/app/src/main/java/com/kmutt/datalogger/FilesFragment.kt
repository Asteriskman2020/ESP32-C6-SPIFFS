package com.kmutt.datalogger

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

class FilesFragment : Fragment(), BleManager.BleCallback {

    private lateinit var bleManager: BleManager
    private lateinit var databaseManager: DatabaseManager

    // Connection card
    private lateinit var tvStatusDot:   TextView
    private lateinit var tvDeviceName:  TextView
    private lateinit var tvStatusText:  TextView
    private lateinit var btnScan:       Button
    private lateinit var btnDisconnect: Button

    // Files card
    private lateinit var btnRefresh: Button
    private lateinit var rvFiles:    RecyclerView

    // Data preview card
    private lateinit var tvRecordCount: TextView
    private lateinit var rvRecords:     RecyclerView

    // Upload card
    private lateinit var btnUpload:        Button
    private lateinit var cbClearAfterUpload: CheckBox
    private lateinit var tvUploadStatus:   TextView

    private lateinit var fileAdapter:   FileAdapter
    private lateinit var recordAdapter: RecordAdapter

    private val foundDevices = mutableListOf<Pair<BluetoothDevice, Int>>()
    private var isConnected = false
    private var isScanning  = false

    private var currentFilename = ""
    private var currentCsvContent = ""

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_files, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        bleManager      = (requireActivity() as MainActivity).bleManager
        databaseManager = (requireActivity() as MainActivity).databaseManager
        bleManager.callback = this

        tvStatusDot     = view.findViewById(R.id.tvStatusDot)
        tvDeviceName    = view.findViewById(R.id.tvDeviceName)
        tvStatusText    = view.findViewById(R.id.tvStatusText)
        btnScan         = view.findViewById(R.id.btnScan)
        btnDisconnect   = view.findViewById(R.id.btnDisconnect)
        btnRefresh      = view.findViewById(R.id.btnRefresh)
        rvFiles         = view.findViewById(R.id.rvFiles)
        tvRecordCount   = view.findViewById(R.id.tvRecordCount)
        rvRecords       = view.findViewById(R.id.rvRecords)
        btnUpload       = view.findViewById(R.id.btnUpload)
        cbClearAfterUpload = view.findViewById(R.id.cbClearAfterUpload)
        tvUploadStatus  = view.findViewById(R.id.tvUploadStatus)

        fileAdapter = FileAdapter(
            onDownload = { fi -> downloadFile(fi) },
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
        btnUpload.setOnClickListener {
            uploadCurrentData()
        }

        btnUpload.isEnabled = false
        updateConnectionUi(false, "", "Not connected")
    }

    private fun downloadFile(fi: FileInfo) {
        currentFilename = fi.name
        currentCsvContent = ""
        bleManager.requestFile(fi.name)
        showToast("Downloading ${fi.name}...")
    }

    private fun uploadCurrentData() {
        if (currentCsvContent.isEmpty()) {
            showToast("No data loaded. Download a file first.")
            return
        }
        tvUploadStatus.text = "Uploading..."
        btnUpload.isEnabled = false
        databaseManager.uploadCsv(currentFilename, currentCsvContent, object : DatabaseManager.UploadCallback {
            override fun onSuccess(count: Int) {
                tvUploadStatus.text = "Uploaded $count records"
                btnUpload.isEnabled = true
                showToast("Upload successful: $count records")
                if (cbClearAfterUpload.isChecked && currentFilename.isNotEmpty()) {
                    bleManager.clearFile(currentFilename)
                }
            }
            override fun onError(msg: String) {
                tvUploadStatus.text = "Upload failed: $msg"
                btnUpload.isEnabled = true
                showToast("Upload error: $msg")
            }
        })
    }

    private fun startScanDialog() {
        foundDevices.clear()
        isScanning = true
        btnScan.isEnabled = false
        bleManager.callback = this
        bleManager.startScan()
        showToast("Scanning for 8 seconds...")

        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        handler.postDelayed({
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
            .setMessage("Delete ${fi.name} from device?")
            .setPositiveButton("Delete") { _, _ -> bleManager.clearFile(fi.name) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateConnectionUi(connected: Boolean, name: String, status: String) {
        isConnected = connected
        tvStatusDot.setBackgroundResource(
            when {
                connected -> R.drawable.dot_connected
                status.contains("Connecting") -> R.drawable.dot_scanning
                else -> R.drawable.dot_disconnected
            }
        )
        tvDeviceName.text = if (connected) name else ""
        tvStatusText.text = status
        btnDisconnect.isEnabled = connected
    }

    // ── BleCallback ─────────────────────────────────────────────────────────
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

    override fun onFileList(files: List<FileInfo>) {
        requireActivity().runOnUiThread {
            fileAdapter.submitList(files)
            showToast("Found ${files.size} file(s)")
        }
    }

    override fun onFileContent(filename: String, csvContent: String) {
        requireActivity().runOnUiThread {
            currentFilename   = filename
            currentCsvContent = csvContent
            val records = parseCsvRecords(csvContent)
            recordAdapter.submitList(records)
            tvRecordCount.text = "Records: ${records.size}"
            btnUpload.isEnabled = records.isNotEmpty()
            showToast("Loaded ${records.size} records from $filename")
        }
    }

    override fun onFileCleared(filename: String) {
        requireActivity().runOnUiThread {
            showToast("Cleared: $filename")
            // Refresh file list
            if (isConnected) bleManager.requestFileList()
        }
    }

    override fun onLog(msg: String) {
        // android.util.Log.d("FilesFragment", msg)
    }

    private fun parseCsvRecords(csv: String): List<SensorRecord> {
        val records = mutableListOf<SensorRecord>()
        val lines = csv.lines()
        for (i in lines.indices) {
            val line = lines[i].trim()
            if (i == 0 && line.startsWith("index")) continue  // skip header
            if (line.isEmpty()) continue
            val rec = SensorRecord.fromCsvLine(line)
            if (rec != null) records.add(rec)
        }
        return records
    }

    private fun showToast(msg: String) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        bleManager.callback = this
    }
}

// ─── FileAdapter ─────────────────────────────────────────────────────────────
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
        holder.tvInfo.text = "${fi.records} records • ${fi.size} bytes"
        holder.btnDownload.setOnClickListener { onDownload(fi) }
        holder.btnClear.setOnClickListener   { onClear(fi) }
    }

    override fun getItemCount() = items.size

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvName:      TextView = v.findViewById(R.id.tvFileName)
        val tvInfo:      TextView = v.findViewById(R.id.tvFileInfo)
        val btnDownload: Button   = v.findViewById(R.id.btnDownload)
        val btnClear:    Button   = v.findViewById(R.id.btnClearFile)
    }
}

// ─── RecordAdapter ────────────────────────────────────────────────────────────
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
        holder.tvDateTime.text = r.dateTime
        holder.tvTempAht.text  = String.format("%.1f", r.tempAht)
        holder.tvHumidity.text = String.format("%.1f%%", r.humidity)
        holder.tvPressure.text = String.format("%.1f", r.pressure)
        holder.tvTempBmp.text  = String.format("%.1f", r.tempBmp)
    }

    override fun getItemCount() = items.size

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvIndex:    TextView = v.findViewById(R.id.tvRecIndex)
        val tvDateTime: TextView = v.findViewById(R.id.tvRecDateTime)
        val tvTempAht:  TextView = v.findViewById(R.id.tvRecTempAht)
        val tvHumidity: TextView = v.findViewById(R.id.tvRecHumidity)
        val tvPressure: TextView = v.findViewById(R.id.tvRecPressure)
        val tvTempBmp:  TextView = v.findViewById(R.id.tvRecTempBmp)
    }
}
