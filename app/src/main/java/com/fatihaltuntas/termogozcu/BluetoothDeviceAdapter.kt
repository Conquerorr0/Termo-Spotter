package com.fatihaltuntas.termogozcu

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.ImageView
import androidx.core.app.ActivityCompat

class BluetoothDeviceAdapter(
    context: Context,
    private val devices: ArrayList<BluetoothDevice>
) : ArrayAdapter<BluetoothDevice>(context, R.layout.item_bluetooth_device, devices) {

    private val TAG = "BluetoothDeviceAdapter"

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context)
            .inflate(R.layout.item_bluetooth_device, parent, false)
        
        val device = devices[position]
        
        val deviceNameTextView = view.findViewById<TextView>(R.id.deviceName)
        val deviceAddressTextView = view.findViewById<TextView>(R.id.deviceAddress)
        val deviceIconImageView = view.findViewById<ImageView>(R.id.deviceIcon)
        
        // Bluetooth izinlerini kontrol et
        if (hasBluetoothPermission()) {
            try {
                deviceNameTextView.text = device.name ?: "Bilinmeyen Cihaz"
                deviceAddressTextView.text = device.address
            } catch (e: SecurityException) {
                Log.e(TAG, "Bluetooth cihaz bilgisi erişim hatası: ${e.message}")
                deviceNameTextView.text = "Bilinmeyen Cihaz"
                deviceAddressTextView.text = "Erişim Hatası"
            }
        } else {
            deviceNameTextView.text = "İzin Gerekli"
            deviceAddressTextView.text = "Bluetooth izni verilmedi"
        }
        
        return view
    }

    private fun hasBluetoothPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
    }
} 