package com.motogps.android.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
    val serviceUuid = UUID.fromString("7e57a000-b50c-4b6a-9c57-40a54e8e1000")
    val phoneToDeviceUuid = UUID.fromString("7e57a001-b50c-4b6a-9c57-40a54e8e1000")
    val deviceToPhoneUuid = UUID.fromString("7e57a002-b50c-4b6a-9c57-40a54e8e1000")
    val cccdUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    var onConnectionStateChange: ((String) -> Unit)? = null
    var onConnectionError: ((String) -> Unit)? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var connectedDevice: BluetoothDevice? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var readCharacteristic: BluetoothGattCharacteristic? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var heartbeatScheduler: ScheduledExecutorService? = null

    private var bleCodec: MotoBLEProtocolCodec? = null
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

    // Synchronized FIFO Write Queue for GATT Characteristic Writes
    private val writeQueue = ArrayDeque<ByteArray>()
    private var writeInProgress = false

    // Asynchronous Operation Queue for GATT configuration steps
    private val operationQueue = mutableListOf<() -> Unit>()
    private var operationInProgress = false

    private var cccdRetryCount = 0
    private var cccdWritePending = false
    private val cccdTimeoutRunnable = Runnable {
        try {
            if (cccdWritePending) {
                Log.w("MotoBLEManager", "CCCD write timed out after 600ms, retrying")
                retryCccdWrite()
            }
        } catch (t: Throwable) {
            Log.e("MotoBLEManager", "Caught unhandled exception in cccdTimeoutRunnable", t)
            mainHandler.post {
                onConnectionError?.invoke("Errore BLE: ${t.localizedMessage ?: t.javaClass.simpleName}")
            }
        }
    }

    init {
        try {
            val codec = MotoBLEProtocolCodec()
            bleCodec = codec
            codecPtr = codec.nativeInit(247)
        } catch (t: Throwable) {
            Log.e("MotoBLEManager", "Failed to initialize JNI MotoBLEProtocolCodec, continuing without JNI codec", t)
            bleCodec = null
            codecPtr = 0L
        }
    }

    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            try {
                if (intent?.action == BluetoothDevice.ACTION_BOND_STATE_CHANGED) {
                    val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                    if (device?.address == connectedDevice?.address && bondState == BluetoothDevice.BOND_BONDED) {
                        try {
                            context?.unregisterReceiver(this)
                        } catch (_: Exception) {}
                        mainHandler.postDelayed({
                            try {
                                enqueueOperation {
                                    bluetoothGatt?.discoverServices()
                                }
                            } catch (t: Throwable) {
                                Log.e("MotoBLEManager", "Caught unhandled exception in bondReceiver runnable", t)
                                mainHandler.post {
                                    onConnectionError?.invoke("Errore BLE: ${t.localizedMessage ?: t.javaClass.simpleName}")
                                }
                            }
                        }, 300)
                    }
                }
            } catch (t: Throwable) {
                Log.e("MotoBLEManager", "Caught unhandled exception in bondReceiver.onReceive", t)
                mainHandler.post {
                    onConnectionError?.invoke("Errore BLE: ${t.localizedMessage ?: t.javaClass.simpleName}")
                }
            }
        }
    }

    private fun enqueueOperation(operation: () -> Unit) {
        try {
            operationQueue.add(operation)
            if (!operationInProgress) {
                executeNextOperation()
            }
        } catch (t: Throwable) {
            Log.e("MotoBLEManager", "Caught unhandled exception in enqueueOperation", t)
            mainHandler.post {
                onConnectionError?.invoke("Errore BLE: ${t.localizedMessage ?: t.javaClass.simpleName}")
            }
        }
    }

    private fun executeNextOperation() {
        try {
            if (operationQueue.isNotEmpty()) {
                operationInProgress = true
                val operation = operationQueue.removeAt(0)
                try {
                    operation()
                } catch (t: Throwable) {
                    Log.e("MotoBLEManager", "Caught unhandled exception executing operation", t)
                    completeOperation()
                }
            } else {
                operationInProgress = false
            }
        } catch (t: Throwable) {
            Log.e("MotoBLEManager", "Caught unhandled exception in executeNextOperation", t)
            operationInProgress = false
            mainHandler.post {
                onConnectionError?.invoke("Errore BLE: ${t.localizedMessage ?: t.javaClass.simpleName}")
            }
        }
    }

    private fun completeOperation() {
        try {
            operationInProgress = false
            executeNextOperation()
        } catch (t: Throwable) {
            Log.e("MotoBLEManager", "Caught unhandled exception in completeOperation", t)
            mainHandler.post {
                onConnectionError?.invoke("Errore BLE: ${t.localizedMessage ?: t.javaClass.simpleName}")
            }
        }
    }

    private fun enqueueWriteFrame(frame: ByteArray) {
        try {
            synchronized(writeQueue) {
                writeQueue.addLast(frame)
                if (!writeInProgress) {
                    writeInProgress = true
                    mainHandler.post { processNextWrite() }
                }
            }
        } catch (t: Throwable) {
            Log.e("MotoBLEManager", "Caught unhandled exception in enqueueWriteFrame", t)
            mainHandler.post {
                onConnectionError?.invoke("Errore BLE: ${t.localizedMessage ?: t.javaClass.simpleName}")
            }
        }
    }

    fun enqueueSendFramesTask(frames: Array<ByteArray>) {
        try {
            synchronized(writeQueue) {
                for (frame in frames) {
                    writeQueue.addLast(frame)
                }
                if (!writeInProgress) {
                    writeInProgress = true
                    mainHandler.post { processNextWrite() }
                }
            }
        } catch (t: Throwable) {
            Log.e("MotoBLEManager", "Caught unhandled exception in enqueueSendFramesTask", t)
            mainHandler.post {
                onConnectionError?.invoke("Errore BLE: ${t.localizedMessage ?: t.javaClass.simpleName}")
            }
        }
    }

    private fun processNextWrite() {
        try {
            val frame: ByteArray
            synchronized(writeQueue) {
                val item = if (writeQueue.isNotEmpty()) writeQueue.removeFirst() else null
                if (item == null) {
                    writeInProgress = false
                    return
                }
                frame = item
            }

            val gatt = bluetoothGatt ?: run {
                synchronized(writeQueue) { writeInProgress = false }
                return
            }
            val characteristic = writeCharacteristic ?: run {
                synchronized(writeQueue) { writeInProgress = false }
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(characteristic, frame, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = frame
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(characteristic)
            }
        } catch (t: Throwable) {
            synchronized(writeQueue) { writeInProgress = false }
            Log.e("MotoBLEManager", "Caught unhandled exception in processNextWrite", t)
            mainHandler.post {
                onConnectionError?.invoke("Errore BLE: ${t.localizedMessage ?: t.javaClass.simpleName}")
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            try {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        connectedDevice = gatt.device
                        handshakeState = HandshakeState.IDLE
                        val bondState = gatt.device.bondState
                        if (bondState == BluetoothDevice.BOND_BONDING) {
                            val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                context.registerReceiver(bondReceiver, filter, Context.RECEIVER_EXPORTED)
                            } else {
                                context.registerReceiver(bondReceiver, filter)
                            }
                        } else if (bondState != BluetoothDevice.BOND_BONDED) {
                            val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                context.registerReceiver(bondReceiver, filter, Context.RECEIVER_EXPORTED)
                            } else {
                                context.registerReceiver(bondReceiver, filter)
                            }
                            gatt.device.createBond()
                        } else {
                            mainHandler.postDelayed({
                                try {
                                    enqueueOperation {
                                        gatt.discoverServices()
                                    }
                                } catch (t: Throwable) {
                                    Log.e("MotoBLEManager", "Caught unhandled exception in discoverServices runnable", t)
                                    mainHandler.post {
                                        onConnectionError?.invoke("Errore BLE: ${t.localizedMessage ?: t.javaClass.simpleName}")
                                    }
                                }
                            }, 300)
                        }
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        stopHeartbeat()
                        mainHandler.removeCallbacks(cccdTimeoutRunnable)
                        cccdWritePending = false
                        cccdRetryCount = 0
                        handshakeState = HandshakeState.IDLE
                        mainHandler.post { onConnectionStateChange?.invoke("Non connesso") }
                        if (status != BluetoothGatt.GATT_SUCCESS) {
                            mainHandler.post { onConnectionError?.invoke("Errore di connessione GATT ($status)") }
                        }
                        writeCharacteristic = null
                        readCharacteristic = null
                        connectedDevice = null
                        operationQueue.clear()
                        operationInProgress = false
                        synchronized(writeQueue) {
                            writeQueue.clear()
                            writeInProgress = false
                        }
                        try {
                            gatt.disconnect()
                        } catch (_: Exception) {}
                        try {
                            gatt.close()
                        } catch (_: Exception) {}
                        if (bluetoothGatt == gatt) {
                            bluetoothGatt = null
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.e("MotoBLEManager", "Caught unhandled exception in gattCallback.onConnectionStateChange", t)
                mainHandler.post {
                    onConnectionError?.invoke("Errore BLE: ${t.localizedMessage ?: t.javaClass.simpleName}")
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            try {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    val service = gatt.getService(serviceUuid)
                    if (service != null) {
                        writeCharacteristic = service.getCharacteristic(phoneToDeviceUuid)
                        readCharacteristic = service.getCharacteristic(deviceToPhoneUuid)

                        mainHandler.postDelayed({
                            try {
                                enqueueOperation {
                                    gatt.requestMtu(247)
                                }
                            } catch (t: Throwable) {
                                Log.e("MotoBLEManager", "Caught unhandled exception in requestMtu runnable", t)
                                mainHandler.post {
                                    onConnectionError?.invoke("Errore BLE: ${t.localizedMessage ?: t.javaClass.simpleName}")
                                }
                            }
                        }, 300)
                    } else {
                        mainHandler.post { onConnectionError?.invoke("Errore: Servizio non trovato") }
                    }
                } else {
                    mainHandler.post { onConnectionError?.invoke("Errore discovery servizi GATT ($status)") }
                }
                completeOperation()
            } catch (t: Throwable) {
                Log.e("MotoBLEManager", "Caught unhandled exception in gattCallback.onServicesDiscovered", t)
                mainHandler.post {
                    onConnectionError?.invoke("Errore BLE: ${t.localizedMessage ?: t.javaClass.simpleName}")
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            try {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    val codec = bleCodec
                    if (codecPtr != 0L && codec != null) {
                        try {
                            codec.nativeSetMaximumFrameSize(codecPtr, mtu.coerceAtMost(247))
                        } catch (t: Throwable) {
                            Log.e("MotoBLEManager", "Caught unhandled exception in nativeSetMaximumFrameSize JNI", t)
                        }
                    }
                }
                enqueueOperation {
                    mainHandler.postDelayed({
                        try {
                            startCccdWrite(gatt)
                        } catch (t: Throwable) {
                            Log.e("MotoBLEManager", "Caught unhandled exception in startCccdWrite runnable", t)
                            mainHandler.post {
                                onConnectionError?.invoke("Errore BLE: ${t.localizedMessage ?: t.javaClass.simpleName}")
                            }
                        }
                    }, 300)
                }
                completeOperation()
            } catch (t: Throwable) {
                Log.e("MotoBLEManager", "Caught unhandled exception in gattCallback.onMtuChanged", t)
                mainHandler.post {
                    onConnectionError?.invoke("Errore BLE: ${t.localizedMessage ?: t.javaClass.simpleName}")
                }
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            try {
                if (descriptor.uuid == cccdUuid) {
                    mainHandler.removeCallbacks(cccdTimeoutRunnable)
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        cccdWritePending = false
                        cccdRetryCount = 0
                        // Initiate 4-way handshake step 1: Send PhoneStarting
                        currentSessionId = (System.currentTimeMillis() % 0x7FFFFFFF).coerceAtLeast(1)
                        handshakeState = HandshakeState.STARTING_SENT
                        val codec = bleCodec
                        if (codecPtr != 0L && codec != null) {
                            try {
                                codec.nativeResetInboundState(codecPtr)
                                val frames = codec.nativeEncodePhoneStarting(codecPtr, currentSessionId)
                                if (frames != null) {
                                    enqueueSendFramesTask(frames)
                                }
                            } catch (t: Throwable) {
                                Log.e("MotoBLEManager", "Caught unhandled exception in nativeEncodePhoneStarting JNI", t)
                            }
                        } else {
                            Log.w("MotoBLEManager", "JNI codec unavailable, skipping nativeEncodePhoneStarting")
                        }
                        startHeartbeat()
                    } else if (status == 15 /* GATT_INSUFFICIENT_ENCRYPTION */) {
                        connectedDevice?.createBond()
                        retryCccdWrite()
                        return
                    } else {
                        retryCccdWrite()
                        return
                    }
                }
                completeOperation()
            } catch (t: Throwable) {
                Log.e("MotoBLEManager", "Caught unhandled exception in gattCallback.onDescriptorWrite", t)
                mainHandler.post {
                    onConnectionError?.invoke("Errore BLE: ${t.localizedMessage ?: t.javaClass.simpleName}")
                }
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            try {
                if (status == 15 /* GATT_INSUFFICIENT_ENCRYPTION */) {
                    connectedDevice?.createBond()
                }
                mainHandler.postDelayed({
                    try {
                        processNextWrite()
                    } catch (t: Throwable) {
                        Log.e("MotoBLEManager", "Caught unhandled exception in processNextWrite runnable", t)
                        mainHandler.post {
                            onConnectionError?.invoke("Errore BLE: ${t.localizedMessage ?: t.javaClass.simpleName}")
                        }
                    }
                }, 18)
            } catch (t: Throwable) {
                Log.e("MotoBLEManager", "Caught unhandled exception in gattCallback.onCharacteristicWrite", t)
                mainHandler.post {
                    onConnectionError?.invoke("Errore BLE: ${t.localizedMessage ?: t.javaClass.simpleName}")
                }
            }
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            try {
                @Suppress("DEPRECATION")
                val data = characteristic.value ?: return
                handleInboundFrame(data)
            } catch (t: Throwable) {
                Log.e("MotoBLEManager", "Caught unhandled exception in gattCallback.onCharacteristicChanged", t)
                mainHandler.post {
                    onConnectionError?.invoke("Errore BLE: ${t.localizedMessage ?: t.javaClass.simpleName}")
                }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            try {
                handleInboundFrame(value)
            } catch (t: Throwable) {
                Log.e("MotoBLEManager", "Caught unhandled exception in gattCallback.onCharacteristicChanged", t)
                mainHandler.post {
                    onConnectionError?.invoke("Errore BLE: ${t.localizedMessage ?: t.javaClass.simpleName}")
                }
            }
        }
    }

    private fun doWriteDescriptor(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val resCode = gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                resCode == android.bluetooth.BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(descriptor)
            }
        } catch (t: Throwable) {
            Log.e("MotoBLEManager", "Caught unhandled exception in doWriteDescriptor", t)
            mainHandler.post {
                onConnectionError?.invoke("Errore BLE: ${t.localizedMessage ?: t.javaClass.simpleName}")
            }
            false
        }
    }

    private fun retryCccdWrite() {
        try {
            mainHandler.removeCallbacks(cccdTimeoutRunnable)
            if (cccdRetryCount < 5) {
                cccdRetryCount++
                mainHandler.postDelayed({
                    try {
                        val gatt = bluetoothGatt
                        if (gatt != null && cccdWritePending) {
                            performCccdWrite(gatt)
                        }
                    } catch (t: Throwable) {
                        Log.e("MotoBLEManager", "Caught unhandled exception in retryCccdWrite runnable", t)
                        mainHandler.post {
                            onConnectionError?.invoke("Errore BLE: ${t.localizedMessage ?: t.javaClass.simpleName}")
                        }
                    }
                }, 300)
            } else {
                Log.e("MotoBLEManager", "CCCD write failed after 5 retries")
                cccdWritePending = false
                mainHandler.post { onConnectionError?.invoke("Errore abilitazione notifiche CCCD") }
                completeOperation()
            }
        } catch (t: Throwable) {
            Log.e("MotoBLEManager", "Caught unhandled exception in retryCccdWrite", t)
            mainHandler.post {
                onConnectionError?.invoke("Errore BLE: ${t.localizedMessage ?: t.javaClass.simpleName}")
            }
        }
    }

    private fun performCccdWrite(gatt: BluetoothGatt) {
        try {
            val characteristic = readCharacteristic ?: run {
                cccdWritePending = false
                completeOperation()
                return
            }
            gatt.setCharacteristicNotification(characteristic, true)
            val descriptor = characteristic.getDescriptor(cccdUuid) ?: run {
                cccdWritePending = false
                completeOperation()
                return
            }

            if (gatt.device.bondState == BluetoothDevice.BOND_BONDING) {
                mainHandler.postDelayed({
                    try {
                        if (cccdWritePending) performCccdWrite(gatt)
                    } catch (t: Throwable) {
                        Log.e("MotoBLEManager", "Caught unhandled exception in performCccdWrite runnable", t)
                        mainHandler.post {
                            onConnectionError?.invoke("Errore BLE: ${t.localizedMessage ?: t.javaClass.simpleName}")
                        }
                    }
                }, 300)
                return
            }

            val success = doWriteDescriptor(gatt, descriptor)
            if (!success) {
                Log.w("MotoBLEManager", "writeDescriptor returned false/error, retrying CCCD write")
                retryCccdWrite()
            } else {
                mainHandler.removeCallbacks(cccdTimeoutRunnable)
                mainHandler.postDelayed(cccdTimeoutRunnable, 600)
            }
        } catch (t: Throwable) {
            Log.e("MotoBLEManager", "Caught unhandled exception in performCccdWrite", t)
            mainHandler.post {
                onConnectionError?.invoke("Errore BLE: ${t.localizedMessage ?: t.javaClass.simpleName}")
            }
        }
    }

    private fun startCccdWrite(gatt: BluetoothGatt) {
        try {
            cccdRetryCount = 0
            cccdWritePending = true
            performCccdWrite(gatt)
        } catch (t: Throwable) {
            Log.e("MotoBLEManager", "Caught unhandled exception in startCccdWrite", t)
            mainHandler.post {
                onConnectionError?.invoke("Errore BLE: ${t.localizedMessage ?: t.javaClass.simpleName}")
            }
        }
    }

    private fun handleInboundFrame(frame: ByteArray) {
        try {
            val codec = bleCodec
            if (codecPtr == 0L || codec == null) {
                Log.w("MotoBLEManager", "JNI codec unavailable, skipping inbound frame parsing")
                return
            }
            val inboundMessage = try {
                codec.nativePushDeviceFrame(codecPtr, frame, System.currentTimeMillis())
            } catch (t: Throwable) {
                Log.e("MotoBLEManager", "Caught unhandled exception in nativePushDeviceFrame JNI", t)
                null
            } ?: return

            if (inboundMessage.complete) {
                val status = inboundMessage.connStatus
                if (status != null && status.role == 2 /* Device */ && status.state == 1 /* Ready */) {
                    if (handshakeState == HandshakeState.STARTING_SENT) {
                        handshakeState = HandshakeState.READY_SENT
                        if (codecPtr != 0L && bleCodec != null) {
                            val frames = try {
                                bleCodec?.nativeEncodePhoneReady(codecPtr, currentSessionId)
                            } catch (t: Throwable) {
                                Log.e("MotoBLEManager", "Caught unhandled exception in nativeEncodePhoneReady JNI", t)
                                null
                            }
                            if (frames != null) {
                                enqueueSendFramesTask(frames)
                            }
                        }
                    } else if (handshakeState == HandshakeState.READY_SENT) {
                        handshakeState = HandshakeState.COMPLETED
                        mainHandler.post { onConnectionStateChange?.invoke("Connesso") }
                        startHeartbeat()
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e("MotoBLEManager", "Caught unhandled exception in handleInboundFrame", t)
            mainHandler.post {
                onConnectionError?.invoke("Errore BLE: ${t.localizedMessage ?: t.javaClass.simpleName}")
            }
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            try {
                result?.device?.let { device ->
                    mainHandler.post { onConnectionStateChange?.invoke("Connessione in corso...") }

                    stopScan()

                    connectToDevice(device)
                }
            } catch (t: Throwable) {
                Log.e("MotoBLEManager", "Caught unhandled exception in scanCallback.onScanResult", t)
                mainHandler.post {
                    onConnectionError?.invoke("Errore BLE: ${t.localizedMessage ?: t.javaClass.simpleName}")
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            try {
                isScanning = false
                mainHandler.post {
                    onConnectionStateChange?.invoke("Non connesso")
                    onConnectionError?.invoke("Errore di scansione ($errorCode)")
                }
            } catch (t: Throwable) {
                Log.e("MotoBLEManager", "Caught unhandled exception in scanCallback.onScanFailed", t)
                mainHandler.post {
                    onConnectionError?.invoke("Errore BLE: ${t.localizedMessage ?: t.javaClass.simpleName}")
                }
            }
        }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        try {
            bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (t: Throwable) {
            Log.e("MotoBLEManager", "Caught unhandled exception in connectToDevice", t)
            mainHandler.post {
                onConnectionError?.invoke("Errore BLE: ${t.localizedMessage ?: t.javaClass.simpleName}")
            }
        }
    }

    fun stopScan() {
        try {
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            bluetoothManager?.adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (t: Throwable) {
            Log.e("MotoBLEManager", "Caught unhandled exception in stopScan", t)
            mainHandler.post {
                onConnectionError?.invoke("Errore BLE: ${t.localizedMessage ?: t.javaClass.simpleName}")
            }
        } finally {
            isScanning = false
        }
    }

    fun startScan() {
        try {
            stopScan()
            onConnectionStateChange?.invoke("Ricerca in corso...")
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val adapter = bluetoothManager.adapter ?: return
            val scanner = adapter.bluetoothLeScanner ?: return

            val filter = ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(serviceUuid))
                .build()

            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            try {
                isScanning = true
                scanner.startScan(listOf(filter), settings, scanCallback)
            } catch (e: Exception) {
                isScanning = false
                Log.e("MotoBLEManager", "Failed to start BLE scan: ${e.message}")
                onConnectionStateChange?.invoke("Non connesso")
                onConnectionError?.invoke("Impossibile avviare la scansione BLE")
            }
        } catch (t: Throwable) {
            isScanning = false
            Log.e("MotoBLEManager", "Caught unhandled exception in startScan", t)
            mainHandler.post {
                onConnectionError?.invoke("Errore BLE: ${t.localizedMessage ?: t.javaClass.simpleName}")
            }
        }
    }

    private fun startHeartbeat() {
        try {
            stopHeartbeat()
            heartbeatScheduler = Executors.newSingleThreadScheduledExecutor()
            heartbeatScheduler?.scheduleAtFixedRate({
                try {
                    val codec = bleCodec
                    if (codecPtr != 0L && codec != null && currentSessionId != 0L) {
                        val frames = try {
                            codec.nativeEncodeHeartbeat(codecPtr, currentSessionId, System.currentTimeMillis() % Int.MAX_VALUE)
                        } catch (t: Throwable) {
                            Log.e("MotoBLEManager", "Caught unhandled exception in nativeEncodeHeartbeat JNI", t)
                            null
                        }
                        if (frames != null) {
                            enqueueSendFramesTask(frames)
                        }
                    }
                } catch (t: Throwable) {
                    Log.w("MotoBLEManager", "Heartbeat send failed: ${t.message}")
                }
            }, 1000, 1000, TimeUnit.MILLISECONDS)
        } catch (t: Throwable) {
            Log.e("MotoBLEManager", "Caught unhandled exception in startHeartbeat", t)
            mainHandler.post {
                onConnectionError?.invoke("Errore BLE: ${t.localizedMessage ?: t.javaClass.simpleName}")
            }
        }
    }

    private fun stopHeartbeat() {
        try {
            heartbeatScheduler?.shutdownNow()
            heartbeatScheduler = null
        } catch (t: Throwable) {
            Log.e("MotoBLEManager", "Caught unhandled exception in stopHeartbeat", t)
            mainHandler.post {
                onConnectionError?.invoke("Errore BLE: ${t.localizedMessage ?: t.javaClass.simpleName}")
            }
        }
    }

    fun disconnect() {
        try {
            stopHeartbeat()
            stopScan()
            bluetoothGatt?.let { gatt ->
                try {
                    gatt.disconnect()
                } catch (_: Exception) {}
                try {
                    gatt.close()
                } catch (_: Exception) {}
            }
            bluetoothGatt = null
        } catch (t: Throwable) {
            Log.e("MotoBLEManager", "Caught unhandled exception in disconnect", t)
            mainHandler.post {
                onConnectionError?.invoke("Errore BLE: ${t.localizedMessage ?: t.javaClass.simpleName}")
            }
        }
    }
}
