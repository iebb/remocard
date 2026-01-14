package ee.nekoko.remocard.omapi

import android.content.Context
import android.os.Build
import android.util.Log
import java.util.concurrent.Executor

interface SmartcardReader {
    val name: String
    val isSecureElementPresent: Boolean
    fun openSession(): SmartcardSession
}

interface SmartcardSession {
    val reader: SmartcardReader
    val isClosed: Boolean
    fun close()
    fun openLogicalChannel(aid: ByteArray): SmartcardChannel?
}

interface SmartcardChannel {
    val session: SmartcardSession
    fun transmit(command: ByteArray): ByteArray
    fun close()
}

interface SmartcardService {
    val readers: Array<SmartcardReader>
    val isConnected: Boolean
    fun shutdown()
}

class OmapiManager(private val context: Context, private val executor: Executor, private val onConnected: () -> Unit) {
    private var service: SmartcardService? = null

    init {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Log.d("OmapiManager", "Using Android P+ OMAPI")
                service = AndroidOmapiService(context, executor, onConnected)
            } else {
                Log.d("OmapiManager", "Using SimAlliance OMAPI (via reflection)")
                service = SimAllianceReflectionService(context, onConnected)
            }
        } catch (e: Throwable) {
            Log.e("OmapiManager", "Failed to initialize OMAPI: ${e.message}")
            try {
                service = SimAllianceReflectionService(context, onConnected)
            } catch (e2: Throwable) {
                Log.e("OmapiManager", "SimAlliance fallback failed: ${e2.message}")
            }
        }
    }

    fun getService(): SmartcardService? = service

    // --- Android 9+ Implementation ---
    private class AndroidOmapiService(context: Context, executor: Executor, onConnected: () -> Unit) : SmartcardService {
        private val seService = android.se.omapi.SEService(context, executor) { onConnected() }
        
        override val readers: Array<SmartcardReader>
            get() = try { seService.readers.map { AndroidReader(it) }.toTypedArray() } catch (e: Exception) { emptyArray() }
            
        override val isConnected: Boolean
            get() = seService.isConnected
            
        override fun shutdown() = seService.shutdown()

        class AndroidReader(private val reader: android.se.omapi.Reader) : SmartcardReader {
            override val name: String = reader.name
            override val isSecureElementPresent: Boolean = reader.isSecureElementPresent
            override fun openSession(): SmartcardSession = AndroidSession(this, reader.openSession())
        }

        class AndroidSession(override val reader: SmartcardReader, private val session: android.se.omapi.Session) : SmartcardSession {
            override val isClosed: Boolean = session.isClosed
            override fun close() = session.close()
            override fun openLogicalChannel(aid: ByteArray): SmartcardChannel? {
                return session.openLogicalChannel(aid)?.let { AndroidChannel(this, it) }
            }
        }

        class AndroidChannel(override val session: SmartcardSession, private val channel: android.se.omapi.Channel) : SmartcardChannel {
            override fun transmit(command: ByteArray): ByteArray = channel.transmit(command)
            override fun close() = channel.close()
        }
    }

    // --- SimAlliance Implementation via Reflection ---
    private class SimAllianceReflectionService(context: Context, onConnected: () -> Unit) : SmartcardService {
        private var seService: Any? = null
        private val clsSEService = Class.forName("org.simalliance.openmobileapi.SEService")
        private val clsReader = Class.forName("org.simalliance.openmobileapi.Reader")
        private val clsSession = Class.forName("org.simalliance.openmobileapi.Session")
        private val clsChannel = Class.forName("org.simalliance.openmobileapi.Channel")

        init {
            val clsCallback = Class.forName("org.simalliance.openmobileapi.SEService\$CallBack")
            val proxy = java.lang.reflect.Proxy.newProxyInstance(
                clsCallback.classLoader,
                arrayOf(clsCallback)
            ) { _, _, _ ->
                onConnected()
                null
            }
            val ctor = clsSEService.getConstructor(Context::class.java, clsCallback)
            seService = ctor.newInstance(context, proxy)
        }

        override val readers: Array<SmartcardReader>
            get() {
                val method = clsSEService.getMethod("getReaders")
                val readersArray = method.invoke(seService) as Array<*>
                return readersArray.map { SimReader(it!!, clsReader, clsSession, clsChannel) }.toTypedArray()
            }
            
        override val isConnected: Boolean
            get() = clsSEService.getMethod("isConnected").invoke(seService) as Boolean
            
        override fun shutdown() {
            clsSEService.getMethod("shutdown").invoke(seService)
        }

        class SimReader(private val reader: Any, private val clsReader: Class<*>, private val clsSession: Class<*>, private val clsChannel: Class<*>) : SmartcardReader {
            override val name: String = clsReader.getMethod("getName").invoke(reader) as String
            override val isSecureElementPresent: Boolean = clsReader.getMethod("isSecureElementPresent").invoke(reader) as Boolean
            override fun openSession(): SmartcardSession {
                val session = clsReader.getMethod("openSession").invoke(reader)!!
                return SimSession(this, session, clsSession, clsChannel)
            }
        }

        class SimSession(override val reader: SmartcardReader, private val session: Any, private val clsSession: Class<*>, private val clsChannel: Class<*>) : SmartcardSession {
            override val isClosed: Boolean = clsSession.getMethod("isClosed").invoke(session) as Boolean
            override fun close() { clsSession.getMethod("close").invoke(session) }
            override fun openLogicalChannel(aid: ByteArray): SmartcardChannel? {
                val channel = clsSession.getMethod("openLogicalChannel", ByteArray::class.java).invoke(session, aid)
                return if (channel != null) SimChannel(this, channel, clsChannel) else null
            }
        }

        class SimChannel(override val session: SmartcardSession, private val channel: Any, private val clsChannel: Class<*>) : SmartcardChannel {
            override fun transmit(command: ByteArray): ByteArray = clsChannel.getMethod("transmit", ByteArray::class.java).invoke(channel, command) as ByteArray
            override fun close() { clsChannel.getMethod("close").invoke(channel) }
        }
    }
}
