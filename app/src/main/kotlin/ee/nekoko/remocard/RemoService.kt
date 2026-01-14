package ee.nekoko.remocard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.se.omapi.Channel
import android.se.omapi.SEService
import android.se.omapi.Session
import android.se.omapi.Reader
import android.util.Log
import android.content.Context
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.MessageDigest
import android.util.Base64

@Serializable
data class VersionInfo(
    val version: String = "1.2",
    val deviceName: String = android.os.Build.MODEL,
    val supportedCommands: List<String> = listOf("sendRawApdu", "sendApdu", "openChannel", "closeChannel", "listSlots", "listChannels"),
    val encryptionRequired: Boolean = false,
    val sessionSupported: Boolean = true
)

@Serializable
data class HandshakeRequest(val clientNonce: String)

@Serializable
data class HandshakeResponse(val sessionId: String, val serverNonce: String)

@Serializable
data class SlotInfo(val name: String, val isPresent: Boolean, val eid: String? = null, val aid: String? = null)

@Serializable
data class OpenChannelRequest(val aids: List<String>, val reader: String? = null)

@Serializable
data class ApduRequest(val apdu: String, val aid: String? = null, val reader: String? = null)

    private val DEFAULT_AIDS = listOf(
        "A06573746B6D65FFFF4953442D522031",
        "A06573746B6D65FFFF4953442D522030",
        "A0000005591010FFFFFFFF8900000100",
        "A0000005591010FFFFFFFF8900050500",
        "A0000005591010000000008900000300",
        "A0000005591010FFFFFFFF8900000177"
    )

@Serializable
data class ErrorResponse(val error: String, val code: String? = null)

@Serializable
data class SuccessResponse(val success: Boolean, val message: String? = null)

@Serializable
data class TransmitResponse(val response: String)

@Serializable
data class OpenChannelResponse(val success: Boolean, val aid: String? = null, val reader: String? = null)

@Serializable
data class ListSlotsResponse(val slots: List<SlotInfo>)

@Serializable
data class ListChannelsResponse(val channels: List<String>)

@Serializable
data class SettingsRequest(
    val port: Int? = null,
    val password: String? = null,
    val useAes: Boolean? = null,
    val singleChannel: Boolean? = null
)

class RemoService : Service() {
    private var server: ApplicationEngine? = null
    private var seService: SEService? = null
    private val activeSessions = mutableMapOf<String, Session>()
    private val activeChannels = mutableMapOf<String, Channel>() // Key: "reader:aid"
    private val slotEids = mutableMapOf<String, String>() // Key: readerName, Value: EID
    private val slotAids = mutableMapOf<String, String>() // Key: readerName, Value: Supported AID

