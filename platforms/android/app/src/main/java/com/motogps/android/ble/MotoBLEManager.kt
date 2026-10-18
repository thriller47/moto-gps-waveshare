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

    // Asynchronous Operation Queue logic for sequential GATT operations
    private val operationQueue = mutableListOf<() -> Unit>()
    private var operationInProgress = false

    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BluetoothDevice.ACTION_BOND_STATE_CHANGED) {
                val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                if (device?.address == connectedDevice?.address && bondState == BluetoothDevice.BOND_BONDED) {
                    try {
                        context?.unregisterReceiver(this)
                    } catch (_: Exception) {}
                    // Resume sequential GATT setup after bonding completes
                    enqueueOperation {
                        bluetoothGatt?.discoverServices()
                    }
                }
            }
        }
    }

    private fun enqueueOperation(operation: () -> Unit) {
        operationQueue.add(operation)
        if (!operationInProgress) {
            executeNextOperation()
        }
    }

    private fun executeNextOperation() {
        if (operationQueue.isNotEmpty()) {
            operationInProgress = true
            val operation = operationQueue.removeAt(0)
            operation()
        } else {
            operationInProgress = false
        }
    }

    private fun completeOperation() {
        operationInProgress = false
        executeNextOperation()
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectedDevice = gatt.device
                    val bondState = gatt.device.bondState
                    if (bondState == BluetoothDevice.BOND_BONDING) {
                        // Bonding in progress, wait for BOND_BONDED broadcast receiver
                        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            context.registerReceiver(bondReceiver, filter, Context.RECEIVER_EXPORTED)
                        } else {
                            context.registerReceiver(bondReceiver, filter)
                        }
                    } else if (bondState != BluetoothDevice.BOND_BONDED) {
                        // Register receiver and initiate bond
                        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            context.registerReceiver(bondReceiver, filter, Context.RECEIVER_EXPORTED)
                        } else {
                            context.registerReceiver(bondReceiver, filter)
                        }
                        gatt.device.createBond()
                    } else {
                        // Fully BOND_BONDED, start sequential configuration queue
                        enqueueOperation {
                            gatt.discoverServices()
                        }
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    stopHeartbeat()
                    mainHandler.post { onConnectionStateChange?.invoke("Non connesso") }
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        mainHandler.post { onConnectionError?.invoke("Errore di connessione GATT ($status)") }
                    }
                    writeCharacteristic = null
                    readCharacteristic = null
                    connectedDevice = null
                    operationQueue.clear()
                    operationInProgress = false
                    bluetoothGatt?.close()
                    bluetoothGatt = null
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(serviceUuid)
                if (service != null) {
                    writeCharacteristic = service.getCharacteristic(phoneToDeviceUuid)
                    readCharacteristic = service.getCharacteristic(deviceToPhoneUuid)

                    // Proceed to request MTU
                    enqueueOperation {
                        gatt.requestMtu(247) // or 512
                    }
                } else {
                    mainHandler.post { onConnectionError?.invoke("Errore: Servizio non trovato") }
                }
            } else {
                mainHandler.post { onConnectionError?.invoke("Errore discovery servizi GATT ($status)") }
            }
            completeOperation() // End discoverServices operation
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                // Proceed to enable notifications
                enqueueOperation {
                    val characteristic = readCharacteristic
                    if (characteristic != null) {
                        gatt.setCharacteristicNotification(characteristic, true)
                        val descriptor = characteristic.getDescriptor(cccdUuid)
                        if (descriptor != null) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                            } else {
                                @Suppress("DEPRECATION")
                                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                @Suppress("DEPRECATION")
                                gatt.writeDescriptor(descriptor)
                            }
                        } else {
                            completeOperation()
                        }
                    } else {
                        completeOperation()
                    }
                }
            }
            completeOperation() // End requestMtu operation
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && descriptor.uuid == cccdUuid) {
                mainHandler.post { onConnectionStateChange?.invoke("Connesso") }

                // Immediately send initial handshake (PhoneStarting) frame and start heartbeat
                enqueueOperation {
                    sendHandshakeFrames()
                    startHeartbeat()
                    completeOperation()
                }
            } else if (status == 15 /* GATT_INSUFFICIENT_ENCRYPTION */) {
                connectedDevice?.createBond()
            }
            completeOperation() // End enable notification operation
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.device?.let { device ->
                mainHandler.post { onConnectionStateChange?.invoke("Connessione in corso...") }

                val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                bluetoothManager.adapter?.bluetoothLeScanner?.stopScan(this)

                connectToDevice(device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            mainHandler.post {
                onConnectionStateChange?.invoke("Non connesso")
                onConnectionError?.invoke("Errore di scansione ($errorCode)")
            }
        }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        bluetoothGatt = device.connectGatt(context, false, gattCallback)
    }

    fun startScan() {
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

        scanner.startScan(listOf(filter), settings, scanCallback)
    }

    private fun sendHandshakeFrames() {
        val codec = MotoBLEProtocolCodec()
        val ptr = codec.nativeInit(247)
        val frames = codec.nativeEncodePhoneStarting(ptr, System.currentTimeMillis() % Int.MAX_VALUE)
        for (frame in frames) {
            internalWriteFrame(frame)
            Thread.sleep(10) // Pacing
        }
        codec.nativeDestroy(ptr)
    }

    private fun startHeartbeat() {
        stopHeartbeat()
        heartbeatScheduler = Executors.newSingleThreadScheduledExecutor()
        heartbeatScheduler?.scheduleAtFixedRate({
            try {
                sendHeartbeatFrame()
            } catch (e: Exception) {
                Log.w("MotoBLEManager", "Heartbeat write failed: ${e.message}")
            }
        }, 1000, 1000, TimeUnit.MILLISECONDS)
    }

    private fun stopHeartbeat() {
        heartbeatScheduler?.shutdownNow()
        heartbeatScheduler = null
    }

    private fun sendHeartbeatFrame() {
        val codec = MotoBLEProtocolCodec()
        val ptr = codec.nativeInit(247)
        val sessionMs = System.currentTimeMillis() % Int.MAX_VALUE
        val frames = codec.nativeEncodeHeartbeat(ptr, sessionMs, sessionMs)
        for (frame in frames) {
            internalWriteFrame(frame)
        }
        codec.nativeDestroy(ptr)
    }

    private fun internalWriteFrame(frame: ByteArray) {
        val gatt = bluetoothGatt ?: return
        val characteristic = writeCharacteristic ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(characteristic, frame, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = frame
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(characteristic)
        }
    }

    fun enqueueSendFramesTask(frames: Array<ByteArray>) {
        // Enqueue sending application data as a sequence of writes on the background queue
        enqueueOperation {
            for (frame in frames) {
                internalWriteFrame(frame)
                // Small pacing delay
                Thread.sleep(20)
            }
            completeOperation()
        }
    }

    fun disconnect() {
        bluetoothGatt?.disconnect()
    }
}
