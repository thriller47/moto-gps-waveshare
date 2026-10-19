package com.motogps.android.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import com.motogps.android.jni.MotoBLEProtocolCodec
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

@SuppressLint("MissingPermission")
class MotoBLEManager(private val context: Context) {
    val serviceUuid: UUID = UUID.fromString("7e57a000-b50c-4b6a-9c57-40a54e8e1000")
    val phoneToDeviceUuid: UUID = UUID.fromString("7e57a001-b50c-4b6a-9c57-40a54e8e1000")
    val deviceToPhoneUuid: UUID = UUID.fromString("7e57a002-b50c-4b6a-9c57-40a54e8e1000")
    val cccdUuid: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    var onConnectionStateChange: ((String) -> Unit)? = null
    var onConnectionError: ((String) -> Unit)? = null

    private var bluetoothGatt: BluetoothGatt? = null
    private var connectedDevice: BluetoothDevice? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var readCharacteristic: BluetoothGattCharacteristic? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var heartbeatScheduler: ScheduledExecutorService? = null

    private val bleCodec = MotoBLEProtocolCodec()
    private var codecPtr: Long = 0L

    private enum class HandshakeState {
        IDLE,
        STARTING_SENT,
        READY_SENT,
        COMPLETED
    }

    private var handshakeState = HandshakeState.IDLE
    private var currentSessionId: Long = 0L
    private var isScanning = false

    private val writeQueue = ArrayDeque<ByteArray>()
    private var writeInProgress = false

    private var cccdRetryCount = 0
    private var cccdWritePending = false
    private val cccdTimeoutRunnable = Runnable {
        if (cccdWritePending) {
            Log.w("MotoBLEManager", "CCCD write timed out after 700ms, retrying")
            retryCccdWrite()
        }
    }

    init {
        try {
            codecPtr = bleCodec.nativeInit(247)
        } catch (t: Throwable) {
            Log.e("MotoBLEManager", "Failed to init native codec", t)
        }
    }

    fun enqueueSendFramesTask(frames: Array<ByteArray>) {
        synchronized(writeQueue) {
            for (frame in frames) {
                writeQueue.addLast(frame)
            }
            if (!writeInProgress) {
                writeInProgress = true
                mainHandler.post { processNextWrite() }
            }
        }
    }

