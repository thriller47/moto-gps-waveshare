package com.motogps.android.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.motogps.android.jni.MotoBLEMediaState
import com.motogps.android.jni.MotoBLENavigationSnapshot
import com.motogps.android.jni.MotoBLEProtocolCodec
import java.util.Queue
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

@SuppressLint("MissingPermission")
class MotoBLEManager(private val context: Context) {

    companion object {
        private const val TAG = "MotoBLEManager"

        val SERVICE_UUID: UUID = UUID.fromString("7e57a000-b50c-4b6a-9c57-40a54e8e1000")
        val TX_CHAR_UUID: UUID = UUID.fromString("7e57a001-b50c-4b6a-9c57-40a54e8e1000")
        val RX_CHAR_UUID: UUID = UUID.fromString("7e57a002-b50c-4b6a-9c57-40a54e8e1000")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val GATT_INSUFFICIENT_ENCRYPTION = 15
        private const val TARGET_MTU = 247
    }

    private var bluetoothGatt: BluetoothGatt? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null

    private val codec = MotoBLEProtocolCodec(182)
    private var sessionId: Long = 0L

    private val mainHandler = Handler(Looper.getMainLooper())
    private var heartbeatRunnable: Runnable? = null
    private var isConnected = false

    private val writeQueue: Queue<ByteArray> = ConcurrentLinkedQueue()
    @Volatile
    private var isWriting = false

    private var bondReceiver: BroadcastReceiver? = null

    interface Listener {
        fun onConnected()
        fun onDisconnected()
        fun onError(message: String)
    }

    var listener: Listener? = null

    fun connect(device: BluetoothDevice) {
        disconnect()
        Log.i(TAG, "Connecting to device: ${device.address}, bond state: ${device.bondState}")

        bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        stopHeartbeat()
        unregisterBondReceiver()
        writeQueue.clear()
        isWriting = false
        bluetoothGatt?.let { gatt ->
            gatt.disconnect()
            gatt.close()
        }
        bluetoothGatt = null
        txCharacteristic = null
        rxCharacteristic = null
        isConnected = false
        sessionId = 0L
    }

    fun sendNavigationSnapshot(snapshot: MotoBLENavigationSnapshot) {
        if (!isConnected || bluetoothGatt == null) return
        val frames = codec.encodeNavigationSnapshot(snapshot)
        sendFrames(frames)
    }

    fun sendMediaState(state: MotoBLEMediaState) {
        if (!isConnected || bluetoothGatt == null) return
        val frames = codec.encodeMediaState(state)
        sendFrames(frames)
    }

    private fun sendFrames(frames: Array<ByteArray>) {
        if (frames.isEmpty()) return
        for (frame in frames) {
            writeQueue.add(frame)
        }
        mainHandler.post { processWriteQueue() }
    }

    @Synchronized
    private fun processWriteQueue() {
        if (isWriting) return
        val nextFrame = writeQueue.peek() ?: return
        val gatt = bluetoothGatt ?: return
        val tx = txCharacteristic ?: return

        isWriting = true
        tx.value = nextFrame
        val success = gatt.writeCharacteristic(tx)
        if (success) {
            writeQueue.poll()
        } else {
            Log.w(TAG, "writeCharacteristic returned false, retrying frame later")
            isWriting = false
            mainHandler.postDelayed({ processWriteQueue() }, 50)
        }
    }

