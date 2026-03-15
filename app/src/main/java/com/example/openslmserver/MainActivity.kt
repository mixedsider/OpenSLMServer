package com.example.openslmserver

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.openslmserver.databinding.ActivityMainBinding
import java.net.InetAddress
import java.net.NetworkInterface

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var server: KtorServer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val ipAddress = getLocalIpAddress() ?: "Unknown"
        binding.sampleText.text = "Server IP: $ipAddress\nPort: 8080\nStatus: Initializing..."

        server = KtorServer { log ->
            runOnUiThread {
                binding.sampleText.text = "${binding.sampleText.text}\n$log"
            }
        }

        binding.root.postDelayed({
            server?.start(8080)
            runOnUiThread {
                val currentText = binding.sampleText.text.toString()
                binding.sampleText.text = currentText.replace("Initializing...", "Running")
            }
        }, 1000)
    }

    override fun onDestroy() {
        super.onDestroy()
        server?.stop()
    }

    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is InetAddress && address.hostAddress.contains(".")) {
                        return address.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    external fun stringFromJNI(): String

    companion object {
        init {
            System.loadLibrary("openslmserver")
        }
    }
}