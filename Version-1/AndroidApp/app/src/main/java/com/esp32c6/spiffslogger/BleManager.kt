package com.esp32c6.spiffslogger

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.util.*
import java.util.concurrent.LinkedBlockingQueue

class BleManager(private val context: Context) {

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("bc000001-0000-1000-8000-00805f9b34fb")
        val CMD_UUID:     UUID = UUID.fromString("bc000002-0000-1000-8000-00805f9b34fb")
        val FILE_UUID:    UUID = UUID.fromString("bc000003-0000-1000-8000-00805f9b34fb")
        val STAT_UUID:    UUID = UUID.fromString("bc000004-0000-1000-8000-00805f9b34fb")
        val CCCD_UUID:    UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val DEVICE_NAME = "ESP32-C6 SPIFFS"
        private const val OP_DELAY_MS = 300L
    }

    interface Callback {
        fun onConnected(name: String)
        fun onDisconnected()
        fun onScanResult(device: BluetoothDevice, rssi: Int)
        fun onScanStopped()
        fun onFileChunk(chunk: String)
        fun onFileComplete(fullContent: String)
        fun onStatusUpdate(status: String)
        fun onLog(msg: String)
    }

    private var callback: Callback? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var cmdChar:  BluetoothGattCharacteristic? = null
    private var fileChar: BluetoothGattCharacteristic? = null
    private var statChar: BluetoothGattCharacteristic? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val fileBuffer   = StringBuilder()

    // GATT operation queue
    private val opQueue = LinkedBlockingQueue<Runnable>()
    private var opInProgress = false

    // Scan
    private val bleAdapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }
    private var bleScanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null

    fun setCallback(cb: Callback) { callback = cb }

    private fun log(msg: String) { mainHandler.post { callback?.onLog(msg) } }

    // ── Permission helper ────────────────────────────────────────────
    private fun hasPermission(perm: String) =
        ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED

    private fun canScan(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        hasPermission(Manifest.permission.BLUETOOTH_SCAN) &&
        hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    // ── Scan ─────────────────────────────────────────────────────────
    fun startScan(durationMs: Long = 3000L) {
        if (!canScan()) { log("Missing BLE permissions"); callback?.onScanStopped(); return }
        bleScanner = bleAdapter?.bluetoothLeScanner
        val filters = listOf(
            ScanFilter.Builder().setDeviceName(DEVICE_NAME).build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        val sc = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                mainHandler.post {
                    callback?.onScanResult(result.device, result.rssi)
                }
            }
        }
        scanCallback = sc
        try {
            bleScanner?.startScan(filters, settings, sc)
            log("Scanning for $DEVICE_NAME …")
        } catch (e: SecurityException) {
            log("Scan permission denied: ${e.message}")
            callback?.onScanStopped()
            return
        }
        mainHandler.postDelayed({
            stopScan()
            callback?.onScanStopped()
        }, durationMs)
    }

    fun stopScan() {
        try { bleScanner?.stopScan(scanCallback) } catch (_: SecurityException) {}
        scanCallback = null
    }

    // ── Connect ──────────────────────────────────────────────────────
    fun connect(device: BluetoothDevice) {
        log("Connecting to ${device.name ?: device.address}…")
        try {
            bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            log("Connect permission denied: ${e.message}")
        }
    }

    fun disconnect() {
        try {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
        } catch (_: SecurityException) {}
        bluetoothGatt = null
        cmdChar = null; fileChar = null; statChar = null
        opQueue.clear()
        opInProgress = false
    }

    // ── Send command ─────────────────────────────────────────────────
    fun sendCommand(cmd: String) {
        val ch = cmdChar ?: run { log("CMD char not ready"); return }
        enqueueOp {
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            ch.setValue(cmd)
            try {
                val ok = bluetoothGatt?.writeCharacteristic(ch) ?: false
                log("CMD '$cmd' write: $ok")
                if (!ok) operationComplete()
            } catch (e: SecurityException) {
                log("Write permission denied"); operationComplete()
            }
        }
    }

    // ── GATT operation queue ─────────────────────────────────────────
    private fun enqueueOp(op: Runnable) {
        opQueue.add(op)
        if (!opInProgress) drainQueue()
    }

    private fun drainQueue() {
        val op = opQueue.poll()
        if (op == null) { opInProgress = false; return }
        opInProgress = true
        op.run()
    }

    fun operationComplete() {
        mainHandler.postDelayed({ drainQueue() }, OP_DELAY_MS)
    }

    // ── GATT callbacks ────────────────────────────────────────────────
    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    log("GATT connected – discovering services…")
                    try { gatt.discoverServices() } catch (e: SecurityException) { log("discoverServices denied") }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    log("GATT disconnected (status=$status)")
                    bluetoothGatt = null
                    cmdChar = null; fileChar = null; statChar = null
                    opQueue.clear(); opInProgress = false
                    mainHandler.post { callback?.onDisconnected() }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("Service discovery failed: $status"); return
            }
            val svc = gatt.getService(SERVICE_UUID)
            if (svc == null) { log("Service not found!"); return }
            cmdChar  = svc.getCharacteristic(CMD_UUID)
            fileChar = svc.getCharacteristic(FILE_UUID)
            statChar = svc.getCharacteristic(STAT_UUID)
            log("Service found. Setting up notifications…")
            setupNotifications()
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val value = characteristic.getStringValue(0) ?: return
            when (characteristic.uuid) {
                FILE_UUID -> handleFileChunk(value)
                STAT_UUID -> {
                    log("STAT: $value")
                    mainHandler.post { callback?.onStatusUpdate(value) }
                }
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            log("Descriptor write status=$status for ${descriptor.characteristic.uuid}")
            operationComplete()
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            operationComplete()
        }
    }

    // ── Notification setup ────────────────────────────────────────────
    private fun setupNotifications() {
        val fChar = fileChar
        val sChar = statChar
        if (fChar != null) {
            enqueueOp {
                enableNotification(fChar)
            }
        }
        if (sChar != null) {
            enqueueOp {
                enableNotification(sChar)
            }
        }
        enqueueOp {
            val name = try {
                bluetoothGatt?.device?.name ?: "ESP32-C6 SPIFFS"
            } catch (_: SecurityException) { "ESP32-C6 SPIFFS" }
            mainHandler.post { callback?.onConnected(name) }
            operationComplete()
        }
    }

    private fun enableNotification(ch: BluetoothGattCharacteristic) {
        val gatt = bluetoothGatt ?: run { operationComplete(); return }
        try {
            gatt.setCharacteristicNotification(ch, true)
            val desc = ch.getDescriptor(CCCD_UUID)
            if (desc == null) {
                log("No CCCD for ${ch.uuid} – skipping")
                operationComplete()
                return
            }
            desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            val wrote = gatt.writeDescriptor(desc)
            log("enableNotify ${ch.uuid}: writeDescriptor=$wrote")
            if (!wrote) operationComplete()
        } catch (e: SecurityException) {
            log("Notification permission denied: ${e.message}")
            operationComplete()
        }
    }

    // ── File chunk assembly ───────────────────────────────────────────
    private fun handleFileChunk(chunk: String) {
        mainHandler.post { callback?.onFileChunk(chunk) }
        when (chunk) {
            "END" -> {
                val content = fileBuffer.toString()
                fileBuffer.clear()
                val finalContent = if (content == "EMPTY") "[]" else content
                mainHandler.post { callback?.onFileComplete(finalContent) }
            }
            "EMPTY" -> {
                fileBuffer.clear()
                fileBuffer.append("EMPTY")
            }
            else -> {
                if (fileBuffer.toString() == "EMPTY") fileBuffer.clear()
                fileBuffer.append(chunk)
            }
        }
    }
}
