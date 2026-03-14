package com.kmutt.datalogger

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue

class BleManager(private val context: Context) {

    interface BleCallback {
        fun onConnected(name: String)
        fun onDisconnected()
        fun onScanResult(device: BluetoothDevice, rssi: Int)
        fun onScanStopped()
        fun onFileList(files: List<FileInfo>)
        fun onFileContent(filename: String, csvContent: String)
        fun onFileCleared(filename: String)
        fun onLog(msg: String)
    }

    companion object {
        private const val TAG = "BleManager"
        val SERVICE_UUID = UUID.fromString("cc000001-0000-1000-8000-00805f9b34fb")
        val CMD_UUID     = UUID.fromString("cc000002-0000-1000-8000-00805f9b34fb")
        val FILE_UUID    = UUID.fromString("cc000003-0000-1000-8000-00805f9b34fb")
        val STAT_UUID    = UUID.fromString("cc000004-0000-1000-8000-00805f9b34fb")
        val CCCD_UUID    = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val GATT_OP_DELAY_MS = 300L
    }

    var callback: BleCallback? = null

    private val bluetoothAdapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private var bleScanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null
    private var cmdChar: BluetoothGattCharacteristic? = null
    private var fileChar: BluetoothGattCharacteristic? = null
    private var statChar: BluetoothGattCharacteristic? = null

    var pendingCommand: String = ""
    val fileChunks = StringBuilder()

    // GATT operation queue
    private val opQueue: LinkedBlockingQueue<Runnable> = LinkedBlockingQueue()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var operationPending = false

    private fun enqueueOp(op: Runnable) {
        opQueue.add(op)
        if (!operationPending) executeNextOp()
    }

    private fun executeNextOp() {
        val op = opQueue.poll()
        if (op != null) {
            operationPending = true
            mainHandler.post(op)
        } else {
            operationPending = false
        }
    }

    fun operationComplete() {
        mainHandler.postDelayed({
            operationPending = false
            executeNextOp()
        }, GATT_OP_DELAY_MS)
    }

