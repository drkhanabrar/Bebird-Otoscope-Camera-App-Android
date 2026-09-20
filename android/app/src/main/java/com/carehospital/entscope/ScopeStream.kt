package com.carehospital.entscope

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import kotlin.concurrent.thread

/**
 * Bebird-type Wi-Fi ENT scope stream receiver.
 *
 * Protocol (identical to the Windows workstation build):
 *
 *   Command packets - 24 bytes, sent to the camera every 400 ms:
 *       offset 0  : uint16 little-endian magic 0x9999
 *       offset 2  : 1 = START, 2 = STOP
 *       remaining : zero
 *
 *   Video packets - received from the camera:
 *       offset 2  : uint16 little-endian packet type, 3 = video
 *       offset 33 : uint16 little-endian 1-based chunk index
 *       offset 51 : start of the JPEG payload
 *
 *   Chunk n occupies (n - 1) * 1388 bytes into the reassembled JPEG. Chunk 1
 *   begins with the JPEG SOI marker FF D8 and marks the start of a new frame.
 *
 * Android note: a phone that still has mobile data will happily route UDP away
 * from the scope's access point, which has no internet. The socket is therefore
 * explicitly bound to the Wi-Fi network via ConnectivityManager. Without this
 * the app silently receives nothing on most modern handsets.
 */
