package com.fatihaltuntas.termogozcu

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import kotlin.concurrent.thread
import android.media.RingtoneManager
import android.media.Ringtone
import android.os.Vibrator
import android.os.VibratorManager
import android.graphics.Color
import android.os.VibrationEffect
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeoutException
import java.util.concurrent.TimeUnit
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {
    private var bluetoothAdapter: BluetoothAdapter? = null
    private val TAG = "MainActivity"
    private lateinit var connectButton: Button
    private lateinit var scanButton: Button
    private lateinit var deviceListView: ListView
    private lateinit var progressBar: ProgressBar
    private lateinit var resultTextView: TextView
    private lateinit var deviceDialog: AlertDialog
    private var bluetoothSocket: BluetoothSocket? = null
    private var deviceList = mutableListOf<BluetoothDevice>()
    private lateinit var deviceAdapter: ArrayAdapter<String>
    private var connected = false
    private var connectThread: Thread? = null
    private var readThread: Thread? = null
    private val handler = Handler(Looper.getMainLooper())
    private var selectedConnectPosition = -1

    // Known HC-05 MAC addresses
    private val knownHC05Addresses = listOf(
        "00:21:13:00:A3:6B",
        "98:D3:31:F9:79:E8",
        "00:18:E4:40:00:06",
        "98:D3:71:FD:62:D4",
        "98:D3:91:FD:46:C9",
        "98:D3:34:90:8D:76",
        "3C:61:05:12:CA:62",
        "00:25:00:00:5D:46"
    )
    
    // Commands to use with HC-05
    private val TEMPERATURE_COMMANDS = listOf("T", "TEMP", "READ", "GET", "R")
    // Store the last successful command
    private var lastSuccessfulCommand = "T"
    
    private lateinit var statusTextView: TextView
    private lateinit var temperatureValueTextView: TextView
    private lateinit var statusImageView: ImageView
    private lateinit var readDataButton: Button
    
    private var inputStream: InputStream? = null
    private var mediaPlayer: MediaPlayer? = null
    private var toneGenerator: ToneGenerator? = null
    private val HC05_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB") // Standard UUID for HC-05
    
    // Variables for regular measurements
    private var autoReadEnabled = false
    private val autoReadRunnable = object : Runnable {
        override fun run() {
            if (autoReadEnabled && connected) {
                readTemperatureData()
                // Take a measurement every 5 seconds
                handler.postDelayed(this, 5000)
            }
        }
    }
    
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        var allGranted = true
        permissions.entries.forEach {
            Log.d(TAG, "Permission result: ${it.key} -> ${it.value}")
            if (!it.value) {
                allGranted = false
            }
        }
        
        if (allGranted) {
            Log.d(TAG, "All permissions granted, initializing Bluetooth")
            initializeBluetooth()
        } else {
            Toast.makeText(this, "Bluetooth and location permissions are required", Toast.LENGTH_LONG).show()
        }
    }
    
    // Receiver for finding Bluetooth devices
    private val discoveryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    if (device != null && hasBluetoothConnectPermission()) {
                        var deviceName = "No Name"
                        var deviceAddress = "Unknown"
                        
                        try {
                            deviceName = device.name ?: "No Name"
                            deviceAddress = device.address
                            
                            // Device filtering
                            val isCompatibleDevice = deviceName.contains("HC-05", ignoreCase = true) || 
                                                   deviceName.contains("HC05", ignoreCase = true) || 
                                                   deviceName.contains("HC_05", ignoreCase = true) ||
                                                   deviceName.contains("ESP32", ignoreCase = true) ||
                                                   deviceName.contains("Termo", ignoreCase = true) ||
                                                   deviceName.contains("BT05", ignoreCase = true) ||
                                                   deviceName.contains("JDY", ignoreCase = true) ||
                                                   knownHC05Addresses.contains(deviceAddress)
                            
                            Log.d(TAG, "Found Bluetooth device: $deviceName - $deviceAddress")
                            
                            if (isCompatibleDevice) {
                                Log.d(TAG, "Compatible thermometer device found: $deviceName")
                                if (!deviceList.contains(device)) {
                                    deviceList.add(device)
                                    deviceAdapter.add("$deviceName - $deviceAddress")
                                    deviceAdapter.notifyDataSetChanged()
                                }
                            } else {
                                Log.d(TAG, "Device ($deviceName) is not a compatible thermometer device")
                            }
                        } catch (e: SecurityException) {
                            Log.e(TAG, "Device info access error: ${e.message}")
                        }
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    Log.d(TAG, "Bluetooth device scanning completed")
                    runOnUiThread {
                        scanButton.isEnabled = true
                        progressBar.visibility = View.GONE
                        scanButton.text = "Scan Again"
                        
                        if (deviceList.isEmpty()) {
                            Toast.makeText(context, "No HC-05 device was found", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    // Helper functions for permission checks
    private fun hasBluetoothScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH
            ) == PackageManager.PERMISSION_GRANTED
        }
    }
    
    private fun hasBluetoothConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH
            ) == PackageManager.PERMISSION_GRANTED
        }
    }
    
    // Bluetooth adaptörünün etkin olup olmadığını güvenli şekilde kontrol et
    private fun isBluetoothEnabled(): Boolean {
        return try {
            if (hasBluetoothConnectPermission()) {
                bluetoothAdapter?.isEnabled == true
            } else {
                false
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Bluetooth durumu kontrolü izin hatası: ${e.message}")
            false
        }
    }

    // Sıcaklık geçmişi için değişkenler
    private val temperatureHistory = mutableListOf<TemperatureMeasurement>()
    private lateinit var historyAdapter: TemperatureHistoryAdapter
    private lateinit var historyRecyclerView: RecyclerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        Log.d(TAG, "onCreate başladı")
        
        statusTextView = findViewById(R.id.statusTextView)
        temperatureValueTextView = findViewById(R.id.temperatureValueTextView)
        statusImageView = findViewById(R.id.statusImageView)
        connectButton = findViewById(R.id.connectButton)
        readDataButton = findViewById(R.id.readDataButton)
        resultTextView = findViewById(R.id.resultTextView)
        
        // Sıcaklık geçmişi RecyclerView'ını ayarlayalım
        historyRecyclerView = findViewById(R.id.historyRecyclerView)
        historyAdapter = TemperatureHistoryAdapter(temperatureHistory)
        historyRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = historyAdapter
        }
        
        // Ses için ToneGenerator kullanacağız
        try {
            // ToneGenerator başlatma (ses seviyesi 80 olarak ayarlandı)
            toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, 80)
            Log.d(TAG, "ToneGenerator başarıyla oluşturuldu")
        } catch (e: Exception) {
            Log.e(TAG, "ToneGenerator hatası: ${e.message}")
            // Hata durumunda tekrar deneme
            try {
                toneGenerator = ToneGenerator(AudioManager.STREAM_RING, 80)
                Log.d(TAG, "ToneGenerator alternatif akış ile oluşturuldu")
            } catch (e: Exception) {
                Log.e(TAG, "Alternatif ToneGenerator hatası: ${e.message}")
            }
        }
        
        connectButton.setOnClickListener {
            Log.d(TAG, "Bağlan butonuna tıklandı")
            if (!connected) {
                checkPermissionsAndConnectToBluetooth()
            } else {
                disconnectBluetooth()
            }
        }
        
        readDataButton.setOnClickListener {
            readTemperatureData()
        }
        
        // Bluetooth tarama receiver'ını kaydet
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        try {
            registerReceiver(discoveryReceiver, filter)
            Log.d(TAG, "BroadcastReceiver kaydedildi")
        } catch (e: Exception) {
            Log.e(TAG, "BroadcastReceiver kayıt hatası: ${e.message}")
        }
        
        // Bluetooth cihaz seçim diyaloğunu hazırla
        createDeviceSelectionDialog()
        
        Log.d(TAG, "onCreate tamamlandı")
    }
    
    private fun createDeviceSelectionDialog() {
        val dialogView = layoutInflater.inflate(R.layout.bluetooth_device_list_dialog, null)
        
        // View ID'lerini doğru şekilde referans alalım
        val deviceListView: ListView = dialogView.findViewById(R.id.deviceListView)
        val scanButton: Button = dialogView.findViewById(R.id.scanButton)
        val progressBar: ProgressBar = dialogView.findViewById(R.id.progressBar)
        val cancelButton: Button = dialogView.findViewById(R.id.cancelButton)
        
        // Sınıf özelliklerine atayalım
        this.deviceListView = deviceListView
        this.scanButton = scanButton
        this.progressBar = progressBar
        
        deviceAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf<String>())
        deviceListView.adapter = deviceAdapter

        deviceListView.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            val device = deviceList[position]
            if (hasBluetoothConnectPermission()) {
                try {
                    val deviceName = try {
                        device.name ?: "İsimsiz"
                    } catch (e: SecurityException) {
                        Log.e(TAG, "Cihaz ismi erişim hatası: ${e.message}")
                        "İsimsiz"
                    }
                    
                    val deviceAddress = try {
                        device.address
                    } catch (e: SecurityException) {
                        Log.e(TAG, "Cihaz adresi erişim hatası: ${e.message}")
                        "Bilinmeyen"
                    }
                    
                    Log.d(TAG, "Seçilen cihaz: $deviceName - $deviceAddress")
                    connectToDevice(device)
                    deviceDialog.dismiss()
                } catch (e: SecurityException) {
                    e.printStackTrace()
                    Toast.makeText(this, "Bluetooth bağlantısı için izin gerekli!", Toast.LENGTH_SHORT).show()
                    checkPermissionsAndConnectToBluetooth()
                }
            } else {
                Toast.makeText(this, "Bluetooth bağlantı izni gerekiyor", Toast.LENGTH_SHORT).show()
                checkPermissionsAndConnectToBluetooth()
            }
        }


        scanButton.setOnClickListener {
            Log.d(TAG, "Tara butonuna tıklandı")
            deviceList.clear()
            deviceAdapter.notifyDataSetChanged()
            startDeviceDiscovery()
        }
        
        cancelButton.setOnClickListener {
            Log.d(TAG, "İptal butonuna tıklandı")
            // Taramayı durdur
            if (hasBluetoothScanPermission()) {
                try {
                    bluetoothAdapter?.cancelDiscovery()
                    Log.d(TAG, "Bluetooth taraması durduruldu")
                } catch (e: SecurityException) {
                    Log.e(TAG, "Tarama durdurma izni hatası: ${e.message}")
                }
            }
            // Dialog'u kapat
            deviceDialog.dismiss()
        }
        
        deviceDialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .setOnCancelListener {
                if (hasBluetoothScanPermission()) {
                    try {
                        bluetoothAdapter?.cancelDiscovery()
                    } catch (e: SecurityException) {
                        Log.e(TAG, "Tarama durdurma izni hatası: ${e.message}")
                    }
                }
                Log.d(TAG, "Diyalog kapatıldı, tarama durduruldu")
            }
            .create()
        
        progressBar.visibility = View.GONE
        Log.d(TAG, "Bluetooth cihaz diyaloğu hazırlandı")
    }
    
    private fun checkPermissionsAndConnectToBluetooth() {
        Log.d(TAG, "Checking permissions")
        
        val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
        
        val permissionsToRequest = ArrayList<String>()
        
        for (permission in requiredPermissions) {
            if (ContextCompat.checkSelfPermission(this, permission) 
                != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(permission)
                Log.d(TAG, "Missing permission: $permission")
            }
        }
        
        if (permissionsToRequest.isEmpty()) {
            Log.d(TAG, "All permissions already granted, initializing Bluetooth")
            initializeBluetooth()
        } else {
            Log.d(TAG, "Requesting permissions: ${permissionsToRequest.joinToString()}")
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }
    
    private fun initializeBluetooth() {
        Log.d(TAG, "Initializing Bluetooth")
        
        // Get adapter from BluetoothManager
        try {
            val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            bluetoothAdapter = bluetoothManager.adapter
            
            if (bluetoothAdapter == null) {
                Log.e(TAG, "Bluetooth adapter not found")
                Toast.makeText(this, "This device does not support Bluetooth", Toast.LENGTH_LONG).show()
                return
            }
            
            if (!isBluetoothEnabled()) {
                Log.e(TAG, "Bluetooth is turned off")
                
                if (!hasBluetoothConnectPermission()) {
                    Toast.makeText(this, "Permissions needed to turn on Bluetooth", Toast.LENGTH_LONG).show()
                    checkPermissionsAndConnectToBluetooth()
                    return
                }
                
                try {
                    val enableBluetoothIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                    startActivity(enableBluetoothIntent)
                    Toast.makeText(this, "Please turn on Bluetooth and try again", Toast.LENGTH_LONG).show()
                } catch (e: SecurityException) {
                    Log.e(TAG, "No permission to turn on Bluetooth: ${e.message}")
                    Toast.makeText(this, "No permission to turn on Bluetooth", Toast.LENGTH_LONG).show()
                }
                return
            }
            
            Log.d(TAG, "Bluetooth ready, showing device list dialog")
            // Show device list dialog
            deviceDialog.show()
            
            // Clear previous device list
            deviceList.clear()
            deviceAdapter.notifyDataSetChanged()
            
            // Start scanning after a short delay
            Handler(Looper.getMainLooper()).postDelayed({
                startDeviceDiscovery()
            }, 500)
            
        } catch (e: SecurityException) {
            Log.e(TAG, "Bluetooth permission error: ${e.message}")
            Toast.makeText(this, "No Bluetooth permission: ${e.message}", Toast.LENGTH_LONG).show()
            checkPermissionsAndConnectToBluetooth()
        } catch (e: Exception) {
            Log.e(TAG, "Bluetooth initialization error: ${e.message}")
            Toast.makeText(this, "Could not initialize Bluetooth: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    private fun startDeviceDiscovery() {
        Log.d(TAG, "Starting device scanning")
        
        if (!isBluetoothEnabled()) {
            Log.e(TAG, "Bluetooth is not on, scanning cannot be started")
            Toast.makeText(this, "Please turn on Bluetooth first", Toast.LENGTH_SHORT).show()
            scanButton.isEnabled = true
            progressBar.visibility = View.GONE
            return
        }
        
        if (!hasBluetoothScanPermission()) {
            Log.e(TAG, "No Bluetooth scanning permission")
            Toast.makeText(this, "Bluetooth scanning permission not granted. Please check permissions.", Toast.LENGTH_LONG).show()
            checkPermissionsAndConnectToBluetooth()
            scanButton.isEnabled = true
            progressBar.visibility = View.GONE
            return
        }
        
        scanButton.isEnabled = false
        progressBar.visibility = View.VISIBLE
        
        try {
            // Stop previous scanning
            if (bluetoothAdapter?.isDiscovering == true) {
                bluetoothAdapter?.cancelDiscovery()
                Thread.sleep(100) // Short wait
            }
            
            // Start scanning
            val started = bluetoothAdapter?.startDiscovery() ?: false
            
            if (started) {
                Log.d(TAG, "Bluetooth scanning started")
                Toast.makeText(this, "Scanning devices...", Toast.LENGTH_SHORT).show()
                
                // Scanning timer (30 seconds)
                Handler(Looper.getMainLooper()).postDelayed({
                    if (bluetoothAdapter?.isDiscovering == true) {
                        bluetoothAdapter?.cancelDiscovery()
                        runOnUiThread {
                            progressBar.visibility = View.GONE
                            scanButton.isEnabled = true
                            if (deviceList.isEmpty()) {
                                Toast.makeText(applicationContext, "No devices found", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }, 30000) // Check after 30 seconds
            } else {
                Log.e(TAG, "Scanning could not be started")
                Toast.makeText(this, "Bluetooth scanning could not be started. Try turning Bluetooth off and on.", Toast.LENGTH_SHORT).show()
                scanButton.isEnabled = true
                progressBar.visibility = View.GONE
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Bluetooth scanning permission error: ${e.message}")
            Toast.makeText(this, "Bluetooth scanning permission error: ${e.message}", Toast.LENGTH_SHORT).show()
            scanButton.isEnabled = true
            progressBar.visibility = View.GONE
        } catch (e: Exception) {
            Log.e(TAG, "Scanning start error: ${e.message}")
            Toast.makeText(this, "Scanning error: ${e.message}", Toast.LENGTH_SHORT).show()
            scanButton.isEnabled = true
            progressBar.visibility = View.GONE
        }
    }
    
    private fun connectToDevice(device: BluetoothDevice) {
        if (connected) {
            disconnectBluetooth()
        }

        val deviceName = try {
            device.name ?: "Unnamed Device"
        } catch (e: SecurityException) {
            Log.e(TAG, "Device name access error: ${e.message}")
            "Unnamed Device"
        }
        val deviceAddress = try {
            device.address
        } catch (e: SecurityException) {
            Log.e(TAG, "Device address access error: ${e.message}")
            "Unknown"
        }
        
        // Check device name and MAC address
        val isKnownDevice = try {
            deviceName.contains("HC-05", ignoreCase = true) || 
                           deviceName.contains("HC05", ignoreCase = true) || 
                           deviceName.contains("ESP32", ignoreCase = true) ||
                           deviceName.contains("Termo", ignoreCase = true) ||
                           deviceName.contains("BT05", ignoreCase = true) ||
                           deviceName.contains("JDY", ignoreCase = true) ||
                           knownHC05Addresses.contains(deviceAddress)
        } catch (e: SecurityException) {
            Log.e(TAG, "Name check error: ${e.message}")
            false
        }
        
        if (!isKnownDevice) {
            // If not a known device, show warning to user
            val alertDialog = AlertDialog.Builder(this)
                .setTitle("Attention")
                .setMessage("This device ($deviceName - $deviceAddress) may not be a standard thermometer module. Are you sure you want to connect?")
                .setPositiveButton("Yes, Connect") { _, _ ->
                    // If user wants to connect, proceed
                    proceedWithConnection(device, deviceName, deviceAddress)
                }
                .setNegativeButton("Cancel", null)
                .create()
            
            alertDialog.show()
        } else {
            // If a known device, connect directly
            proceedWithConnection(device, deviceName, deviceAddress)
        }
    }
    
    private fun proceedWithConnection(device: BluetoothDevice, deviceName: String, deviceAddress: String) {
        Log.d(TAG, "Connecting to $deviceName ($deviceAddress)...")
        Toast.makeText(this, "Connecting to $deviceName...", Toast.LENGTH_SHORT).show()

        var connectionAttempts = 0
        val maxAttempts = 3
        
        connectThread = Thread {
            try {
                // Make Bluetooth connection
                while (connectionAttempts < maxAttempts && bluetoothSocket == null) {
                    connectionAttempts++
                    try {
                        Log.d(TAG, "Connection attempt: $connectionAttempts")
                        
                        if (hasBluetoothConnectPermission()) {
                            try {
                                val connectFuture = CompletableFuture<Boolean>()
                                
                                // Create the standard socket first
                                bluetoothSocket = device.createRfcommSocketToServiceRecord(HC05_UUID)
                                
                                val connectTimeoutThread = Thread {
                                    try {
                                        Log.d(TAG, "HC-05 cihazına bağlanmaya çalışılıyor (MAC: ${device.address})")
                                        
                                        // First try the standard connection method
                                        try {
                                            if (bluetoothSocket == null) {
                                                Log.e(TAG, "Socket null, standart bağlantı yapılamıyor")
                                                throw IOException("Socket null")
                                            }
                                            bluetoothSocket?.connect()
                                            Log.d(TAG, "Standart bağlantı başarılı")
                                            connectFuture.complete(true)
                                        } catch (e: IOException) {
                                            Log.e(TAG, "Standart bağlantı hatası: ${e.message}")
                                            
                                            // Try HC-05 specific workaround - known issue with HC-05 modules
                                            try {
                                                Log.d(TAG, "HC-05 için alternatif bağlantı yöntemi deneniyor...")
                                                
                                                // Get a field from BluetoothDevice by reflection
                                                val device2 = device::class.java
                                                val m = device2.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                                                
                                                // Close the old socket
                                                try {
                                                    bluetoothSocket?.close()
                                                } catch (ce: Exception) {
                                                    Log.e(TAG, "Socket kapatma hatası: ${ce.message}")
                                                }
                                                
                                                // Create a new socket using channel 1
                                                bluetoothSocket = m.invoke(device, 1) as BluetoothSocket
                                                
                                                // Try to connect with the new socket
                                                bluetoothSocket?.connect()
                                                Log.d(TAG, "Alternatif bağlantı başarılı!")
                                                connectFuture.complete(true)
                                            } catch (e2: Exception) {
                                                Log.e(TAG, "Alternatif bağlantı hatası: ${e2.message}")
                                                try {
                                                    bluetoothSocket?.close()
                                                } catch (e3: Exception) {
                                                    Log.e(TAG, "Alternatif socket kapama hatası: ${e3.message}")
                                                }
                                                bluetoothSocket = null
                                                connectFuture.complete(false)
                                            }
                                        }
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Bağlantı hatası: ${e.message}")
                                        try {
                                            bluetoothSocket?.close()
                                        } catch (e2: Exception) {
                                            Log.e(TAG, "Socket kapatma hatası: ${e2.message}")
                                        }
                                        bluetoothSocket = null
                                        connectFuture.complete(false)
                                    }
                                }
                                
                                connectTimeoutThread.start()
                                
                                try {
                                    var connected = connectFuture.get(5000, TimeUnit.MILLISECONDS)
                                    if (connected) {
                                        Log.d(TAG, "Cihaza başarıyla bağlanıldı")
                                        // Initialize the inputStream here
                                        try {
                                            inputStream = bluetoothSocket?.inputStream
                                            Log.d(TAG, "InputStream successfully obtained")
                                            
                                            val available = inputStream?.available() ?: -1
                                            Log.d(TAG, "InputStream available bytes: $available")
                                            
                                            // Make the read button enabled
                                            runOnUiThread {
                                                readDataButton.isEnabled = true
                                                statusTextView.text = "Bluetooth Connection: Connected"
                                            }
                                        } catch (e: IOException) {
                                            Log.e(TAG, "Failed to get InputStream: ${e.message}")
                                            runOnUiThread {
                                                Toast.makeText(this@MainActivity, "Connection established but data stream could not be obtained", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                        
                                        startReading()
                                        this@MainActivity.connected = true
                                        runOnUiThread {
                                            connectButton.text = "Disconnect"
                                            Toast.makeText(this@MainActivity, "Connected to $deviceName", Toast.LENGTH_SHORT).show()
                                        }
                                        break
                                    } else {
                                        Log.e(TAG, "Connection failed")
                                        if (connectionAttempts < maxAttempts) {
                                            Thread.sleep(1000) // Wait 1 second before retrying
                                        }
                                    }
                                } catch (e: TimeoutException) {
                                    Log.e(TAG, "Connection timed out")
                                    connectTimeoutThread.interrupt()
                                    try {
                                        bluetoothSocket?.close()
                                    } catch (e2: Exception) {
                                        Log.e(TAG, "Socket closing error: ${e2.message}")
                                    }
                                    bluetoothSocket = null
                                    
                                    if (connectionAttempts < maxAttempts) {
                                        Thread.sleep(1000) // Wait 1 second before retrying
                                    }
                                }
                            } catch (e: SecurityException) {
                                Log.e(TAG, "Socket creation permission error: ${e.message}")
                                runOnUiThread {
                                    Toast.makeText(this@MainActivity, "Bluetooth socket permission error: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                                break
                            }
                        } else {
                            runOnUiThread {
                                Toast.makeText(this@MainActivity, "No Bluetooth connection permission", Toast.LENGTH_SHORT).show()
                            }
                            break
                        }
                    } catch (e: SecurityException) {
                        Log.e(TAG, "Security error: ${e.message}")
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "Bluetooth permission error: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                        break
                    } catch (e: Exception) {
                        Log.e(TAG, "Connection error (${connectionAttempts}/${maxAttempts}): ${e.message}")
                        if (connectionAttempts < maxAttempts) {
                            Thread.sleep(1000) // Wait 1 second before retrying
                        }
                    }
                }
                
                if (bluetoothSocket == null) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Could not connect to $deviceName", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error: ${e.message}")
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
        connectThread?.start()
    }
    
    private fun disconnectBluetooth() {
        thread {
            try {
                Log.d(TAG, "Disconnecting Bluetooth")
                // Stop automatic reading
                stopAutoReading()
                
                inputStream?.close()
                bluetoothSocket?.close()
            } catch (e: IOException) {
                e.printStackTrace()
                Log.e(TAG, "Disconnection error: ${e.message}")
            } finally {
                runOnUiThread {
                    connected = false
                    statusTextView.text = "Bluetooth Connection: Not Connected"
                    connectButton.text = "Connect to Bluetooth"
                    readDataButton.isEnabled = false
                    temperatureValueTextView.text = "--.-°C"
                    statusImageView.visibility = View.INVISIBLE
                    stopAlarm()
                }
            }
        }
    }
    
    private fun readTemperatureData() {
        if (!connected) {
            Toast.makeText(this, "No connection: connected = false", Toast.LENGTH_SHORT).show()
            Log.e(TAG, "Temperature could not be read: connected = false")
            return
        }
        
        if (bluetoothSocket == null) {
            Toast.makeText(this, "No connection: bluetoothSocket = null", Toast.LENGTH_SHORT).show()
            Log.e(TAG, "Temperature could not be read: bluetoothSocket = null")
            return
        }
        
        if (inputStream == null) {
            // Try to reinitialize the missing InputStream
            try {
                Log.d(TAG, "InputStream is null, reinitializing...")
                inputStream = bluetoothSocket?.inputStream
                Log.d(TAG, "InputStream reinitialized")
                
                // Test if we can actually read from the stream
                try {
                    val available = inputStream?.available() ?: -1
                    Log.d(TAG, "InputStream test: available bytes = $available")
                } catch (e: IOException) {
                    Log.e(TAG, "InputStream test error: ${e.message}")
                    // Socket might be closed, try reconnecting
                    Toast.makeText(this, "Connection may be lost, please try reconnecting", Toast.LENGTH_SHORT).show()
                    connected = false
                    disconnectBluetooth()
                    return
                }
            } catch (e: IOException) {
                Toast.makeText(this, "No connection: couldn't get inputStream - ${e.message}", Toast.LENGTH_SHORT).show()
                Log.e(TAG, "Temperature could not be read: couldn't get inputStream - ${e.message}")
                connected = false
                disconnectBluetooth()
                return
            } catch (e: Exception) {
                Toast.makeText(this, "No connection: inputStream error - ${e.message}", Toast.LENGTH_SHORT).show()
                Log.e(TAG, "Temperature could not be read: inputStream error - ${e.message}")
                connected = false
                disconnectBluetooth()
                return
            }
        }
        
        // Test socket connection status
        try {
            val available = inputStream?.available() ?: -1
            Log.d(TAG, "Socket connection test: available bytes = $available")
        } catch (e: IOException) {
            Log.e(TAG, "Socket connection test error: ${e.message}")
            Toast.makeText(this, "Connection may be lost, please try reconnecting", Toast.LENGTH_SHORT).show()
            connected = false
            disconnectBluetooth()
            return
        }
        
        thread {
            try {
                Log.d(TAG, "Reading temperature data...")
                
                // First clear the buffer
                try {
                    while (inputStream?.available() ?: 0 > 0) {
                        inputStream?.skip((inputStream?.available() ?: 0).toLong())
                    }
                } catch (e: IOException) {
                    Log.e(TAG, "Buffer clearing error: ${e.message}")
                    runOnUiThread {
                        Toast.makeText(this, "Connection error: ${e.message}", Toast.LENGTH_SHORT).show()
                        connected = false
                        disconnectBluetooth()
                    }
                    return@thread
                }
                
                // Attempt to get data from HC-05
                var attempt = 0
                var success = false
                var temperatureValue: Double? = null
                
                // Try the last successful command first
                val commandsToTry = mutableListOf<String>()
                commandsToTry.add(lastSuccessfulCommand)
                for (cmd in TEMPERATURE_COMMANDS) {
                    if (cmd != lastSuccessfulCommand) {
                        commandsToTry.add(cmd)
                    }
                }
                
                while (!success && attempt < commandsToTry.size) {
                    val currentCommand = commandsToTry[attempt]
                    attempt++
                    Log.d(TAG, "Veri okuma denemesi $attempt: Komut='$currentCommand'")
                    
                    try {
                        // HC-05'e komut göndererek sıcaklık verisini iste
                        sendCommandToHC05(currentCommand)
                        Log.d(TAG, "HC-05'e sıcaklık verisi için '$currentCommand' komutu gönderildi")
                        
                        // Veri gelene kadar bekleyelim (maksimum 3 saniye)
                        var waitCount = 0
                        while ((inputStream?.available() ?: 0) <= 0 && waitCount < 30) {
                            Thread.sleep(100)
                            waitCount++
                            if (waitCount % 5 == 0) {
                                Log.d(TAG, "Veri bekleniyor... ${waitCount * 100}ms")
                            }
                        }
                        
                        // Gelen veriyi oku
                        if (inputStream?.available() ?: 0 > 0) {
                            val buffer = ByteArray(1024)
                            val bytes = inputStream?.read(buffer) ?: 0
                            
                            if (bytes > 0) {
                                // Gelen veriyi string'e çevir
                                val data = String(buffer, 0, bytes).trim()
                                Log.d(TAG, "Okunan ham veri: '$data'")
                                
                                // Veri formatını kontrol et ve sıcaklık değerini çıkar
                                temperatureValue = parseTemperatureData(data)
                                
                                if (temperatureValue != null) {
                                    success = true
                                    lastSuccessfulCommand = currentCommand // Bu komutu kaydedelim
                                    Log.d(TAG, "Sıcaklık değeri başarıyla okundu: $temperatureValue, Başarılı komut: $currentCommand")
                                } else {
                                    Log.e(TAG, "Geçerli sıcaklık değeri alınamadı: $data")
                                    // İkinci deneme için biraz bekleyelim
                                    Thread.sleep(500)
                                }
                            } else {
                                Log.e(TAG, "Veri okunamadı (bytes: $bytes)")
                                Thread.sleep(500)
                            }
                        } else {
                            Log.e(TAG, "Veri yok (timeout)")
                            Thread.sleep(500)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Deneme $attempt - Hata: ${e.message}")
                        Thread.sleep(500)
                    }
                }
                
                // Sonuçları UI'da göster
                runOnUiThread {
                    if (success && temperatureValue != null) {
                        val formattedTemp = String.format("%.1f°C", temperatureValue)
                        temperatureValueTextView.text = formattedTemp
                        checkTemperature(temperatureValue)
                    } else {
                        Toast.makeText(this, "Sıcaklık verisi okunamadı, lütfen tekrar deneyin", Toast.LENGTH_SHORT).show()
                        statusTextView.text = "Veri okunamadı - HC-05 bağlantısını kontrol edin"
                    }
                }
                
            } catch (e: IOException) {
                e.printStackTrace()
                Log.e(TAG, "Veri okuma hatası: ${e.message}")
                runOnUiThread {
                    Toast.makeText(this, "Veri okuma hatası: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    /**
     * HC-05'ten gelen farklı formattaki sıcaklık verilerini parse eder
     */
    private fun parseTemperatureData(data: String): Double? {
        try {
            // Format 1: Sadece sayı (örn: "36.5")
            val simpleNumber = data.toDoubleOrNull()
            if (simpleNumber != null) {
                return simpleNumber
            }
            
            // Format 2: "TEMP:36.5" formatında
            if (data.contains("TEMP:")) {
                return data.split(":")[1].trim().toDoubleOrNull()
            }
            
            // Format 3: "SICAKLIK:36.5" formatında
            if (data.contains("SICAKLIK:")) {
                return data.split(":")[1].trim().toDoubleOrNull()
            }
            
            // Format 4: T:36.5
            if (data.matches(Regex("T:\\s*\\d+\\.?\\d*"))) {
                return data.substring(data.indexOf(":") + 1).trim().toDoubleOrNull()
            }
            
            // Format 5: JSON formatında
            if (data.contains("{") && data.contains("}") && data.contains("\"temp\":")) {
                val tempPattern = "\"temp\"\\s*:\\s*(\\d+\\.?\\d*)".toRegex()
                val matchResult = tempPattern.find(data)
                if (matchResult != null && matchResult.groupValues.size > 1) {
                    return matchResult.groupValues[1].toDoubleOrNull()
                }
            }
            
            // Format 6: Herhangi bir yerdeki ilk sayı
            val numberPattern = "\\d+\\.?\\d*".toRegex()
            val matchResult = numberPattern.find(data)
            if (matchResult != null) {
                return matchResult.value.toDoubleOrNull()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Sıcaklık verisi parse hatası: ${e.message}")
        }
        
        return null
    }
    
    private fun checkTemperature(temperature: Double) {
        // Sıcaklık değerini sınıf değişkenine atayalım
        this.currentTemperature = temperature
        
        // Ölçüm listesine ekle
        addTemperatureMeasurement(temperature)
        
        if (temperature > 37.5) {
            // Yüksek sıcaklık uyarısı
            statusImageView.setImageResource(R.drawable.ic_fire)
            statusImageView.visibility = View.VISIBLE
            
            // Sıcaklık 38 dereceden yüksekse daha şiddetli uyarı
            if (temperature >= 38.0) {
                playAlarm()
                statusTextView.text = "UYARI: Yüksek Ateş! (${temperature}°C)"
            } else {
                playAlarm()
                statusTextView.text = "Hafif Ateş (${temperature}°C)"
            }
        } else if (temperature >= 37.0 && temperature <= 37.5) {
            // Sınırda sıcaklık
            statusImageView.setImageResource(R.drawable.ic_fire) // Şimdilik fire ikonunu kullanıyoruz
            statusImageView.visibility = View.VISIBLE
            statusTextView.text = "Sınırda Sıcaklık (${temperature}°C)"
            stopAlarm()
        } else {
            // Normal sıcaklık
            statusImageView.setImageResource(R.drawable.ic_smile)
            statusImageView.visibility = View.VISIBLE
            statusTextView.text = "Normal Sıcaklık (${temperature}°C)"
            stopAlarm()
        }
    }
    
    private var lastAlarmTime = 0L
    private var isAlarmPlaying = false

    private fun playAlarm() {
        if (isAlarmPlaying) return
        
        try {
            toneGenerator?.let { generator ->
                isAlarmPlaying = true
                // Yüksek öncelikli uyarı sesi çal
                generator.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 1000)
                
                // 1 saniye sonra tekrar çal
                Handler(Looper.getMainLooper()).postDelayed({
                    if (isAlarmPlaying) {
                        generator.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 1000)
                    }
                }, 2000)
            } ?: run {
                // ToneGenerator null ise yeniden oluşturmayı dene
                try {
                    toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, 80)
                    playAlarm() // Tekrar dene
                } catch (e: Exception) {
                    Log.e(TAG, "ToneGenerator oluşturma hatası: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Alarm çalma hatası: ${e.message}")
            isAlarmPlaying = false
        }
    }
    
    private fun stopAlarm() {
        isAlarmPlaying = false
        try {
            toneGenerator?.stopTone()
        } catch (e: Exception) {
            Log.e(TAG, "Alarm durdurma hatası: ${e.message}")
        }
    }
    
    // Otomatik ölçüm özelliğini başlat
    private fun startReading() {
        autoReadEnabled = true
        handler.removeCallbacks(autoReadRunnable)
        handler.postDelayed(autoReadRunnable, 5000) // 5 saniye sonra başlat
        Log.d(TAG, "Otomatik ölçüm başlatıldı")
    }
    
    // Otomatik ölçüm özelliğini durdur
    private fun stopAutoReading() {
        autoReadEnabled = false
        handler.removeCallbacks(autoReadRunnable)
        Log.d(TAG, "Otomatik ölçüm durduruldu")
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")
        // Otomatik ölçümü durdur
        stopAutoReading()
        
        disconnectBluetooth()
        toneGenerator?.release()
        toneGenerator = null
        
        // BroadcastReceiver'ı kaldır
        try {
            unregisterReceiver(discoveryReceiver)
            Log.d(TAG, "BroadcastReceiver kaldırıldı")
        } catch (IllegalArgumentException: Exception) {
            // Receiver zaten kayıtlı değilse bu hata oluşabilir
            Log.e(TAG, "BroadcastReceiver zaten kayıtlı değil")
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e(TAG, "BroadcastReceiver kaldırma hatası: ${e.message}")
        }
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        if (requestCode == 1001) {
            var allGranted = true
            for (result in grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false
                    break
                }
            }
            
            if (allGranted) {
                Toast.makeText(this, "İzinler verildi, tekrar deneyin", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Uygulama için gerekli izinler verilmedi", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    // Geçerli sıcaklık değeri
    private var currentTemperature = 0.0

    /**
     * HC-05'e komut gönderir
     */
    private fun sendCommandToHC05(command: String) {
        try {
            if (bluetoothSocket != null) {
                try {
                    bluetoothSocket?.outputStream?.write(command.toByteArray())
                    bluetoothSocket?.outputStream?.flush()
                } catch (e: SecurityException) {
                    Log.e(TAG, "Komut gönderme izin hatası: ${e.message}")
                    throw e
                } catch (e: IOException) {
                    Log.e(TAG, "Komut gönderme I/O hatası: ${e.message}")
                    throw e
                }
            } else {
                Log.e(TAG, "Socket null, komut gönderilemedi")
                throw IOException("Socket bağlantısı yok")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Komut gönderme hatası: ${e.message}")
            throw e
        }
    }

    // Yeni sıcaklık ölçümünü listeye ekleyen fonksiyon
    private fun addTemperatureMeasurement(temperature: Double) {
        // Yeni ölçümü listeye ekle
        val measurement = TemperatureMeasurement(temperature)
        temperatureHistory.add(0, measurement) // Yeni ölçümü listenin başına ekle
        
        // Liste maksimum 5 ölçüm tutacak şekilde sınırla
        if (temperatureHistory.size > 5) {
            temperatureHistory.removeAt(temperatureHistory.size - 1)
        }
        
        // RecyclerView'ı güncelle
        runOnUiThread {
            historyAdapter.updateMeasurements(temperatureHistory)
        }
    }
}