    private fun registerBondReceiver(device: BluetoothDevice, onBonded: () -> Unit) {
        unregisterBondReceiver()
        bondReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == BluetoothDevice.ACTION_BOND_STATE_CHANGED) {
                    val bondedDevice = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                    if (bondedDevice?.address == device.address) {
                        Log.i(TAG, "Bond state changed for ${device.address}: $bondState")
                        if (bondState == BluetoothDevice.BOND_BONDED) {
                            unregisterBondReceiver()
                            onBonded()
                        } else if (bondState == BluetoothDevice.BOND_NONE) {
                            unregisterBondReceiver()
                            Log.e(TAG, "Pairing failed for ${device.address}")
                            listener?.onError("Bonding failed")
                        }
                    }
                }
            }
        }
        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        context.registerReceiver(bondReceiver, filter)
    }

    private fun unregisterBondReceiver() {
        bondReceiver?.let { receiver ->
            try {
                context.unregisterReceiver(receiver)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to unregister bond receiver: ${e.message}")
            }
        }
        bondReceiver = null
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.i(TAG, "onConnectionStateChange status=$status newState=$newState")

            if (status == GATT_INSUFFICIENT_ENCRYPTION || status == 137) {
                Log.w(TAG, "Insufficient encryption status $status on connection change, initiating bond")
                if (gatt.device.bondState != BluetoothDevice.BOND_BONDED) {
                    registerBondReceiver(gatt.device) {
                        gatt.discoverServices()
                    }
                    gatt.device.createBond()
                } else {
                    mainHandler.postDelayed({ gatt.discoverServices() }, 300)
                }
                return
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "Connected to GATT server. Checking bond state: ${gatt.device.bondState}")
                if (gatt.device.bondState != BluetoothDevice.BOND_BONDED) {
                    Log.i(TAG, "Not bonded. Creating bond before service discovery...")
                    registerBondReceiver(gatt.device) {
                        Log.i(TAG, "Bond completed. Proceeding to service discovery.")
                        gatt.discoverServices()
                    }
                    if (!gatt.device.createBond()) {
                        Log.e(TAG, "createBond failed immediately")
                        listener?.onError("Failed to initiate bonding")
                    }
                } else {
                    Log.i(TAG, "Device already bonded. Discovering services...")
                    gatt.discoverServices()
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "Disconnected from GATT server.")
                disconnect()
                listener?.onDisconnected()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            Log.i(TAG, "onServicesDiscovered status=$status")
            if (status == GATT_INSUFFICIENT_ENCRYPTION || status == 15) {
                Log.w(TAG, "GATT_INSUFFICIENT_ENCRYPTION during service discovery. Initiating bonding or retry.")
                if (gatt.device.bondState != BluetoothDevice.BOND_BONDED) {
                    registerBondReceiver(gatt.device) {
                        gatt.discoverServices()
                    }
                    gatt.device.createBond()
                } else {
                    mainHandler.postDelayed({ gatt.discoverServices() }, 500)
                }
                return
            }

            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(SERVICE_UUID)
                if (service == null) {
                    Log.e(TAG, "Navigation BLE service $SERVICE_UUID not found")
                    listener?.onError("Service not found")
                    return
                }

                txCharacteristic = service.getCharacteristic(TX_CHAR_UUID)
                rxCharacteristic = service.getCharacteristic(RX_CHAR_UUID)

                if (txCharacteristic == null || rxCharacteristic == null) {
                    Log.e(TAG, "TX/RX characteristics not found")
                    listener?.onError("Characteristics missing")
                    return
                }

                Log.i(TAG, "Requesting MTU $TARGET_MTU...")
                gatt.requestMtu(TARGET_MTU)
            } else {
                Log.e(TAG, "onServicesDiscovered failed with status $status")
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.i(TAG, "onMtuChanged mtu=$mtu status=$status")
            val frameSize = (mtu - 3).coerceIn(20, 244)
            codec.setMaximumFrameSize(frameSize)

            enableTxNotifications(gatt)
        }

        private fun enableTxNotifications(gatt: BluetoothGatt) {
            val rx = rxCharacteristic ?: return
            gatt.setCharacteristicNotification(rx, true)

            val cccd = rx.getDescriptor(CCCD_UUID)
            if (cccd != null) {
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                val success = gatt.writeDescriptor(cccd)
                Log.i(TAG, "Writing CCCD descriptor success=$success")
            } else {
                Log.e(TAG, "CCCD descriptor $CCCD_UUID not found")
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            Log.i(TAG, "onDescriptorWrite uuid=${descriptor.uuid} status=$status")
            if (descriptor.uuid == CCCD_UUID && status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "GATT notification enabled on CCCD. Starting session handshake...")
                startSessionHandshake()
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (characteristic.uuid == TX_CHAR_UUID) {
                isWriting = false
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.w(TAG, "onCharacteristicWrite failed with status $status")
                }
                mainHandler.post { processWriteQueue() }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == RX_CHAR_UUID) {
                val data = characteristic.value ?: return
                val message = codec.pushDeviceFrame(data, System.currentTimeMillis())
                if (message != null && message.complete) {
                    Log.d(TAG, "Inbound device frame complete sequence=${message.sequence}")
                }
            }
        }
    }

    private fun startSessionHandshake() {
        sessionId = (System.currentTimeMillis() and 0x7FFFFFFF)
        if (sessionId == 0L) sessionId = 1L

        val startingFrames = codec.encodePhoneStarting(sessionId)
        sendFrames(startingFrames)

        val readyFrames = codec.encodePhoneReady(sessionId)
        sendFrames(readyFrames)

        isConnected = true
        listener?.onConnected()

        startHeartbeat()
    }

    private fun startHeartbeat() {
        stopHeartbeat()
        heartbeatRunnable = object : Runnable {
            override fun run() {
                if (isConnected && sessionId != 0L) {
                    val hbFrames = codec.encodeHeartbeat(sessionId, SystemClock.elapsedRealtime())
                    sendFrames(hbFrames)
                    mainHandler.postDelayed(this, 1000)
                }
            }
        }
        mainHandler.postDelayed(heartbeatRunnable!!, 1000)
    }

    private fun stopHeartbeat() {
        heartbeatRunnable?.let { mainHandler.removeCallbacks(it) }
        heartbeatRunnable = null
    }
}
