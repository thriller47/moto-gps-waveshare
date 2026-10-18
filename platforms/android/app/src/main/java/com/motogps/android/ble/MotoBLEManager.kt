package com.motogps.android.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import java.util.UUID

@SuppressLint("MissingPermission")
class MotoBLEManager(private val context: Context) {
    val serviceUuid = UUID.fromString("7e57a000-b50c-4b6a-9c57-40a54e8e1000")
    val phoneToDeviceUuid = UUID.fromString("7e57a001-b50c-4b6a-9c57-40a54e8e1000")

    var onConnectionStateChange: ((String) -> Unit)? = null
    var onConnectionError: ((String) -> Unit)? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    mainHandler.post { onConnectionStateChange?.invoke("Connesso") }
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    mainHandler.post { onConnectionStateChange?.invoke("Non connesso") }
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        mainHandler.post { onConnectionError?.invoke("Errore di connessione GATT ($status)") }
                    }
                    writeCharacteristic = null
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
                }
            } else {
                mainHandler.post { onConnectionError?.invoke("Errore discovery servizi GATT ($status)") }
            }
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.device?.let { device ->
                mainHandler.post { onConnectionStateChange?.invoke("Connessione in corso...") }
                connectToDevice(device)

                val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                bluetoothManager.adapter?.bluetoothLeScanner?.stopScan(this)
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

    fun sendFrames(frames: Array<ByteArray>) {
        val gatt = bluetoothGatt ?: return
        val characteristic = writeCharacteristic ?: return

        // Execute writing on a background thread to prevent Main/UI blocking
        Thread {
            for (frame in frames) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeCharacteristic(characteristic, frame, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                } else {
                    @Suppress("DEPRECATION")
                    characteristic.value = frame
                    characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    @Suppress("DEPRECATION")
                    gatt.writeCharacteristic(characteristic)
                }
                Thread.sleep(10)
            }
        }.start()
    }

    fun disconnect() {
        bluetoothGatt?.disconnect()
    }
}
