package me.johnchilton.freshpots

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import androidx.core.content.ContextCompat.getSystemService

class CoffeePotClient {
    val SERVICE_TYPE = "_johnscoffeepot._tcp"

    init {

    }

    fun beginNetworkStatusDiscovery(context: Context) {
        val nsdManager = (getSystemService(context, NsdManager::class.java) as NsdManager)

        // Instantiate a new DiscoveryListener
        val discoveryListener = object : NsdManager.DiscoveryListener {

            // Called as soon as service discovery begins.
            override fun onDiscoveryStarted(regType: String) {
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                Log.i("Woot", "${service.serviceType} / ${service.serviceName}")
//                // A service was found! Do something with it.
//                when {
//                    service.serviceType != SERVICE_TYPE -> // Service type is the string containing the protocol and
//                        // transport layer for this service.
//                        Log.d(TAG, "Unknown Service Type: ${service.serviceType}")
//                    service.serviceName == mServiceName -> // The name of the service tells the user what they'd be
//                        // connecting to. It could be "Bob's Chat App".
//                        Log.d(TAG, "Same machine: $mServiceName")
//                    service.serviceName.contains("NsdChat") -> nsdManager.resolveService(service, resolveListener)
//                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                // When the network service is no longer available.
                // Internal bookkeeping code goes here.
            }

            override fun onDiscoveryStopped(serviceType: String) {
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                nsdManager.stopServiceDiscovery(this)
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                nsdManager.stopServiceDiscovery(this)
            }
        }

        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }
}