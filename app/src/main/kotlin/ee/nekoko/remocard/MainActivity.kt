package ee.nekoko.remocard

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.os.Build
import android.se.omapi.SEService
import android.se.omapi.Reader

class MainActivity : AppCompatActivity() {
    private lateinit var statusText: TextView
    private lateinit var actionButton: Button
    private lateinit var portInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var singleChannelCheckbox: CheckBox
    private lateinit var autoStartCheckbox: CheckBox
    private lateinit var readerWhitelistContainer: LinearLayout
    private lateinit var clientsContainer: LinearLayout
    private var seService: SEService? = null
    
    private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val uiRefreshTask = object : Runnable {
        override fun run() {
            refreshClientList()
            uiHandler.postDelayed(this, 5000)
        }
    }

    private var isServiceRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val prefs = getSharedPreferences("remocard_prefs", Context.MODE_PRIVATE)

        val root = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#121212")) // Dark background
            isFillViewport = true
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 48, 64, 64)
            gravity = Gravity.CENTER_HORIZONTAL
        }

        // Add Header/Title
        val titleText = TextView(this).apply {
            text = "RemoCard"
            textSize = 34f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#FF9800")) // Orange
            setPadding(0, 32, 0, 8)
            gravity = Gravity.CENTER
        }
        val subtitleText = TextView(this).apply {
            text = "REMOTE SIM ADAPTER"
            textSize = 10f
            letterSpacing = 0.2f
            setTextColor(Color.parseColor("#FF9800"))
            setPadding(0, 0, 0, 48)
            gravity = Gravity.CENTER
        }
        layout.addView(titleText)
        layout.addView(subtitleText)

        statusText = TextView(this).apply {
            text = "RemoCard Server Offline"
            textSize = 20f
            setTextColor(Color.GRAY)
            setPadding(0, 0, 0, 48)
            gravity = Gravity.CENTER
        }
        
        actionButton = Button(this).apply {
            text = "Start Server"
            setAllCaps(false)
            textSize = 16f
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 64)
            }
            setOnClickListener {
                if (isServiceRunning) {
                    stopRemoService()
                    updateUi(false)
                } else {
                    saveSettings()
                    startRemoService()
                    updateUi(true)
                }
            }
        }

        // Settings section
        val settingsTitle = TextView(this).apply {
            text = "SERVER SETTINGS"
            textSize = 12f
            setTextColor(Color.DKGRAY)
            setPadding(0, 32, 0, 16)
        }

        portInput = createLabeledInput("Server Port", "33777", InputType.TYPE_CLASS_NUMBER)
        portInput.setText(prefs.getInt("port", 33777).toString())

        passwordInput = createLabeledInput("Connection Password", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        passwordInput.setText(prefs.getString("password", ""))

        singleChannelCheckbox = createCheckBox("Restrict to Single Channel", prefs.getBoolean("single_channel", false))
        singleChannelCheckbox.setOnCheckedChangeListener { _, _ -> saveSettings() }

        autoStartCheckbox = createCheckBox("Auto-start on Boot", prefs.getBoolean("auto_start", true))
        autoStartCheckbox.setOnCheckedChangeListener { _, _ -> saveSettings() }

        val whitelistTitle = TextView(this).apply {
            text = "ALLOWED READERS (Defaults to all if none selected)"
            textSize = 12f
            setTextColor(Color.DKGRAY)
            setPadding(0, 32, 0, 16)
        }
        readerWhitelistContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 32)
        }

        val clientsTitle = TextView(this).apply {
            text = "CONNECTED CLIENTS (Last 10m)"
            textSize = 12f
            setTextColor(Color.parseColor("#FF9800"))
            setPadding(0, 32, 0, 16)
        }
        clientsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 32)
        }

        layout.addView(statusText)
        layout.addView(actionButton)
        layout.addView(settingsTitle)
        layout.addView(createLabel("Port"))
        layout.addView(portInput)
        layout.addView(createLabel("Password (Leave empty for none)"))
        layout.addView(passwordInput)
        layout.addView(singleChannelCheckbox)
        layout.addView(autoStartCheckbox)
        layout.addView(whitelistTitle)
        layout.addView(readerWhitelistContainer)
        layout.addView(clientsTitle)
        layout.addView(clientsContainer)

        // Init SE Service to list readers
        seService = SEService(this, java.util.concurrent.Executors.newSingleThreadExecutor()) {
            runOnUiThread { refreshReaderWhitelist() }
        }

        // Add text change listeners for auto-save
        val textWatcher = object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { saveSettings() }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        }
        portInput.addTextChangedListener(textWatcher)
        passwordInput.addTextChangedListener(textWatcher)

        root.addView(layout)
        setContentView(root)
        
        // Start UI refresh task
        uiHandler.post(uiRefreshTask)
        
        // Auto-start on launch if enabled
        if (prefs.getBoolean("auto_start", true)) {
            startRemoService()
            updateUi(true)
        }
    }

    private fun createLabel(text: String) = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(Color.LTGRAY)
        setPadding(0, 16, 0, 8)
    }

    private fun createLabeledInput(label: String, hint: String, inputType: Int): EditText {
        return EditText(this).apply {
            this.hint = hint
            this.inputType = inputType
            setTextColor(Color.WHITE)
            setHintTextColor(Color.DKGRAY)
            background.setColorFilter(Color.GRAY, android.graphics.PorterDuff.Mode.SRC_ATOP)
        }
    }

    private fun createCheckBox(text: String, checked: Boolean) = CheckBox(this).apply {
        this.text = text
        this.isChecked = checked
        setTextColor(Color.LTGRAY)
        setPadding(0, 24, 0, 24)
        // Set checkbox tint to orange
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            buttonTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#FF9800"))
        }
    }

    private fun refreshClientList() {
        if (!::clientsContainer.isInitialized) return
        clientsContainer.removeAllViews()
        RemoService.cleanOldClients()
        val clients = RemoService.activeClients
        val now = System.currentTimeMillis()

        if (clients.isEmpty()) {
            clientsContainer.addView(TextView(this).apply {
                text = "No active clients"
                textSize = 13f
                setTextColor(Color.DKGRAY)
                gravity = Gravity.CENTER
            })
            return
        }

        clients.forEach { (ip, timestamp) ->
            val secondsAgo = (now - timestamp) / 1000
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 16, 0, 16)
            }
            val ipText = TextView(this).apply {
                text = ip
                weight = 1f
                setTextColor(Color.WHITE)
                textSize = 14f
            }
            val timeText = TextView(this).apply {
                text = "${secondsAgo}s ago"
                setTextColor(if (secondsAgo < 30) Color.parseColor("#4CAF50") else Color.GRAY)
                textSize = 12f
            }
            row.addView(ipText)
            row.addView(timeText)
            clientsContainer.addView(row)
        }
    }

    private fun LinearLayout.addView(view: View, weight: Float) {
        val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight)
        view.layoutParams = params
        this.addView(view)
    }

    private var TextView.weight: Float
        get() = 0f
        set(value) {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, value)
        }

    private fun refreshReaderWhitelist() {
        readerWhitelistContainer.removeAllViews()
        val readers = seService?.readers ?: emptyArray<Reader>()
        val prefs = getSharedPreferences("remocard_prefs", Context.MODE_PRIVATE)
        val allowed = prefs.getStringSet("allowed_readers", null) ?: emptySet<String>()

        readers.forEach { reader ->
            val cb = createCheckBox(reader.name, allowed.contains(reader.name))
            cb.setOnCheckedChangeListener { _, _ -> saveSettings() }
            readerWhitelistContainer.addView(cb)
        }
    }

    private fun saveSettings() {
        val prefs = getSharedPreferences("remocard_prefs", Context.MODE_PRIVATE)
        val allowed = mutableSetOf<String>()
        for (i in 0 until readerWhitelistContainer.childCount) {
            val v = readerWhitelistContainer.getChildAt(i)
            if (v is CheckBox && v.isChecked) {
                allowed.add(v.text.toString())
            }
        }

        prefs.edit().apply {
            putInt("port", portInput.text.toString().toIntOrNull() ?: 33777)
            putString("password", passwordInput.text.toString())
            putStringSet("allowed_readers", allowed)
            putBoolean("single_channel", singleChannelCheckbox.isChecked)
            putBoolean("auto_start", autoStartCheckbox.isChecked)
            apply()
        }
    }

    private fun updateUi(running: Boolean) {
        isServiceRunning = running
        val prefs = getSharedPreferences("remocard_prefs", Context.MODE_PRIVATE)
        val port = prefs.getInt("port", 33777)
        if (running) {
            statusText.text = "Server running on :$port"
            statusText.setTextColor(Color.parseColor("#4CAF50"))
            actionButton.text = "Stop Server"
            actionButton.setBackgroundColor(Color.parseColor("#E53935"))
        } else {
            statusText.text = "Server Offline"
            statusText.setTextColor(Color.GRAY)
            actionButton.text = "Start Server"
            actionButton.setBackgroundColor(Color.parseColor("#1E88E5"))
        }
    }

    private fun startRemoService() {
        val intent = Intent(this, RemoService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopRemoService() {
        val intent = Intent(this, RemoService::class.java)
        stopService(intent)
    }

    override fun onDestroy() {
        uiHandler.removeCallbacks(uiRefreshTask)
        seService?.shutdown()
        super.onDestroy()
    }
}
