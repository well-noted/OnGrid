package com.ongrid.app.data.network

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.ongrid.app.data.model.OllamaServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.nio.ByteOrder

private const val TAG = "NetworkScanner"
private const val OLLAMA_PORT = 11434

class NetworkScanner(private val client: OkHttpClient) {

    /**
     * Scans the local subnet for Ollama servers running on port 11434.
     * Emits each discovered server as it is found.
     */
    fun discoverServers(context: Context): Flow<OllamaServer> = flow {
        val subnet = getLocalSubnet(context)
        if (subnet == null) {
            Log.w(TAG, "Could not determine local subnet")
            return@flow
        }
        Log.d(TAG, "Scanning subnet: $subnet.0/24")

        // Scan all 254 possible hosts in the /24 subnet concurrently
        coroutineScope {
            val deferreds = (1..254).map { hostByte ->
                val host = "$subnet.$hostByte"
                async(Dispatchers.IO) {
                    probeOllamaServer(host)
                }
            }

            // Await results as they complete and emit discovered servers
            deferreds.awaitAll().filterNotNull().forEach { server ->
                emit(server)
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Check if a specific host is running an Ollama server.
     * Returns an [OllamaServer] if found, null otherwise.
     */
    suspend fun probeOllamaServer(host: String, port: Int = OLLAMA_PORT): OllamaServer? =
        withContext(Dispatchers.IO) {
            val baseUrl = "http://$host:$port"
            try {
                // Confirm it's actually Ollama by fetching /api/version
                val request = Request.Builder().url("$baseUrl/api/version").get().build()
                val shortTimeoutClient = client.newBuilder()
                    .connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                shortTimeoutClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext null
                    val body = response.body?.string() ?: return@withContext null
                    if (!body.contains("version")) return@withContext null
                    val versionMatch = Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(body)
                    val version = versionMatch?.groupValues?.get(1) ?: ""
                    Log.i(TAG, "Found Ollama server at $host:$port (version=$version)")
                    OllamaServer(host = host, port = port, version = version)
                }
            } catch (e: Exception) {
                null
            }
        }

    /**
     * Returns the local IP address's subnet prefix (e.g. "192.168.1")
     * using the WiFi manager, falling back to connectivity manager.
     */
    private fun getLocalSubnet(context: Context): String? {
        // Try WiFi first
        val wifiManager = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val wifiInfo = wifiManager?.connectionInfo
        val ipInt = wifiInfo?.ipAddress ?: 0
        if (ipInt != 0) {
            // Android returns IP as little-endian int on most devices
            val ipBytes = if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) {
                byteArrayOf(
                    (ipInt and 0xFF).toByte(),
                    (ipInt shr 8 and 0xFF).toByte(),
                    (ipInt shr 16 and 0xFF).toByte(),
                    (ipInt shr 24 and 0xFF).toByte()
                )
            } else {
                byteArrayOf(
                    (ipInt shr 24 and 0xFF).toByte(),
                    (ipInt shr 16 and 0xFF).toByte(),
                    (ipInt shr 8 and 0xFF).toByte(),
                    (ipInt and 0xFF).toByte()
                )
            }
            val ip = InetAddress.getByAddress(ipBytes).hostAddress ?: return null
            return ip.substringBeforeLast(".")
        }

        // Fallback: enumerate network interfaces
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            interfaces?.asSequence()?.forEach { iface ->
                if (iface.isLoopback || !iface.isUp) return@forEach
                iface.inetAddresses.asSequence().forEach { addr ->
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        val ip = addr.hostAddress ?: return@forEach
                        return ip.substringBeforeLast(".")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to enumerate network interfaces", e)
        }
        return null
    }
}
