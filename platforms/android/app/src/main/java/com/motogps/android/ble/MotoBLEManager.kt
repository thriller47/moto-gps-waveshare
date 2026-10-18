package com.motogps.android.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.os.Build
import com.motogps.android.jni.MotoBLEProtocolCodec
import java.util.LinkedList
import java.util.Queue
import java.util.UUID
import kotlin.math.min

class MotoBLEManager(private val context: Context, private val device: BluetoothDevice) {
    private var bluetoothGatt: BluetoothGatt? = null
    private val codec = MotoBLEProtocolCodec(20) // Default MTU - 3
    private var sessionID: Long = 0
    private var isConnected = false
    private var isReady = false
    private var activeSessionStartTimeMs: Long = 0

    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null

    private val writeQueue: Queue<ByteArray> = LinkedList()
    private var isWriting = false
    private val handler = Handler(Looper.getMainLooper())

    var stateChangeListener: ((String) -> Unit)? = null

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    isConnected = true
                    stateChangeListener?.invoke("Connected. Discovering Services...")
                    gatt.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    handleDisconnect(gatt)
                }
            } else {
                handleDisconnect(gatt)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(UUID.fromString(MotoBLEProtocolCodec.SERVICE_UUID_STRING))
                if (service != null) {
                    rxCharacteristic = service.getCharacteristic(UUID.fromString(MotoBLEProtocolCodec.PHONE_TO_DEVICE_UUID_STRING))
                    txCharacteristic = service.getCharacteristic(UUID.fromString(MotoBLEProtocolCodec.DEVICE_TO_PHONE_UUID_STRING))

                    if (txCharacteristic != null) {
                        stateChangeListener?.invoke("Subscribing to TX...")
                        gatt.setCharacteristicNotification(txCharacteristic, true)
                        val cccd = txCharacteristic!!.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                        if (cccd != null) {
                            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            gatt.writeDescriptor(cccd)
                        }
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                stateChangeListener?.invoke("Requesting MTU...")
                gatt.requestMtu(512)
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val maxFrameSize = min(mtu - 3, 512)
                codec.setMaximumFrameSize(maxFrameSize)

                stateChangeListener?.invoke("MTU Negotated. Handshaking...")
                sessionID = (Math.random() * Long.MAX_VALUE).toLong()
                activeSessionStartTimeMs = System.currentTimeMillis()
                codec.resetInboundState()

                val startingFrames = codec.encodePhoneStarting(sessionID)
                enqueueWrites(startingFrames)
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            isWriting = false
            processNextWrite()
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == txCharacteristic?.uuid) {
                val value = characteristic.value
                val message = codec.pushDeviceFrame(value, System.currentTimeMillis())
                if (message?.complete == true) {
                    val connStatus = message.connectionStatus
                    if (connStatus != null && connStatus.role == 1 /* Device */ && connStatus.state == 2 /* Ready */) {
                        if (!isReady) {
                            isReady = true
                            stateChangeListener?.invoke("Connected (Ready)")
                            val readyFrames = codec.encodePhoneReady(sessionID)
                            enqueueWrites(readyFrames)
                        }
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleDisconnect(gatt: BluetoothGatt) {
        isConnected = false
        isReady = false
        writeQueue.clear()
        isWriting = false
        stateChangeListener?.invoke("Disconnected (Queue Cleared)")
        gatt.close()
        bluetoothGatt = null
    }

    @SuppressLint("MissingPermission")
    fun connect() {
        stateChangeListener?.invoke("Connecting...")
        bluetoothGatt = device.connectGatt(context, false, gattCallback)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        bluetoothGatt?.disconnect()
    }

    fun enqueueWrites(frames: List<ByteArray>) {
        handler.post {
            for (frame in frames) {
                writeQueue.offer(frame)
            }
            processNextWrite()
        }
    }

    @SuppressLint("MissingPermission")
    private fun processNextWrite() {
        if (isWriting || writeQueue.isEmpty() || bluetoothGatt == null || rxCharacteristic == null) {
            return
        }

        val frame = writeQueue.poll() ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            bluetoothGatt!!.writeCharacteristic(rxCharacteristic!!, frame, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
            isWriting = true
        } else {
            rxCharacteristic!!.value = frame
            rxCharacteristic!!.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            isWriting = bluetoothGatt!!.writeCharacteristic(rxCharacteristic)
        }
    }

    fun sendHeartbeat() {
        if (!isReady) return
        val elapsedMs = System.currentTimeMillis() - activeSessionStartTimeMs
        val frames = codec.encodeHeartbeat(sessionID, elapsedMs)
        enqueueWrites(frames)
    }
}
