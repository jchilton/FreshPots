package me.johnchilton.freshpots

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import androidx.core.content.ContextCompat.getSystemService
import java.io.InputStream
import java.net.Socket

/**
 * This class handles all network-related activities in connection with the coffee pot,
 * including service discovery, sending, and receiving.
 */
class BrewerClient(private var context: Context) {

    private var mNsdManager: NsdManager? = null
    private var mServiceListener: NsdManager.DiscoveryListener? = null
    private var mNsdServiceInfo: NsdServiceInfo? = null

    enum class BrewerMeaning {
        UNKNOWN_OR_ERROR {
            override fun getChar(): Char {
                return 'U'
            }
        },
        MONITOR {
            override fun getChar(): Char {
                return 'M'
            }
        },

        BREW {
            override fun getChar(): Char {
                return 'B'
            }
        },
        STOP {
            override fun getChar(): Char {
                return 'T'
            }
        },
        DELAY {
            override fun getChar(): Char {
                return 'D'
            }
        },
        WARM {
            override fun getChar(): Char {
                return 'W'
            }
        },
        SCHEDULE {
            override fun getChar(): Char {
                return 'C'
            }
        },
        ACKNOWLEDGEMENT {
            override fun getChar(): Char {
                return 'K'
            }
        };

        abstract fun getChar(): Char

        companion object {
            @JvmStatic
            fun fromChar(char: Char): BrewerMeaning {
                return values().first { it.getChar() == char }
            }
        }
    }

    /**
     * startServiceAwareness begins asynchronous service discovery and DNS resolution using buildCoffeePotDiscoveryListener.
     * Argument 'onDiscovered' will be called not when the coffee
     * pot has been first discovered, but only after DNS resolution has succeeded.
     *
     * If the service becomes unavailable or if resolution fails, onLost will be called.
     */
    fun startServiceAwareness(context: Context, onDiscovered: () -> Unit, onLost: () -> Unit) {
        mNsdManager = getSystemService(context, NsdManager::class.java) as NsdManager

        mServiceListener = buildCoffeePotDiscoveryListener(onDiscovered, onLost)

        mNsdManager!!.discoverServices(context.getString(R.string.coffee_pot_protocol_mdns_name), NsdManager.PROTOCOL_DNS_SD, mServiceListener)

        Log.d(POT_TAG, "startServiceAwareness()")
    }

    /**
     * buildCoffeePotDiscoveryListener constructs a NsdManager.DiscoveryListener which will first discover the coffee pot
     * and then resolve it. Upon successful discovery, the onDiscovered callback is called. Upon failed discovery or service
     * reachability lost, onLost is called.
     *
     * Please note the contract that if the last report of service state from BrewerClient goes via onDiscovered, then
     * the private variable mNsdServiceInfo must be non-null and DNS-resolved; if the last report goes via onLost, then
     * mNsdServiceInfo should be null. This is for the benefit of a stale-resolution check conducted by send().
     */
    private fun buildCoffeePotDiscoveryListener(
        onDiscovered: () -> Unit,
        onLost: () -> Unit
    ): NsdManager.DiscoveryListener {
        return object : NsdManager.DiscoveryListener {

            override fun onDiscoveryStarted(regType: String) {
            }

            override fun onServiceFound(foundServiceInfo: NsdServiceInfo) {
                Log.d(POT_TAG, "service found")
                mNsdManager!!.resolveService(foundServiceInfo, object : NsdManager.ResolveListener {

                    override fun onResolveFailed(
                        resolvedServiceInfo: NsdServiceInfo,
                        errorCode: Int
                    ) {
                        Log.d(POT_TAG, "resolve failed")
                        onLost()
                    }

                    override fun onServiceResolved(resolvedServiceInfo: NsdServiceInfo) {
                        Log.e(POT_TAG, "Resolve Succeeded. ${resolvedServiceInfo.host}, ${resolvedServiceInfo.port}")
                        mNsdServiceInfo = resolvedServiceInfo

                        onDiscovered()
                    }
                })
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                Log.d(POT_TAG, "service lost")
                mNsdServiceInfo = null
                onLost()
            }

            override fun onDiscoveryStopped(serviceType: String) {
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                stopServiceAwareness()
            }
        }
    }

    /**
     * Ends asynchronous service discovery, i.e. such as you would want for in onPause. See: note
     * about data contract in documentation for buildCoffeePotDiscoveryListener().
     */
    fun stopServiceAwareness() {
        mNsdManager!!.stopServiceDiscovery(mServiceListener)
        mServiceListener = null
        mNsdServiceInfo = null
    }

