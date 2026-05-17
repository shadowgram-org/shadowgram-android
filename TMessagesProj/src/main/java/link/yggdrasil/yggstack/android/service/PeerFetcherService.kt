package link.yggdrasil.yggstack.android.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import link.yggdrasil.yggstack.android.data.PublicPeerInfo
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

/**
 * Service for fetching public peers from remote sources
 */
class PeerFetcherService {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    companion object {
        private const val PRIMARY_URL = "https://publicpeers.neilalexander.dev/publicnodes.json"
        private const val MIRROR_URL = "https://peers.yggdrasil.link/publicnodes.json"
        private const val TIMEOUT_MS = 10000
        private const val OPENDNS_RESOLVER = "208.67.222.222"
        private const val OPENDNS_MY_IP_HOST = "myip.opendns.com"
        private const val DNS_PORT = 53
        private const val DNS_PACKET_SIZE = 512
        private const val DNS_HEADER_SIZE = 12
        private const val DNS_TYPE_A = 1
        private const val DNS_CLASS_IN = 1
    }

    /**
     * Fetch public peers from remote source
     */
    suspend fun fetchPublicPeers(): Result<List<PublicPeerInfo>> = withContext(Dispatchers.IO) {
        try {
            // Try primary URL first
            val jsonString = fetchUrl(PRIMARY_URL) ?: fetchUrl(MIRROR_URL)

            if (jsonString == null) {
                return@withContext Result.failure(Exception("Failed to fetch from both primary and mirror URLs"))
            }

            val peers = parsePublicNodesJson(jsonString)
            Result.success(peers)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun fetchPublicPeersBlocking(): List<PublicPeerInfo>? {
        return runBlocking {
            fetchPublicPeers().getOrNull()
        }
    }

    /**
     * Get current external IPv4 address
     * Tries ipify.org first, falls back to DNS query via OpenDNS
     */
    suspend fun getExternalIp(onStatusUpdate: ((String) -> Unit)? = null): Result<String> =
        withContext(Dispatchers.IO) {
            // Try ipify.org first
            onStatusUpdate?.invoke("Checking external IP (ipify)...")
            try {
                val ip = fetchUrl("https://api.ipify.org?format=text")?.trim()
                if (!ip.isNullOrBlank()) {
                    return@withContext Result.success(ip)
                }
            } catch (e: Exception) {
                // Ignore, try DNS fallback
            }

            // Fallback to DNS query via OpenDNS
            onStatusUpdate?.invoke("Checking external IP (DNS)...")
            try {
                val ip = getExternalIpViaDns()
                if (ip != null) {
                    Result.success(ip)
                } else {
                    Result.failure(Exception("Failed to get IP from both ipify and DNS"))
                }
            } catch (e: Exception) {
                Result.failure(
                    Exception(
                        "Failed to get IP from both ipify and DNS: ${e.message}",
                        e
                    )
                )
            }
        }

    /**
     * Get external IP via DNS query to OpenDNS
     * Equivalent to: dig +short myip.opendns.com @resolver1.opendns.com
     */
    private fun getExternalIpViaDns(): String? {
        return try {
            val transactionId = System.currentTimeMillis().toInt() and 0xffff
            val query = buildDnsAQuery(OPENDNS_MY_IP_HOST, transactionId)
            val resolverAddress = InetAddress.getByName(OPENDNS_RESOLVER)

            DatagramSocket().use { socket ->
                socket.soTimeout = TIMEOUT_MS
                socket.send(DatagramPacket(query, query.size, resolverAddress, DNS_PORT))

                val response = ByteArray(DNS_PACKET_SIZE)
                val packet = DatagramPacket(response, response.size)
                socket.receive(packet)

                parseDnsAResponse(response, packet.length, transactionId)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun buildDnsAQuery(hostname: String, transactionId: Int): ByteArray {
        val output = ByteArrayOutputStream()
        writeDnsShort(output, transactionId)
        writeDnsShort(output, 0x0100) // Standard recursive query.
        writeDnsShort(output, 1)
        writeDnsShort(output, 0)
        writeDnsShort(output, 0)
        writeDnsShort(output, 0)

        hostname.split('.').forEach { label ->
            val bytes = label.toByteArray(Charsets.US_ASCII)
            output.write(bytes.size)
            output.write(bytes)
        }
        output.write(0)
        writeDnsShort(output, DNS_TYPE_A)
        writeDnsShort(output, DNS_CLASS_IN)

        return output.toByteArray()
    }

    private fun parseDnsAResponse(response: ByteArray, length: Int, transactionId: Int): String? {
        if (length < DNS_HEADER_SIZE || readDnsShort(response, 0) != transactionId) {
            return null
        }

        val flags = readDnsShort(response, 2)
        if ((flags and 0x8000) == 0 || (flags and 0x000f) != 0) {
            return null
        }

        val questions = readDnsShort(response, 4)
        val answers = readDnsShort(response, 6)
        var offset = DNS_HEADER_SIZE

        repeat(questions) {
            offset = skipDnsName(response, length, offset) ?: return null
            if (offset + 4 > length) {
                return null
            }
            offset += 4
        }

        repeat(answers) {
            offset = skipDnsName(response, length, offset) ?: return null
            if (offset + 10 > length) {
                return null
            }

            val type = readDnsShort(response, offset)
            val recordClass = readDnsShort(response, offset + 2)
            val dataLength = readDnsShort(response, offset + 8)
            offset += 10

            if (offset + dataLength > length) {
                return null
            }
            if (type == DNS_TYPE_A && recordClass == DNS_CLASS_IN && dataLength == 4) {
                return InetAddress.getByAddress(response.copyOfRange(offset, offset + 4)).hostAddress
            }

            offset += dataLength
        }

        return null
    }

    private fun skipDnsName(response: ByteArray, length: Int, startOffset: Int): Int? {
        var offset = startOffset
        while (offset < length) {
            val labelLength = response[offset].toInt() and 0xff
            offset++

            when {
                labelLength == 0 -> return offset
                (labelLength and 0xc0) == 0xc0 -> {
                    if (offset >= length) {
                        return null
                    }
                    return offset + 1
                }
                (labelLength and 0xc0) != 0 -> return null
                else -> {
                    offset += labelLength
                    if (offset > length) {
                        return null
                    }
                }
            }
        }

        return null
    }

    private fun readDnsShort(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xff) shl 8) or (data[offset + 1].toInt() and 0xff)
    }

    private fun writeDnsShort(output: ByteArrayOutputStream, value: Int) {
        output.write((value ushr 8) and 0xff)
        output.write(value and 0xff)
    }

    /**
     * Fetch content from URL
     */
    private fun fetchUrl(urlString: String): String? {
        var connection: HttpURLConnection? = null
        try {
            val url = URL(urlString)
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.requestMethod = "GET"

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                return null
            }

            val reader = BufferedReader(InputStreamReader(connection.inputStream))
            val response = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                response.append(line)
            }
            reader.close()

            return response.toString()
        } catch (e: Exception) {
            return null
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Parse publicnodes.json format
     * Structure: { "country.md": { "protocol://host:port": { "key": "...", ... }, ... }, ... }
     */
    private fun parsePublicNodesJson(jsonString: String): List<PublicPeerInfo> {
        val peers = mutableListOf<PublicPeerInfo>()

        try {
            val rootObject = json.parseToJsonElement(jsonString).jsonObject

            // Only use Russian peers
            rootObject.filter { it.key == "russia.md" }.forEach { (countryFile, countryData) ->
                val country = countryFile.removeSuffix(".md")
                    .split("-")
                    .joinToString(" ") { it.replaceFirstChar { char -> char.uppercase() } }

                val peerMap = countryData.jsonObject

                // Iterate through peers in this country
                peerMap.forEach { (uri, _) ->
                    try {
                        peers.add(
                            PublicPeerInfo(
                                uri = uri,
                                country = country
                            )
                        )
                    } catch (e: Exception) {
                        // Skip invalid peer entries
                    }
                }
            }
        } catch (e: Exception) {
            throw Exception("Failed to parse JSON: ${e.message}", e)
        }

        return peers
    }
}