    private fun processNextWrite() {
        val frame: ByteArray?
        synchronized(writeQueue) {
            frame = if (writeQueue.isNotEmpty()) writeQueue.removeFirst() else null
            if (frame == null) {
                writeInProgress = false
                return
            }
        }

        val gatt = bluetoothGatt ?: run {
            synchronized(writeQueue) { writeInProgress = false }
            return
        }
        val characteristic = writeCharacteristic ?: run {
            synchronized(writeQueue) { writeInProgress = false }
            return
        }

        try {
            val writeType = if ((characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(characteristic, frame!!, writeType)
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = frame!!
                characteristic.writeType = writeType
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(characteristic)
            }
        } catch (t: Throwable) {
            Log.e("MotoBLEManager", "Error writing characteristic", t)
            synchronized(writeQueue) { writeInProgress = false }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectedDevice = gatt.device
                    handshakeState = HandshakeState.IDLE
                    mainHandler.post { onConnectionStateChange?.invoke("Connessione in corso...") }
                    mainHandler.postDelayed({
                        try {
                            bluetoothGatt?.discoverServices()
                        } catch (t: Throwable) {
                            Log.e("MotoBLEManager", "discoverServices failed", t)
                        }
                    }, 300)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    stopHeartbeat()
                    mainHandler.removeCallbacks(cccdTimeoutRunnable)
                    cccdWritePending = false
                    cccdRetryCount = 0
                    handshakeState = HandshakeState.IDLE
                    writeCharacteristic = null
                    readCharacteristic = null
                    connectedDevice = null
                    synchronized(writeQueue) {
                        writeQueue.clear()
                        writeInProgress = false
                    }
                    try {
                        gatt.close()
                    } catch (_: Throwable) {}
                    if (bluetoothGatt == gatt) {
                        bluetoothGatt = null
                    }
                    mainHandler.post {
                        onConnectionStateChange?.invoke("Non connesso")
                        if (status != BluetoothGatt.GATT_SUCCESS) {
                            onConnectionError?.invoke("Errore di connessione GATT ($status)")
                        }
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(serviceUuid)
                if (service != null) {
                    writeCharacteristic = service.getCharacteristic(phoneToDeviceUuid)
                    readCharacteristic = service.getCharacteristic(deviceToPhoneUuid)
                    mainHandler.postDelayed({
                        try {
                            val mtuRequested = gatt.requestMtu(247)
                            if (!mtuRequested) {
                                startCccdWrite(gatt)
                            }
                        } catch (t: Throwable) {
                            Log.w("MotoBLEManager", "requestMtu failed, falling back to CCCD", t)
                            startCccdWrite(gatt)
                        }
                    }, 200)
                } else {
                    mainHandler.post { onConnectionError?.invoke("Errore: Servizio BLE non trovato") }
                }
            } else {
                mainHandler.post { onConnectionError?.invoke("Errore discovery servizi GATT ($status)") }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            try {
                if (status == BluetoothGatt.GATT_SUCCESS && codecPtr != 0L) {
                    bleCodec.nativeSetMaximumFrameSize(codecPtr, mtu.coerceAtMost(247))
                }
            } catch (t: Throwable) {
                Log.w("MotoBLEManager", "nativeSetMaximumFrameSize error", t)
            }
            mainHandler.postDelayed({
                startCccdWrite(gatt)
            }, 200)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid == cccdUuid) {
                mainHandler.removeCallbacks(cccdTimeoutRunnable)
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    cccdWritePending = false
                    cccdRetryCount = 0
                    try {
                        currentSessionId = (System.currentTimeMillis() % 0x7FFFFFFF).coerceAtLeast(1)
                        handshakeState = HandshakeState.STARTING_SENT
                        if (codecPtr != 0L) {
                            bleCodec.nativeResetInboundState(codecPtr)
                            val frames = bleCodec.nativeEncodePhoneStarting(codecPtr, currentSessionId)
                            enqueueSendFramesTask(frames)
                        }
                        startHeartbeat()
                    } catch (t: Throwable) {
                        Log.e("MotoBLEManager", "Error starting handshake", t)
                    }
                } else {
                    Log.w("MotoBLEManager", "onDescriptorWrite failed with status=$status")
                    retryCccdWrite()
                }
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            mainHandler.postDelayed({
                processNextWrite()
            }, 18)
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val data = characteristic.value ?: return
            handleInboundFrame(data)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            handleInboundFrame(value)
        }
    }

    private fun doWriteDescriptor(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, value: ByteArray): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val resCode = gatt.writeDescriptor(descriptor, value)
                resCode == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = value
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(descriptor)
            }
        } catch (t: Throwable) {
            Log.e("MotoBLEManager", "doWriteDescriptor exception", t)
            false
        }
    }

    private fun retryCccdWrite() {
        mainHandler.removeCallbacks(cccdTimeoutRunnable)
        if (cccdRetryCount < 5) {
            cccdRetryCount++
            mainHandler.postDelayed({
                val gatt = bluetoothGatt
                if (gatt != null && cccdWritePending) {
                    performCccdWrite(gatt)
                }
            }, 350)
        } else {
            Log.e("MotoBLEManager", "CCCD write failed after 5 retries")
            cccdWritePending = false
            mainHandler.post { onConnectionError?.invoke("Errore abilitazione notifiche CCCD") }
        }
    }

    private fun performCccdWrite(gatt: BluetoothGatt) {
        val characteristic = readCharacteristic ?: run {
            cccdWritePending = false
            return
        }
        try {
            gatt.setCharacteristicNotification(characteristic, true)
            val descriptor = characteristic.getDescriptor(cccdUuid) ?: run {
                cccdWritePending = false
                return
            }
            val cccdValue = if ((characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            } else {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            }
            val success = doWriteDescriptor(gatt, descriptor, cccdValue)
            if (!success) {
                retryCccdWrite()
            } else {
                mainHandler.removeCallbacks(cccdTimeoutRunnable)
                mainHandler.postDelayed(cccdTimeoutRunnable, 700)
            }
        } catch (t: Throwable) {
            Log.e("MotoBLEManager", "performCccdWrite exception", t)
            retryCccdWrite()
        }
    }

    private fun startCccdWrite(gatt: BluetoothGatt) {
        cccdRetryCount = 0
        cccdWritePending = true
        performCccdWrite(gatt)
    }

    private fun handleInboundFrame(frame: ByteArray) {
        if (codecPtr == 0L) return
        try {
            val inboundMessage = bleCodec.nativePushDeviceFrame(codecPtr, frame, System.currentTimeMillis())
            if (inboundMessage != null && inboundMessage.complete) {
                val status = inboundMessage.connStatus
                if (status != null && status.role == 2 && status.state == 1) {
                    if (handshakeState == HandshakeState.STARTING_SENT) {
                        handshakeState = HandshakeState.READY_SENT
                        val frames = bleCodec.nativeEncodePhoneReady(codecPtr, currentSessionId)
                        enqueueSendFramesTask(frames)
                    } else if (handshakeState == HandshakeState.READY_SENT) {
                        handshakeState = HandshakeState.COMPLETED
                        mainHandler.post { onConnectionStateChange?.invoke("Connesso") }
                        startHeartbeat()
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e("MotoBLEManager", "Error handling inbound frame", t)
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val device = result?.device ?: return
            if (!isScanning) return
            stopScan()
            mainHandler.post {
                onConnectionStateChange?.invoke("Connessione in corso...")
                connectToDevice(device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            isScanning = false
            mainHandler.post {
                onConnectionStateChange?.invoke("Non connesso")
                onConnectionError?.invoke("Errore di scansione ($errorCode)")
            }
        }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        try {
            try {
                bluetoothGatt?.close()
            } catch (_: Throwable) {}
            bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (t: Throwable) {
            Log.e("MotoBLEManager", "connectGatt failed", t)
            mainHandler.post {
                onConnectionStateChange?.invoke("Non connesso")
                onConnectionError?.invoke("Errore avvio connessione: ${t.message}")
            }
        }
    }

    fun stopScan() {
        isScanning = false
        try {
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            bluetoothManager?.adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (t: Throwable) {
            Log.w("MotoBLEManager", "Error stopping BLE scan: ${t.message}")
        }
    }

    fun startScan() {
        stopScan()
        mainHandler.post { onConnectionStateChange?.invoke("Ricerca in corso...") }
        try {
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = bluetoothManager?.adapter ?: run {
                mainHandler.post { onConnectionError?.invoke("Bluetooth non disponibile") }
                return
            }
            val scanner = adapter.bluetoothLeScanner ?: run {
                mainHandler.post { onConnectionError?.invoke("Attiva il Bluetooth per connetterti") }
                return
            }

            val filter = ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(serviceUuid))
                .build()

            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            isScanning = true
            scanner.startScan(listOf(filter), settings, scanCallback)
        } catch (t: Throwable) {
            isScanning = false
            Log.e("MotoBLEManager", "Failed to start BLE scan", t)
            mainHandler.post {
                onConnectionStateChange?.invoke("Non connesso")
                onConnectionError?.invoke("Permessi Bluetooth mancanti o errore scansione")
            }
        }
    }

    private fun startHeartbeat() {
        stopHeartbeat()
        heartbeatScheduler = Executors.newSingleThreadScheduledExecutor()
        heartbeatScheduler?.scheduleAtFixedRate({
            try {
                if (codecPtr != 0L && currentSessionId != 0L) {
                    val frames = bleCodec.nativeEncodeHeartbeat(codecPtr, currentSessionId, System.currentTimeMillis() % Int.MAX_VALUE)
                    enqueueSendFramesTask(frames)
                }
            } catch (t: Throwable) {
                Log.w("MotoBLEManager", "Heartbeat send failed: ${t.message}")
            }
        }, 1000, 1000, TimeUnit.MILLISECONDS)
    }

    private fun stopHeartbeat() {
        try {
            heartbeatScheduler?.shutdownNow()
        } catch (_: Throwable) {}
        heartbeatScheduler = null
    }

    fun disconnect() {
        stopHeartbeat()
        stopScan()
        mainHandler.removeCallbacks(cccdTimeoutRunnable)
        cccdWritePending = false
        bluetoothGatt?.let { gatt ->
            try {
                gatt.disconnect()
            } catch (_: Throwable) {}
            try {
                gatt.close()
            } catch (_: Throwable) {}
        }
        bluetoothGatt = null
    }
}