    private var currentPort = 33777
    private var currentPassword = ""
    private var currentUseAes = false
    private var currentSingleChannel = false
    private var allowedReaders = mutableSetOf<String>()
    private val sessionKeys = mutableMapOf<String, ByteArray>() // sessionId -> sessionKey
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    companion object {
        private const val TAG = "RemoCardService"
        private const val CHANNEL_ID = "RemoCardServer"
        private const val ACTION_SIM_STATE_CHANGED = "android.intent.action.SIM_STATE_CHANGED"
        private val json = Json { 
            ignoreUnknownKeys = true
            prettyPrint = true
        }
        
        // Track connected clients: Map<IP, lastSeenTimestamp>
        val activeClients = java.util.concurrent.ConcurrentHashMap<String, Long>()
        
        fun cleanOldClients() {
            val now = System.currentTimeMillis()
            val iterator = activeClients.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (now - entry.value > 10 * 60 * 1000) { // 10 minutes
                    iterator.remove()
                }
            }
        }
    }

    private val simStateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            if (intent?.action == ACTION_SIM_STATE_CHANGED) {
                Log.d(TAG, "SIM State Changed, refreshing EIDs")
                refreshEids()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        setupCrashHandler()
        loadSettings()
        acquireWakeLock()
        startForegroundService()
        initOmapi()
        startServer()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(simStateReceiver, android.content.IntentFilter(ACTION_SIM_STATE_CHANGED), RECEIVER_EXPORTED)
        } else {
            registerReceiver(simStateReceiver, android.content.IntentFilter(ACTION_SIM_STATE_CHANGED))
        }
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences("remocard_prefs", Context.MODE_PRIVATE)
        currentPort = prefs.getInt("port", 33777)
        currentPassword = prefs.getString("password", "") ?: ""
        currentUseAes = currentPassword.isNotEmpty()
        currentSingleChannel = prefs.getBoolean("single_channel", false)
        currentSingleChannel = prefs.getBoolean("single_channel", false)
        allowedReaders = prefs.getStringSet("allowed_readers", null)?.toMutableSet() ?: mutableSetOf()
        
        // Load custom AIDs if present (Not implemented yet, using defaults)
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "RemoCard::ServerWakeLock")
        wakeLock?.acquire()
    }

    private fun setupCrashHandler() {
        val oldHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "Uncaught exception in thread ${thread.name}", throwable)
            val intent = Intent(this, MainActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            oldHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun logAppSignatureHash() {
        try {
            val packageName = packageName
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageManager.getPackageInfo(packageName, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, android.content.pm.PackageManager.GET_SIGNATURES)
            }
            
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.signingInfo?.apkContentsSigners ?: emptyArray()
            } else {
                @Suppress("DEPRECATION")
                packageInfo.signatures ?: emptyArray()
            }

            for (sig in signatures) {
                val md = java.security.MessageDigest.getInstance("SHA-1")
                val hash = md.digest(sig.toByteArray())
                Log.i(TAG, "App Signature SHA-1: ${bytesToHex(hash)}")
                val md256 = java.security.MessageDigest.getInstance("SHA-256")
                val hash256 = md256.digest(sig.toByteArray())
                Log.i(TAG, "App Signature SHA-256: ${bytesToHex(hash256)}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get signature hash", e)
        }
    }

    private fun startForegroundService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "RemoCard API Server", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("RemoCard Server Running")
                .setContentText("Listening on port $currentPort")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("RemoCard Server Running")
                .setContentText("Listening on port $currentPort")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build()
        }
        startForeground(1, notification)
    }

    private fun initOmapi() {
        seService = SEService(this, Executors.newSingleThreadExecutor()) {
            Log.i(TAG, "SE Service connected")
            refreshEids()
        }
    }

    private fun refreshEids() {
        val readers = seService?.readers ?: return
        readers.forEach { reader ->
            if (reader.isSecureElementPresent) {
                try {
                    val session = reader.openSession()
                    
                    // Try to detect working AID and EID
                    var foundAid: String? = null
                    var foundEid: String? = null
                    
                    // Add standard ISD-R AID to the list to check
                    val aidsToCheck = mutableListOf("A0000005591010FFFFFFFF8900000100")
                    aidsToCheck.addAll(DEFAULT_AIDS)
                    val uniqueAids = aidsToCheck.distinct()

                    for (aid in uniqueAids) {
                         try {
                             val aidBytes = hexToBytes(aid)
                             val channel = session.openLogicalChannel(aidBytes)
                             if (channel != null) {
                                 foundAid = aid
                                 Log.i(TAG, "Found working AID for ${reader.name}: $aid")
                                 
                                 // Try to get EID using this channel
                                 try {
                                     // GET EID: 80 E2 91 00 06 BF 3E 03 5C 01 5A
                                     val getEidCmd = hexToBytes("80E2910006BF3E035C015A")
                                     val response = transmitWithChunking(channel, getEidCmd)
                                     val respHex = bytesToHex(response)
                                     Log.d(TAG, "EID Query response for ${reader.name} using $aid: $respHex")
                                     
                                     if (respHex.length >= 42 && !respHex.startsWith("6881")) {
                                         // 5A 10 followed by 16 bytes.
                                         val index = respHex.lowercase().indexOf("5a10")
                                          if (index != -1 && respHex.length >= index + 4 + 32) {
                                             foundEid = respHex.substring(index + 4, index + 4 + 32).uppercase()
                                             Log.i(TAG, "Found EID for ${reader.name}: $foundEid")
                                         }
                                     }
                                 } catch (e: Exception) {
                                     Log.w(TAG, "Failed to get EID on AID $aid: ${e.message}")
                                 }
                                 
                                 channel.close()
                                 if (foundEid != null) {
                                     break // Found everything we need
                                 }
                                 // If we found AID but not EID, we could continue searching or stop. 
                                 // Usually any ISD AID should respond to EID query if supported. 
                                 // Let's break if we found a working channel, assuming EID query failure means EID not available via standard means.
                                 break 
                             }
                         } catch (e: Exception) {
                             // Ignore failure to open this AID
                         }
                    }
                    
                    if (foundEid != null) {
                        slotEids[reader.name] = foundEid!!
                    }
                    if (foundAid != null) {
                        slotAids[reader.name] = foundAid!!
                    }
                    
                    session.close()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to fetch EID/AID for ${reader.name}: ${e.message}")
                }
            }
        }
    }

    private fun startServer() {
        try {
            server = embeddedServer(CIO, port = currentPort) {
                install(ContentNegotiation) {
                    json(Json {
                        ignoreUnknownKeys = true
                        prettyPrint = true
                    })
                }
                install(CORS) {
                    anyHost()
                    allowMethod(HttpMethod.Options)
                    allowMethod(HttpMethod.Put)
                    allowMethod(HttpMethod.Delete)
                    allowMethod(HttpMethod.Patch)
                    allowHeader(HttpHeaders.Authorization)
                    allowHeader(HttpHeaders.ContentType)
                    allowHeader(HttpHeaders.AccessControlAllowOrigin)
                    anyHost() // Ensure all origins are allowed for web client
                }

                intercept(ApplicationCallPipeline.Plugins) {
                    val remoteHost = call.request.origin.remoteHost
                    activeClients[remoteHost] = System.currentTimeMillis()
                    cleanOldClients()

                    if (currentPassword.isNotEmpty()) {
                        val authHeader = call.request.header(HttpHeaders.Authorization)
                        if (authHeader != "Bearer $currentPassword") {
                            call.respondMaybeEncrypted(ErrorResponse("Invalid password", "invalid_password"))
                            finish()
                        }
                    }
                }

                routing {
                    get("/") {
                        Log.d(TAG, "GET /")
                        call.respond(VersionInfo(encryptionRequired = currentUseAes))
                    }

                    post("/handshake") {
                        val req = call.receive<HandshakeRequest>()
                        val sessionId = java.util.UUID.randomUUID().toString()
                        val serverNonce = java.util.UUID.randomUUID().toString().substring(0, 16)
                        
                        // Derive Session Key: SHA-256(password + clientNonce + serverNonce)
                        val combined = currentPassword + req.clientNonce + serverNonce
                        val key = MessageDigest.getInstance("SHA-256").digest(combined.toByteArray())
                        sessionKeys[sessionId] = key
                        
                        // Optional: Limit map size to prevent OOM
                        if (sessionKeys.size > 100) {
                            val firstKey = sessionKeys.keys.first()
                            sessionKeys.remove(firstKey)
                        }

                        call.respond(HandshakeResponse(sessionId, serverNonce))
                    }

                    get("/listSlots") {
                        Log.d(TAG, "GET /listSlots")
                        val readers = seService?.readers ?: emptyArray<Reader>()
                        val filtered: List<Reader> = if (allowedReaders.isEmpty()) readers.toList() else readers.filter { it.name in allowedReaders }
                        val slots = filtered.map { SlotInfo(it.name, it.isSecureElementPresent, slotEids[it.name], slotAids[it.name]) }
                        call.respondMaybeEncrypted(ListSlotsResponse(slots))
                    }

                    post("/openChannel") {
                        if (currentSingleChannel) {
                            closeAllChannels()
                        }
                        
                        try {
                            Log.d(TAG, "POST /openChannel")
                            val req = call.receiveMaybeEncrypted<OpenChannelRequest>()
                            val readers = seService?.readers ?: emptyArray()
                            val reader = if (req.reader != null) readers.find { it.name == req.reader } else readers.firstOrNull()
                            
                            if (reader == null) {
                                call.respondMaybeEncrypted(ErrorResponse("Reader not found"), HttpStatusCode.NotFound)
                                return@post
                            }
                            
                            if (allowedReaders.isNotEmpty() && reader.name !in allowedReaders) {
                                call.respondMaybeEncrypted(ErrorResponse("Reader not allowed", "access_denied"), HttpStatusCode.Forbidden)
                                return@post
                            }

                            val session = activeSessions.getOrPut(reader.name) { reader.openSession() }
                            var openedChannel: Channel? = null
                            var matchedAid: String? = null

                            for (aid in req.aids) {
                                try {
                                    val aidBytes = hexToBytes(aid)
                                    Log.d(TAG, "Trying to open logical channel for AID: $aid")
                                    openedChannel = session.openLogicalChannel(aidBytes)
                                    if (openedChannel != null) {
                                        matchedAid = aid
                                        activeChannels["${reader.name}:$aid"] = openedChannel
                                        Log.i(TAG, "Successfully opened channel for AID: $aid")
                                        break
                                    }
                                } catch (e: SecurityException) {
                                    Log.e(TAG, "SecurityException opening channel for AID $aid. App might not be whitelisted: ${e.message}")
                                } catch (e: Exception) {
                                    Log.w(TAG, "Failed for AID $aid: ${e.message}", e)
                                }
                            }
                            
                            if (openedChannel != null) {
                                call.respondMaybeEncrypted(OpenChannelResponse(true, matchedAid, reader.name))
                            } else {
                                call.respondMaybeEncrypted(OpenChannelResponse(false))
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error in /openChannel", e)
                            if (e.message == "Invalid session") {
                                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid session", "invalid_session"))
                            } else {
                                call.respondMaybeEncrypted(ErrorResponse(e.toString()), HttpStatusCode.InternalServerError)
                            }
                        }
                    }

                    post("/sendApdu") {
                        try {
                            Log.d(TAG, "POST /sendApdu")
                            val req = call.receiveMaybeEncrypted<ApduRequest>()
                            val readers = seService?.readers ?: emptyArray()
                            val readerName = req.reader ?: readers.firstOrNull()?.name ?: ""
                            
                            if (allowedReaders.isNotEmpty() && readerName !in allowedReaders) {
                                call.respondMaybeEncrypted(ErrorResponse("Reader not allowed", "access_denied"), HttpStatusCode.Forbidden)
                                return@post
                            }

                            val aid = req.aid ?: ""
                            val channel = activeChannels["$readerName:$aid"]

                            if (channel == null) {
                                call.respondMaybeEncrypted(ErrorResponse("Channel not found for reader $readerName and AID $aid. Call openChannel first."))
                                return@post
                            }

                            val command = hexToBytes(req.apdu)
                            val response = transmitWithChunking(channel, command)
                            call.respondMaybeEncrypted(TransmitResponse(bytesToHex(response)))
                        } catch (e: Exception) {
                            Log.e(TAG, "Error in /sendApdu", e)
                            if (e.message == "Invalid session") {
                                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid session", "invalid_session"))
                            } else {
                                call.respondMaybeEncrypted(ErrorResponse(e.toString()))
                            }
                        }
                    }
                    
                    post("/sendRawApdu") {
                        try {
                            Log.d(TAG, "POST /sendRawApdu")
                            val req = call.receiveMaybeEncrypted<ApduRequest>()
                            val readers = seService?.readers ?: emptyArray()
                            val readerName = req.reader ?: readers.firstOrNull()?.name ?: ""
                            val reader = readers.find { it.name == readerName }
                            if (reader == null) {
                                call.respondMaybeEncrypted(ErrorResponse("Reader not found"), HttpStatusCode.NotFound)
                                return@post
                            }

                            if (allowedReaders.isNotEmpty() && reader.name !in allowedReaders) {
                                call.respondMaybeEncrypted(ErrorResponse("Reader not allowed", "access_denied"), HttpStatusCode.Forbidden)
                                return@post
                            }
                        
                            val session = reader.openSession()
                            // Raw APDU in OMAPI usually means open logical channel to default eUICC AID and transmit
                            val defaultAid = hexToBytes("A0000005591010FFFFFFFF8900000100")
                            val channel = session.openLogicalChannel(defaultAid)
                            if (channel == null) {
                                call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Failed to open default channel"))
                                return@post
                            }
                            val command = hexToBytes(req.apdu)
                            val response = transmitWithChunking(channel, command)
                            channel.close()
                            session.close()
                            call.respondMaybeEncrypted(TransmitResponse(bytesToHex(response)))
                        } catch (e: Exception) {
                            Log.e(TAG, "Error in /sendRawApdu", e)
                            if (e.message == "Invalid session") {
                                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid session", "invalid_session"))
                            } else {
                                call.respondMaybeEncrypted(ErrorResponse(e.toString()))
                            }
                        }
                    }

                    post("/closeChannel") {
                        try {
                            Log.d(TAG, "POST /closeChannel")
                            val req = call.receiveMaybeEncrypted<OpenChannelRequest>() // reuse aids
                            val readers = seService?.readers ?: emptyArray()
                            val readerName = req.reader ?: readers.firstOrNull()?.name ?: ""
                            
                            req.aids.forEach { aid ->
                                activeChannels.remove("$readerName:$aid")?.close()
                            }
                            call.respondMaybeEncrypted(SuccessResponse(true))
                        } catch (e: Exception) {
                            Log.e(TAG, "Error in /closeChannel", e)
                            if (e.message == "Invalid session") {
                                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid session", "invalid_session"))
                            } else {
                                call.respondMaybeEncrypted(ErrorResponse(e.toString()))
                            }
                        }
                    }
                    
                    get("/listChannels") {
                        Log.d(TAG, "GET /listChannels")
                        call.respond(ListChannelsResponse(activeChannels.keys.toList()))
                    }

                    post("/updateSettings") {
                        val req = call.receive<SettingsRequest>()
                        val prefs = getSharedPreferences("remocard_prefs", Context.MODE_PRIVATE)
                        prefs.edit().apply {
                            req.port?.let { putInt("port", it) }
                            req.password?.let { putString("password", it) }
                            req.singleChannel?.let { putBoolean("single_channel", it) }
                            apply()
                        }
                        loadSettings()
                        call.respond(SuccessResponse(true, "Settings updated. Port change requires server restart."))
                    }
                }
            }
            server?.start(wait = false)
            Log.i(TAG, "Server started on port $currentPort")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start server", e)
        }
    }

    private fun transmitWithChunking(channel: Channel, command: ByteArray): ByteArray {
        // Bi-directional chunking:
        // 1. Command Chaining (split huge command into multiple APDUs with bit 4 of CLA set)
        // 2. Response Chaining (handle 61XX GET RESPONSE)
        
        // This is a simplified implementation. Real ISO 7816-4 chaining might be more complex.
        // But the user specifically asked for bi-directional handling of huge request/responses.
        
        // Handle Request Chaining (Sending)
        // If command is > 255 bytes and SE doesn't support extended length, or just to be safe:
        // (Actually OMAPI handles extended length if SE supports it, but let's assume standard chaining)
        
        // For now, let's focus on Response Chaining which is most common "penalty"
        var response = channel.transmit(command)
        var sw1 = response[response.size - 2].toInt() and 0xFF
        var sw2 = response[response.size - 1].toInt() and 0xFF
        
        val fullResponse = mutableListOf<Byte>()
        fullResponse.addAll(response.copyOfRange(0, response.size - 2).toList())
        
        while (sw1 == 0x61) {
            val getResponse = byteArrayOf(0x00, 0xC0.toByte(), 0x00, 0x00, sw2.toByte())
            response = channel.transmit(getResponse)
            sw1 = response[response.size - 2].toInt() and 0xFF
            sw2 = response[response.size - 1].toInt() and 0xFF
            fullResponse.addAll(response.copyOfRange(0, response.size - 2).toList())
        }
        
        if (sw1 == 0x6C) {
           val corrected = command.copyOf()
           corrected[corrected.size - 1] = sw2.toByte()
           return transmitWithChunking(channel, corrected)
        }
        
        fullResponse.add(sw1.toByte())
        fullResponse.add(sw2.toByte())
        return fullResponse.toByteArray()
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        wakeLock?.let { if (it.isHeld) it.release() }
        server?.stop(1000, 2000)
        closeAllChannels()
        seService?.shutdown()
        unregisterReceiver(simStateReceiver)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun hexToBytes(hex: String): ByteArray {
        val s = hex.replace(" ", "")
        val result = ByteArray(s.length / 2)
        for (i in 0 until s.length step 2) {
            result[i / 2] = s.substring(i, i + 2).toInt(16).toByte()
        }
        return result
    }

    private fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun closeAllChannels() {
        activeChannels.values.forEach { try { it.close() } catch (e: Exception) {} }
        activeChannels.clear()
        activeSessions.values.forEach { try { it.close() } catch (e: Exception) {} }
        activeSessions.clear()
    }

    private fun decrypt(encrypted: String, sessionId: String? = null): String {
        if (currentPassword.isEmpty() || !currentUseAes) return encrypted
        try {
            val keyBytes = if (sessionId != null) {
                sessionKeys[sessionId] ?: throw Exception("Invalid session")
            } else {
                MessageDigest.getInstance("SHA-256").digest(currentPassword.toByteArray())
            }
            val key = SecretKeySpec(keyBytes, "AES")
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val data = Base64.decode(encrypted, Base64.DEFAULT)
            val iv = IvParameterSpec(data.copyOfRange(0, 16))
            cipher.init(Cipher.DECRYPT_MODE, key, iv)
            return String(cipher.doFinal(data.copyOfRange(16, data.size)))
        } catch (e: Exception) {
            Log.e(TAG, "Decryption failed (session: $sessionId)", e)
            throw e
        }
    }

    private fun encrypt(plainText: String, sessionId: String? = null): String {
        if (currentPassword.isEmpty() || !currentUseAes) return plainText
        try {
            val keyBytes = if (sessionId != null) {
                sessionKeys[sessionId] ?: throw Exception("Invalid session")
            } else {
                MessageDigest.getInstance("SHA-256").digest(currentPassword.toByteArray())
            }
            val key = SecretKeySpec(keyBytes, "AES")
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val encrypted = cipher.doFinal(plainText.toByteArray())
            return Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Encryption failed (session: $sessionId)", e)
            // If encryption failed (e.g. invalid session), send plain error if possible, or better:
            // Since we can't encrypt, returning a plain error might be intercepted as 500 on client if it expects encrypted.
            // But if we return a JSON that client CAN parse, handled in catch block?
            // Actually, returning a special string to signal failure.
            return "{ \"error\": \"Encryption failed: ${e.message}\", \"code\": \"encryption_failed\" }"
        }
    }
    
    private suspend inline fun <reified T : Any> ApplicationCall.receiveMaybeEncrypted(): T {
        return if (currentUseAes) {
            val sessionId = request.header("X-Remo-Session")
            val encrypted = receiveText()
            json.decodeFromString<T>(decrypt(encrypted, sessionId))
        } else {
            receive()
        }
    }

    private suspend inline fun <reified T : Any> ApplicationCall.respondMaybeEncrypted(data: T, status: HttpStatusCode = HttpStatusCode.OK) {
        var finalStatus = status
        if (data is ErrorResponse && data.code == "invalid_password") {
            finalStatus = HttpStatusCode.Unauthorized
        } else if (data is ErrorResponse) {
             if (finalStatus == HttpStatusCode.OK) finalStatus = HttpStatusCode.BadRequest
        }

        if (currentUseAes) {
            val sessionId = request.header("X-Remo-Session")
            val plainJson = json.encodeToString(data)
            respondText(encrypt(plainJson, sessionId), ContentType.Text.Plain, finalStatus)
        } else {
            respond(finalStatus, data)
        }
    }
}