class ScopeStream(
    private val context: Context,
    private val ip: String,
    private val port: Int,
) {

    companion object {
        private const val TAG = "ScopeStream"

        const val HDR = 51
        const val STRIDE = 1388
        const val TYPE_VIDEO = 3
        const val KEEPALIVE_MS = 400L
        const val RECV_BUFFER = 4 * 1024 * 1024
        private const val MAX_FRAME_BYTES = 4 * 1024 * 1024

        private fun command(opcode: Byte): ByteArray {
            val b = ByteArray(24)
            b[0] = 0x99.toByte()          // 0x9999, little-endian
            b[1] = 0x99.toByte()
            b[2] = opcode
            return b
        }

        val START: ByteArray = command(1)
        val STOP: ByteArray = command(2)
    }

    /** Emitted on a worker thread - marshal to the main thread before drawing. */
    var onFrame: ((Bitmap) -> Unit)? = null

    /** Emitted on a worker thread when something goes wrong. */
    var onError: ((String) -> Unit)? = null

    @Volatile var running: Boolean = false
        private set

    @Volatile var packetCount: Long = 0; private set
    @Volatile var frameCount: Long = 0; private set
    @Volatile var decodedCount: Long = 0; private set
    @Volatile var lastFrameAt: Long = 0; private set
    @Volatile var boundToWifi: Boolean = false; private set

    private var socket: DatagramSocket? = null
    private var address: InetAddress? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var connectivity: ConnectivityManager? = null

    // ----------------------------------------------------------------- start
    fun start() {
        if (running) return
        running = true
        packetCount = 0
        frameCount = 0
        decodedCount = 0

        thread(name = "scope-open", isDaemon = true) {
            try {
                address = InetAddress.getByName(ip)
            } catch (e: Exception) {
                onError?.invoke("Cannot resolve $ip")
                running = false
                return@thread
            }
            openSocketBoundToWifi()
            if (!running) return@thread
            thread(name = "scope-keepalive", isDaemon = true) { keepaliveLoop() }
            thread(name = "scope-recv", isDaemon = true) { receiveLoop() }
        }
    }

    /**
     * Ask for the Wi-Fi network that has no internet capability (the scope's
     * access point) and bind the datagram socket to it. Falls back to an
     * unbound socket if no such network turns up quickly.
     */
    private fun openSocketBoundToWifi() {
        val sock = DatagramSocket(null).apply {
            reuseAddress = true
            try { receiveBufferSize = RECV_BUFFER } catch (_: Exception) { }
            bind(InetSocketAddress(0))
            soTimeout = 1000
        }
        socket = sock

        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        connectivity = cm
        if (cm == null) return

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            // The scope AP offers no internet, so this capability must be
            // removed or the request never matches it.
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val latch = java.util.concurrent.CountDownLatch(1)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                try {
                    network.bindSocket(sock)
                    boundToWifi = true
                    Log.i(TAG, "UDP socket bound to Wi-Fi network")
                } catch (e: Exception) {
                    Log.w(TAG, "bindSocket failed: ${e.message}")
                } finally {
                    latch.countDown()
                }
            }

            override fun onUnavailable() = latch.countDown()
        }
        networkCallback = callback

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                cm.requestNetwork(request, callback, 4000)
            } else {
                cm.requestNetwork(request, callback)
            }
            latch.await(4, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.w(TAG, "requestNetwork failed: ${e.message}")
        }

        if (!boundToWifi) {
            onError?.invoke(
                "Using the default network. If there is no picture, turn mobile " +
                    "data off and make sure the phone is joined to the scope Wi-Fi."
            )
        }
    }

    // ------------------------------------------------------------------ stop
    fun stop() {
        running = false
        try {
            socket?.send(DatagramPacket(STOP, STOP.size, address, port))
        } catch (_: Exception) { }
        try { socket?.close() } catch (_: Exception) { }
        socket = null
        networkCallback?.let { cb ->
            try { connectivity?.unregisterNetworkCallback(cb) } catch (_: Exception) { }
        }
        networkCallback = null
        boundToWifi = false
    }

    // --------------------------------------------------------------- workers
    private fun keepaliveLoop() {
        while (running) {
            val sock = socket ?: break
            val addr = address ?: break
            try {
                sock.send(DatagramPacket(START, START.size, addr, port))
            } catch (e: Exception) {
                if (running) onError?.invoke("Keepalive failed: ${e.message}")
            }
            try { Thread.sleep(KEEPALIVE_MS) } catch (_: InterruptedException) { break }
        }
    }

    private fun receiveLoop() {
        val buffer = ByteArray(65535)
        val packet = DatagramPacket(buffer, buffer.size)
        val chunks = HashMap<Int, ByteArray>(256)
        var collecting = false

        while (running) {
            try {
                packet.setData(buffer, 0, buffer.size)
                socket?.receive(packet) ?: break
            } catch (e: SocketTimeoutException) {
                continue
            } catch (e: Exception) {
                if (running) onError?.invoke("Receive failed: ${e.message}")
                break
            }

            packetCount++
            val length = packet.length
            if (length < HDR) continue

            val type = u16(buffer, 2)
            if (type != TYPE_VIDEO) continue

            val index = u16(buffer, 33)
            if (index <= 0) continue

            val payloadLength = length - HDR
            if (payloadLength <= 0) continue
            val payload = buffer.copyOfRange(HDR, HDR + payloadLength)

            val isFrameStart = index == 1 &&
                payload.size >= 2 &&
                payload[0] == 0xFF.toByte() &&
                payload[1] == 0xD8.toByte()

            if (isFrameStart) {
                if (collecting) emit(chunks)
                chunks.clear()
                chunks[1] = payload
                collecting = true
                frameCount++
            } else if (collecting) {
                chunks[index] = payload
            }
        }
    }

    /** Reassemble the chunk map into one JPEG and decode it. */
    private fun emit(chunks: Map<Int, ByteArray>) {
        if (chunks.isEmpty()) return
        val maxIndex = chunks.keys.max()
        val size = (maxIndex - 1) * STRIDE + (chunks[maxIndex]?.size ?: 0)
        if (size <= 0 || size > MAX_FRAME_BYTES) return

        val frame = ByteArray(size)
        for ((index, payload) in chunks) {
            val offset = (index - 1) * STRIDE
            if (offset < 0 || offset >= size) continue
            val count = minOf(payload.size, size - offset)
            System.arraycopy(payload, 0, frame, offset, count)
        }

        val bitmap = try {
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeByteArray(frame, 0, size, options)
        } catch (e: Throwable) {
            null
        } ?: return

        decodedCount++
        lastFrameAt = System.currentTimeMillis()
        onFrame?.invoke(bitmap)
    }

    private fun u16(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
}