    /**
     * sendAndReceive starts a socket operation to send the specified coffee pot
     * protocol message to the client and then wait for a response. If there is any problem,
     * the callback will receive BrewerMeaning.UNKNOWN_OR_ERROR. This function should be
     * called in a thread off the main/UI one.
     *
     * Note that it is the caller's responsibility to make sure to (pass or not pass) seconds1 and
     * seconds2 as necessary for the supplied message parameter. This method will not verify the
     * in-protocol validity of the arguments. It will just send the message, and then send seconds1
     * and seconds2 if they are not null.
     */
    fun sendAndReceive(message: BrewerMeaning, seconds1: Int? = null, seconds2: Int? = null, callback: (BrewerMeaning, Int?, Int?) -> Unit) {
        Log.d(POT_TAG, "sendAndReceive()")
        if (mNsdServiceInfo == null || mNsdServiceInfo?.host == null) {
            Log.d(POT_TAG, "resolve error")
            callback(BrewerMeaning.UNKNOWN_OR_ERROR, null, null)
            return
        }

        val toWrite = mutableListOf(message.getChar().code)
        if (seconds1 != null) {
            val bytes1 = int16ToBytes(seconds1)
            toWrite.add(bytes1.first)
            toWrite.add(bytes1.second)
        }

        if (seconds2 != null) {
            val bytes2 = int16ToBytes(seconds2)
            toWrite.add(bytes2.first)
            toWrite.add(bytes2.second)
        }

        try {
            Log.d(POT_TAG, "open socket to ${mNsdServiceInfo!!.host}:${mNsdServiceInfo!!.port}")
            Socket(mNsdServiceInfo!!.host, mNsdServiceInfo!!.port).use { socket ->
                Log.d(POT_TAG, "going to write ${toWrite.size}")

                socket.getOutputStream().write((toWrite.toIntArray().map{ j -> j.toByte()}).toByteArray())
                socket.getOutputStream().flush()

                Log.d(POT_TAG, "${toWrite.size} written")
                Thread.sleep(this.context.resources.getInteger(R.integer.response_wait_time_ms).toLong())

                val response = receive(socket.getInputStream(), message)
                Log.d(POT_TAG, "received")
                callback(response.first, response.second, response.third)
                return
            }
        } catch (e: Exception){
            Log.d(POT_TAG, "exception in use of socket: ${e.javaClass}: ${e.message}, ${e.stackTraceToString()}")
            // In case an exception is thrown int Socket().use(()), we get here
            callback(BrewerMeaning.UNKNOWN_OR_ERROR, null, null)
        }
    }

    /**
     * receive will attempt to return a Triple containing what message was received from the coffee pot,
     * as well as attached data if appropriate. This method relies on knowing which message was originally sent
     * in order to determine if the response was appropriate. The data contract is that the caller will
     * act within the protocol, ie only sending BrewerMeanings meant to be sent and will
     * correctly inform receive() which BrewerMeaning was sent. If not, response is undefined.
     */
    private fun receive(
        inputStream: InputStream,
        whatWasSent: BrewerMeaning
    ): Triple<BrewerMeaning, Int?, Int?> {
        Log.d(POT_TAG, "receive()")
        val unknown = Triple<BrewerMeaning, Int?, Int?>(BrewerMeaning.UNKNOWN_OR_ERROR, null, null)
        val ack = Triple<BrewerMeaning, Int?, Int?>(BrewerMeaning.ACKNOWLEDGEMENT, null, null)

        if (inputStream.available() < 1) {
            Log.d(POT_TAG, "no data available")
            return unknown
        }

        val responseMeaning = BrewerMeaning.fromChar(inputStream.read().toChar())
        var response = ack.copy(first = responseMeaning)

        Log.d(POT_TAG, "Read ${responseMeaning.getChar()}")

        if (whatWasSent == BrewerMeaning.BREW ||
            whatWasSent == BrewerMeaning.SCHEDULE ||
            whatWasSent == BrewerMeaning.WARM ||
            whatWasSent == BrewerMeaning.DELAY ||
            whatWasSent == BrewerMeaning.STOP
        ) {
            if (responseMeaning == BrewerMeaning.ACKNOWLEDGEMENT) {
                return ack
            }

            return unknown
        }

        Log.d(POT_TAG, "sent Monitor?")
        // The MONITOR case needs special logic to parse the response.
        if (whatWasSent == BrewerMeaning.MONITOR) {
            Log.d(POT_TAG, "yes")
            response = try {
                when (responseMeaning) {
                    BrewerMeaning.WARM, BrewerMeaning.DELAY -> response.copy(
                        second = readTimer(
                            inputStream
                        )
                    )
                    BrewerMeaning.SCHEDULE -> response.copy(
                        second = readTimer(inputStream),
                        third = readTimer(inputStream)
                    )
                    else -> response
                }
            } catch (e: Exception) {
                unknown
            }
        }

        return response
    }

    /**
     * readTimer gets two bytes from the InputStream, a, and b, just as in the coffee pot
     * embedded code documentation
     */
    private fun readTimer(stream: InputStream): Int {
        val b1 = stream.read()
        val b2 = stream.read()
        return (b1 shl 8) or b2
    }

    private fun int16ToBytes(pInt: Int): Pair<Int, Int> {
        return Pair((pInt and 0xff00) shr 8, pInt and 0xff)
    }
}