    // ── Scan ─────────────────────────────────────────────────────────────────
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            result.device?.let { dev ->
                callback?.onScanResult(dev, result.rssi)
            }
        }
        override fun onScanFailed(errorCode: Int) {
            callback?.onLog("Scan failed: $errorCode")
            callback?.onScanStopped()
        }
    }

    fun startScan() {
        if (!hasScanPermission()) { callback?.onLog("Missing BLE scan permission"); return }
        bleScanner = bluetoothAdapter?.bluetoothLeScanner
        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(android.os.ParcelUuid(SERVICE_UUID)).build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            bleScanner?.startScan(filters, settings, scanCallback)
            callback?.onLog("Scan started")
        } catch (e: Exception) {
            callback?.onLog("Scan error: ${e.message}")
        }
        mainHandler.postDelayed({ stopScan() }, 15000)
    }

    fun stopScan() {
        if (!hasScanPermission()) return
        try { bleScanner?.stopScan(scanCallback) } catch (_: Exception) {}
        callback?.onScanStopped()
    }

    // ── Connect / Disconnect ──────────────────────────────────────────────────
    fun connect(device: BluetoothDevice) {
        if (!hasConnectPermission()) { callback?.onLog("Missing BLE connect permission"); return }
        disconnect()
        callback?.onLog("Connecting to ${device.address}...")
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        if (!hasConnectPermission()) return
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (_: Exception) {}
        gatt = null
        cmdChar = null
        fileChar = null
        statChar = null
        operationPending = false
        opQueue.clear()
    }

    // ── Send command ──────────────────────────────────────────────────────────
    fun sendCommand(cmd: String) {
        val chr = cmdChar ?: run { callback?.onLog("CMD char not found"); return }
        enqueueOp(Runnable {
            if (!hasConnectPermission()) { operationComplete(); return@Runnable }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val result = gatt?.writeCharacteristic(
                        chr, cmd.toByteArray(Charsets.UTF_8),
                        BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    ) ?: BluetoothStatusCodes.ERROR_UNKNOWN
                    if (result != BluetoothStatusCodes.SUCCESS) {
                        callback?.onLog("Write failed: $result")
                    }
                } else {
                    @Suppress("DEPRECATION")
                    chr.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    @Suppress("DEPRECATION")
                    chr.value = cmd.toByteArray(Charsets.UTF_8)
                    @Suppress("DEPRECATION")
                    val ok = gatt?.writeCharacteristic(chr) ?: false
                    if (!ok) callback?.onLog("Write returned false")
                }
                operationComplete()
            } catch (e: Exception) {
                callback?.onLog("Write exception: ${e.message}")
                operationComplete()
            }
        })
    }

    fun requestFileList() {
        pendingCommand = "LISTFILES"
        fileChunks.clear()
        sendCommand("LISTFILES")
    }

    fun requestFile(filename: String) {
        pendingCommand = "READFILE:$filename"
        fileChunks.clear()
        sendCommand("READFILE:$filename")
    }

    fun clearFile(filename: String) {
        pendingCommand = "CLEARFILE:$filename"
        sendCommand("CLEARFILE:$filename")
    }

    fun clearAll() {
        pendingCommand = "CLEARALL"
        sendCommand("CLEARALL")
    }

    // ── Enable notifications ───────────────────────────────────────────────────
    private fun enableNotify(characteristic: BluetoothGattCharacteristic) {
        enqueueOp(Runnable {
            if (!hasConnectPermission()) { operationComplete(); return@Runnable }
            val g = gatt ?: run { operationComplete(); return@Runnable }
            try {
                g.setCharacteristicNotification(characteristic, true)
                val descriptor = characteristic.getDescriptor(CCCD_UUID)
                if (descriptor != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        val result = g.writeDescriptor(
                            descriptor,
                            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        )
                        if (result != BluetoothStatusCodes.SUCCESS) {
                            callback?.onLog("Descriptor write failed: $result")
                            operationComplete()
                        }
                        // completion handled in onDescriptorWrite
                    } else {
                        @Suppress("DEPRECATION")
                        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        val ok = g.writeDescriptor(descriptor)
                        if (!ok) {
                            callback?.onLog("Descriptor write returned false")
                            operationComplete()
                        }
                        // completion handled in onDescriptorWrite
                    }
                } else {
                    callback?.onLog("CCCD descriptor not found for ${characteristic.uuid}")
                    operationComplete()
                }
            } catch (e: Exception) {
                callback?.onLog("enableNotify exception: ${e.message}")
                operationComplete()
            }
        })
    }

    // ── GATT callbacks ────────────────────────────────────────────────────────
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (!hasConnectPermission()) return
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    // Request large MTU first so 200-byte ESP32 chunks are received intact.
                    // discoverServices() is called in onMtuChanged after negotiation completes.
                    callback?.onLog("GATT connected, requesting MTU 512...")
                    gatt.requestMtu(512)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    callback?.onLog("GATT disconnected (status=$status)")
                    mainHandler.post { callback?.onDisconnected() }
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            callback?.onLog("MTU changed to $mtu (status=$status), discovering services...")
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                callback?.onLog("Service discovery failed: $status")
                return
            }
            val service = gatt.getService(SERVICE_UUID)
            if (service == null) {
                callback?.onLog("Target service not found")
                return
            }
            cmdChar  = service.getCharacteristic(CMD_UUID)
            fileChar = service.getCharacteristic(FILE_UUID)
            statChar = service.getCharacteristic(STAT_UUID)

            callback?.onLog("Services discovered. Enabling notifications...")

            fileChar?.let { enableNotify(it) }
            statChar?.let { enableNotify(it) }

            // Fire onConnected AFTER both notify setup ops complete so FILE_UUID
            // notifications are active before FilesFragment calls requestFileList()
            val devName = try {
                if (hasConnectPermission()) gatt.device.name ?: gatt.device.address
                else gatt.device.address
            } catch (_: Exception) { "Unknown" }
            enqueueOp(Runnable {
                mainHandler.post { callback?.onConnected(devName) }
                operationComplete()
            })
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                callback?.onLog("onDescriptorWrite failed: $status")
            }
            operationComplete()
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            // CMD uses WRITE_NO_RESPONSE — operationComplete() already called in sendCommand Runnable
            if (characteristic.uuid != CMD_UUID) operationComplete()
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val value = characteristic.value ?: return
            handleCharacteristicValue(characteristic.uuid, value)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            handleCharacteristicValue(characteristic.uuid, value)
        }
    }

    private fun handleCharacteristicValue(uuid: UUID, value: ByteArray) {
        val str = String(value, Charsets.UTF_8)
        when (uuid) {
            FILE_UUID -> {
                if (str == "END") {
                    val content = fileChunks.toString()
                    fileChunks.clear()
                    val cmd = pendingCommand
                    mainHandler.post {
                        when {
                            cmd == "LISTFILES" -> {
                                val files = parseFileList(content)
                                callback?.onFileList(files)
                            }
                            cmd.startsWith("READFILE:") -> {
                                val fname = cmd.removePrefix("READFILE:")
                                callback?.onFileContent(fname, content)
                            }
                            else -> callback?.onLog("Unknown pending command: $cmd")
                        }
                    }
                } else {
                    fileChunks.append(str)
                }
            }
            STAT_UUID -> {
                mainHandler.post {
                    if (str.startsWith("CLEARED:")) {
                        val fname = str.removePrefix("CLEARED:")
                        callback?.onFileCleared(fname)
                    } else {
                        callback?.onLog("STAT: $str")
                    }
                }
            }
        }
    }

    private fun parseFileList(json: String): List<FileInfo> {
        val files = mutableListOf<FileInfo>()
        try {
            val arr = org.json.JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                files.add(FileInfo(
                    name    = obj.optString("name", ""),
                    size    = obj.optInt("size", 0),
                    records = obj.optInt("records", 0)
                ))
            }
        } catch (e: Exception) {
            Log.e(TAG, "parseFileList error: ${e.message}")
        }
        return files
    }

    // ── Permission helpers ────────────────────────────────────────────────────
    private fun hasScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun hasConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else true
    }
}
