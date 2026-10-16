package work.portside.esim

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import work.portside.esim.euicc.EuiccCard
import work.portside.esim.euicc.ProfileInfo
import work.portside.esim.usb.CardTransport
import work.portside.esim.usb.CcidReader
import work.portside.esim.usb.DemoCardTransport
import work.portside.esim.util.Hex
import work.portside.esim.util.Logs
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    companion object {
        private const val ACTION_USB_PERMISSION = "work.portside.esim.USB_PERMISSION"
    }

    private lateinit var usbManager: UsbManager
    private lateinit var readerStatus: TextView
    private lateinit var btnConnect: Button
    private lateinit var btnDemo: Button
    private lateinit var progress: ProgressBar
    private lateinit var cardInfo: TextView
    private lateinit var profilesHeader: TextView
    private lateinit var emptyHint: TextView
    private lateinit var profilesContainer: LinearLayout
    private lateinit var logView: TextView
    private lateinit var apduCheck: CheckBox

    private val executor = Executors.newSingleThreadExecutor()
    private val logBuffer = StringBuilder()
    private var transport: CardTransport? = null
    private var card: EuiccCard? = null
    private var profiles: List<ProfileInfo> = emptyList()

    @Volatile
    private var busy = false

    @Volatile
    private var demoMode = false

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_USB_PERMISSION) return
            @Suppress("DEPRECATION")
            val device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE) as? UsbDevice ?: return
            if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                connectToDevice(device)
            } else {
                appendLog("USB permission denied for ${device.deviceName}")
            }
        }
    }

    private val attachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            @Suppress("DEPRECATION")
            val device = intent?.getParcelableExtra(UsbManager.EXTRA_DEVICE) as? UsbDevice ?: return
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    if (CcidReader.isCcidDevice(device)) {
                        appendLog("Reader attached: ${device.productName ?: device.deviceName}")
                        requestPermissionIfNeeded(device)
                    }
                }

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    appendLog("Reader detached")
                    executor.execute { closeCard() }
                    clearCardUi()
                    updateReaderStatus()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        readerStatus = findViewById(R.id.reader_status)
        btnConnect = findViewById(R.id.btn_connect)
        btnDemo = findViewById(R.id.btn_demo)
        progress = findViewById(R.id.progress)
        cardInfo = findViewById(R.id.card_info)
        profilesHeader = findViewById(R.id.profiles_header)
        emptyHint = findViewById(R.id.empty_hint)
        profilesContainer = findViewById(R.id.profiles_container)
        logView = findViewById(R.id.log_view)
        apduCheck = findViewById(R.id.check_apdu)

        Logs.sink = { message -> appendLog(message) }
        apduCheck.setOnCheckedChangeListener { _, checked -> Logs.apduLogging = checked }

        btnConnect.setOnClickListener { onConnectClicked() }
        btnDemo.setOnClickListener { startDemo() }

        ContextCompat.registerReceiver(
            this,
            permissionReceiver,
            IntentFilter(ACTION_USB_PERMISSION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        ContextCompat.registerReceiver(
            this,
            attachReceiver,
            IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        updateReaderStatus()
        appendLog("Ready. Plug in a USB CCID reader with the card inserted, or use demo mode.")
    }

    override fun onDestroy() {
        super.onDestroy()
        Logs.sink = null
        try {
            unregisterReceiver(permissionReceiver)
            unregisterReceiver(attachReceiver)
        } catch (_: Exception) {
        }
        executor.execute { closeCard() }
        executor.shutdown()
    }

    // ---------------------------------------------------------------- actions

    private fun onConnectClicked() {
        val device = CcidReader.findReaders(usbManager).firstOrNull()
        if (device == null) {
            toast("No USB reader detected")
            return
        }
        requestPermissionIfNeeded(device)
    }

    private fun requestPermissionIfNeeded(device: UsbDevice) {
        if (usbManager.hasPermission(device)) {
            connectToDevice(device)
            return
        }
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(packageName)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags = flags or PendingIntent.FLAG_MUTABLE
        val pending = PendingIntent.getBroadcast(this, 0, intent, flags)
        appendLog("Requesting USB permission for ${device.deviceName}")
        usbManager.requestPermission(device, pending)
    }

    private fun connectToDevice(device: UsbDevice) {
        runBackground {
            closeCard()
            demoMode = false
            val reader = CcidReader.open(usbManager, device)
            val euicc = EuiccCard(reader)
            euicc.connect()
            transport = reader
            card = euicc
            readCard()
        }
    }

    private fun startDemo() {
        runBackground {
            closeCard()
            demoMode = true
            val demo = DemoCardTransport()
            val euicc = EuiccCard(demo)
            euicc.connect()
            transport = demo
            card = euicc
            appendLog("DEMO mode — no real card is being touched")
            readCard()
        }
    }

    /** Must be called on the background executor. Updates the UI when done. */
    private fun readCard() {
        val euicc = card ?: throw IllegalStateException("No card connected")
        val eid = euicc.getEid()
        val list = euicc.getProfiles()
        ui {
            showCardInfo(eid)
            showProfiles(list)
        }
        appendLog("Read ${list.size} profile(s) from the card")
    }

    /**
     * Closes the current card connection. Safe to call from the background executor:
     * it performs no UI work and captures the instances it is closing.
     */
    private fun closeCard() {
        runCatching { card?.disconnect() }
        runCatching { transport?.close() }
        card = null
        transport = null
        demoMode = false
    }

    private fun clearCardUi() {
        cardInfo.visibility = View.GONE
        profilesHeader.visibility = View.GONE
        emptyHint.visibility = View.GONE
        profilesContainer.removeAllViews()
        profiles = emptyList()
    }

    private fun refreshProfiles() {
        val euicc = card ?: throw IllegalStateException("No card connected")
        showProfiles(euicc.getProfiles())
    }

    // ---------------------------------------------------------------- profile actions

    private fun onToggle(profile: ProfileInfo) {
        if (busy) return
        if (profile.isEnabled) {
            if (profiles.size <= 1) {
                toast("This is the only profile on the card. Disabling it can make some phones stop recognizing the card — add another profile first.")
                return
            }
            AlertDialog.Builder(this)
                .setTitle("Disable profile")
                .setMessage("\"${profile.displayName}\" is the active profile. Disable it?\n\nIf the card is inserted in a phone, restart the phone afterwards.")
                .setPositiveButton("Disable") { _, _ ->
                    runAction("Disabling profile") { euicc -> euicc.disableProfile(profile.iccid) }
                }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            AlertDialog.Builder(this)
                .setTitle("Enable profile")
                .setMessage("Make \"${profile.displayName}\" the active eSIM on the card?\n\nIf the card is inserted in a phone, restart the phone afterwards.")
                .setPositiveButton("Enable") { _, _ ->
                    runAction("Enabling profile") { euicc -> euicc.enableProfile(profile.iccid) }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun onRename(profile: ProfileInfo) {
        if (busy) return
        val input = EditText(this).apply {
            setText(profile.nickname ?: "")
            hint = "Nickname"
            setSingleLine()
        }
        val wrapper = FrameLayout(this).apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("Rename profile")
            .setView(wrapper)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                runAction("Saving nickname") { euicc -> euicc.setNickname(profile.iccid, name) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun onDelete(profile: ProfileInfo) {
        if (busy) return
        if (profiles.size <= 1) {
            toast("This is the only profile on the card — deleting it would leave the card empty.")
            return
        }
        val extra = if (profile.isEnabled) {
            "\n\nWARNING: this is the currently active profile."
        } else {
            ""
        }
        AlertDialog.Builder(this)
            .setTitle("Delete profile")
            .setMessage(
                "Permanently delete \"${profile.displayName}\"?\n" +
                        "ICCID ${Hex.grouped(profile.iccid)}\n\n" +
                        "This cannot be undone, and most eSIM activation codes cannot be reused." + extra,
            )
            .setPositiveButton("Delete") { _, _ ->
                runAction("Deleting profile") { euicc -> euicc.deleteProfile(profile.iccid) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun runAction(label: String, block: (EuiccCard) -> Unit) {
        val euicc = card
        if (euicc == null) {
            toast("Connect a reader first")
            return
        }
        runBackground {
            appendLog(label + "...")
            block(euicc)
            readCard()
        }
    }

    // ---------------------------------------------------------------- UI plumbing

    private fun runBackground(block: () -> Unit) {
        if (busy) {
            toast("An operation is still running — hold on")
            return
        }
        busy = true
        ui {
            progress.visibility = View.VISIBLE
            btnConnect.isEnabled = false
            btnDemo.isEnabled = false
        }
        executor.execute {
            try {
                block()
            } catch (e: Exception) {
                val message = e.message ?: e.toString()
                appendLog("ERROR: $message")
                ui { toast(message) }
            } finally {
                ui {
                    busy = false
                    progress.visibility = View.GONE
                    updateReaderStatus()
                }
            }
        }
    }

    private fun updateReaderStatus() {
        val readers = CcidReader.findReaders(usbManager)
        readerStatus.text = if (readers.isEmpty()) {
            getString(R.string.reader_none)
        } else {
            val device = readers.first()
            val label = device.productName?.takeIf { it.isNotBlank() } ?: device.deviceName
            getString(R.string.reader_found, label)
        }
        btnConnect.isEnabled = readers.isNotEmpty() && !busy
        btnDemo.isEnabled = !busy
    }

    private fun showCardInfo(eid: String) {
        cardInfo.visibility = View.VISIBLE
        val prefix = if (demoMode) "DEMO — simulated card\n" else ""
        cardInfo.text = prefix + "Reader: ${transport?.name ?: "?"}\nEID: ${Hex.grouped(eid)}"
    }

    private fun showProfiles(list: List<ProfileInfo>) {
        profiles = list
        profilesHeader.visibility = View.VISIBLE
        emptyHint.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        profilesContainer.removeAllViews()
        list.forEach { profilesContainer.addView(buildProfileRow(it)) }
    }

    private fun buildProfileRow(profile: ProfileInfo): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(10))
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.row_bg)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) }
        }

        val stateText = TextView(this).apply {
            text = if (profile.isEnabled) "ENABLED" else "DISABLED"
            textSize = 12f
            letterSpacing = 0.08f
            setTypeface(null, Typeface.BOLD)
            setTextColor(if (profile.isEnabled) 0xFF0F766E.toInt() else 0xFF8A8A8A.toInt())
        }
        val title = TextView(this).apply {
            text = profile.displayName
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
        }
        row.addView(title)
        row.addView(stateText)
        row.addView(TextView(this).apply {
            text = "ICCID ${Hex.grouped(profile.iccid)}"
            textSize = 13f
        })
        profile.serviceProviderName?.takeIf { it.isNotBlank() }?.let { spn ->
            row.addView(TextView(this).apply {
                text = "Provider: $spn"
                textSize = 13f
            })
        }

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
        }
        buttons.addView(Button(this).apply {
            text = if (profile.isEnabled) "Disable" else "Enable"
            setOnClickListener { onToggle(profile) }
        })
        buttons.addView(Button(this).apply {
            text = "Rename"
            setOnClickListener { onRename(profile) }
        })
        buttons.addView(Button(this).apply {
            text = "Delete"
            setOnClickListener { onDelete(profile) }
        })
        row.addView(buttons)
        return row
    }

    private fun appendLog(message: String) {
        logView.post {
            logBuffer.append(message).append('\n')
            if (logBuffer.length > 4000) logBuffer.delete(0, logBuffer.length - 4000)
            logView.text = logBuffer
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun ui(block: () -> Unit) = runOnUiThread(block)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
