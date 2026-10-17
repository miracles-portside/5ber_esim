package work.portside.esim

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
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
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import rikka.shizuku.Shizuku
import work.portside.esim.euicc.EuiccCard
import work.portside.esim.euicc.ProfileInfo
import work.portside.esim.lpa.ActivationCode
import work.portside.esim.lpa.LpaSession
import work.portside.esim.lpa.Sgp22
import work.portside.esim.slot.SlotTransport
import work.portside.esim.ui.QrScanActivity
import work.portside.esim.usb.CardException
import work.portside.esim.usb.CardTransport
import work.portside.esim.usb.CcidReader
import work.portside.esim.usb.DemoCardTransport
import work.portside.esim.util.Hex
import work.portside.esim.util.Logs
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {

    companion object {
        private const val ACTION_USB_PERMISSION = "work.portside.esim.USB_PERMISSION"
        private const val SHIZUKU_PERMISSION_REQUEST = 4201
        private const val PHONE_STATE_REQUEST = 4202
        private const val REQ_SCAN = 4203
    }

    private lateinit var usbManager: UsbManager
    private lateinit var readerStatus: TextView
    private lateinit var btnConnect: Button
    private lateinit var btnDemo: Button
    private lateinit var btnSlot: Button
    private lateinit var btnAdd: Button
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
    private var lastUsbDevice: UsbDevice? = null

    @Volatile
    private var busy = false

    @Volatile
    private var demoMode = false

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == SHIZUKU_PERMISSION_REQUEST) {
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                appendLog("Shizuku permission granted")
                connectToSlot()
            } else {
                toast("Shizuku permission denied")
            }
        }
    }

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
                    lastUsbDevice = null
                    executor.execute { closeCard() }
                    clearCardUi()
                    updateReaderStatus()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        // Android 15+ draws apps edge-to-edge; keep the content clear of the status bar
        // and the navigation buttons ourselves.
        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        readerStatus = findViewById(R.id.reader_status)
        btnConnect = findViewById(R.id.btn_connect)
        btnDemo = findViewById(R.id.btn_demo)
        btnSlot = findViewById(R.id.btn_slot)
        btnAdd = findViewById(R.id.btn_add)
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
        btnSlot.setOnClickListener { onSlotClicked() }
        btnAdd.setOnClickListener { onAddClicked() }
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)

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
        appendLog("Ready. Use the card in the phone's SIM slot (Shizuku), plug in a USB CCID reader, or use demo mode.")
    }

    override fun onDestroy() {
        super.onDestroy()
        Logs.sink = null
        runCatching { Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener) }
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
        lastUsbDevice = device
        runBackground { doConnectToDevice(device) }
    }

    /** Must run on the background executor. */
    private fun doConnectToDevice(device: UsbDevice) {
        closeCard()
        demoMode = false
        val reader = CcidReader.open(usbManager, device)
        val euicc = EuiccCard(reader)
        euicc.connect()
        transport = reader
        card = euicc
        readCard()
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

    // ---------------------------------------------------------------- phone slot (Shizuku)

    private fun onSlotClicked() {
        if (busy) return
        if (!Shizuku.pingBinder()) {
            toast("Shizuku is not running")
            appendLog("Shizuku is not running. Open the Shizuku app and start it, or run via ADB:")
            appendLog("  sh /storage/emulated/0/Android/data/moe.shizuku.privileged.api/start.sh")
            return
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            appendLog("Requesting Shizuku permission…")
            Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST)
            return
        }
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            appendLog("Requesting phone-state permission…")
            requestPermissions(arrayOf(Manifest.permission.READ_PHONE_STATE), PHONE_STATE_REQUEST)
            return
        }
        connectToSlot()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PHONE_STATE_REQUEST) connectToSlot()
    }

    private fun connectToSlot() {
        if (activeSubs().isEmpty()) {
            toast("No active SIM subscriptions found — is the card inserted and enabled in Settings?")
            return
        }
        runBackground { doConnectToSlot() }
    }

    private fun activeSubs(): List<SubscriptionInfo> =
        runCatching {
            (getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager)?.activeSubscriptionInfoList
        }.getOrNull().orEmpty()

    /** Must run on the background executor. Probes every subscription for the card. */
    private fun doConnectToSlot() {
        val subs = activeSubs()
        if (subs.isEmpty()) {
            throw CardException("No active SIM subscription — the card may still be re-initializing")
        }
        closeCard()
        demoMode = false
        var foundTransport: CardTransport? = null
        var foundCard: EuiccCard? = null
        var lastError: String? = null
        for (info in subs) {
            try {
                val slotTransport = SlotTransport(applicationContext, info.subscriptionId, info.simSlotIndex)
                val slotCard = EuiccCard(slotTransport, refreshOnSwitch = true)
                slotCard.connect()
                foundTransport = slotTransport
                foundCard = slotCard
                break
            } catch (e: Exception) {
                lastError = e.message
                Logs.i("slot: subId ${info.subscriptionId} → ${e.message}")
            }
        }
        val t = foundTransport
            ?: throw CardException("Card not found in any SIM slot" + (lastError?.let { " ($it)" } ?: ""))
        val c = foundCard ?: throw CardException("Card not reachable")
        transport = t
        card = c
        readCard()
    }

    /** Must be called on the background executor. Updates the UI when done. */
    private fun readCard() {
        val euicc = card ?: throw IllegalStateException("No card connected")
        val eid = runCatching { euicc.getEid() }.getOrNull()
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
                .setMessage("\"${profile.displayName}\" is the active profile. Disable it?\n\nIf the card is in the phone, the phone will re-read the card automatically (a moment of no service).")
                .setPositiveButton("Disable") { _, _ ->
                    runAction("Disabling profile") { euicc -> euicc.disableProfile(profile.iccid) }
                }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            AlertDialog.Builder(this)
                .setTitle("Enable profile")
                .setMessage("Make \"${profile.displayName}\" the active eSIM on the card?\n\nIf the card is in the phone, the phone will re-read the card automatically (a moment of no service).")
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
            toast("Connect to the card first")
            return
        }
        runBackground {
            appendLog(label + "...")
            block(euicc)
            readCardResilient()
        }
    }

    /**
     * Reads the card, recovering from the SIM refresh that a profile switch triggers:
     * the modem re-initializes the card, the old logical channel dies (SW 6881/6F00)
     * and the subscription may briefly disappear. Wait for it and reconnect.
     */
    private fun readCardResilient() {
        try {
            readCard()
        } catch (e: Exception) {
            if (!looksLikeCardRefresh(e)) throw e
            appendLog("The card re-initialized after the profile switch — reconnecting…")
            reconnectAfterRefresh()
        }
    }

    private fun looksLikeCardRefresh(e: Exception): Boolean {
        val m = e.message ?: return false
        return m.contains("6881") || m.contains("6F00")
    }

    /** Must run on the background executor. Re-opens the previous transport and re-reads. */
    private fun reconnectAfterRefresh() {
        var last: Exception? = null
        repeat(6) {
            try {
                val device = lastUsbDevice
                if (device != null) doConnectToDevice(device) else doConnectToSlot()
                return
            } catch (e: Exception) {
                last = e
                Thread.sleep(1500)
            }
        }
        throw last ?: CardException("Card not reachable after the refresh — tap the read button again")
    }

    // ---------------------------------------------------------------- add eSIM profile

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_SCAN && resultCode == RESULT_OK) {
            val text = data?.getStringExtra(QrScanActivity.EXTRA_RESULT) ?: return
            appendLog("QR code decoded")
            startInstall(text)
        }
    }

    private fun onAddClicked() {
        if (busy) return
        AlertDialog.Builder(this)
            .setTitle(R.string.add_confirm_title)
            .setItems(
                arrayOf(getString(R.string.add_choose_scan), getString(R.string.add_choose_type)),
            ) { _, which ->
                when (which) {
                    0 -> startActivityForResult(Intent(this, QrScanActivity::class.java), REQ_SCAN)
                    1 -> promptActivationCode()
                }
            }
            .show()
    }

    private fun promptActivationCode() {
        val input = EditText(this).apply {
            hint = getString(R.string.add_code_hint)
            setSingleLine()
        }
        val wrapper = FrameLayout(this).apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.add_confirm_title)
            .setView(wrapper)
            .setPositiveButton("Continue") { _, _ -> startInstall(input.text.toString()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startInstall(rawCode: String) {
        val code = try {
            ActivationCode.parse(rawCode)
        } catch (e: Exception) {
            toast(e.message ?: "Could not read the activation code")
            return
        }
        appendLog("Activation code for ${code.smdp}${code.matchingId?.let { " ($it)" } ?: ""}")

        val cc = EditText(this).apply {
            hint = getString(R.string.add_cc_hint)
            setSingleLine()
        }
        val wrapper = FrameLayout(this).apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(cc)
        }
        val message = buildString {
            append("SM-DP+ server: ${code.smdp}\n")
            code.matchingId?.let { append("Matching ID: $it\n") }
            if (code.confirmationCodeRequired) append("The code says a confirmation code (password) is required.\n")
            if (card == null) append("\nNote: the card is not read yet — do that first.\n")
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.add_confirm_title)
            .setMessage(message)
            .setView(wrapper)
            .setPositiveButton(R.string.add_install) { _, _ ->
                runInstall(code, cc.text.toString().trim().ifEmpty { null })
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun runInstall(code: ActivationCode, confirmationCode: String?) {
        val euicc = card
        if (euicc == null) {
            toast("Read the card first (phone slot or USB reader), then try again")
            return
        }
        runBackground {
            val session = LpaSession(
                card = euicc,
                log = { message -> appendLog(message) },
                confirmProfile = { meta -> askInstallPreview(meta) },
            )
            val outcome = session.install(code, confirmationCode)
            readCardResilient()
            ui {
                val label = outcome.name ?: "The profile"
                toast("Installed: $label")
                outcome.iccid?.let { promptEnable(it) }
            }
        }
    }

    /** Runs on the background thread; posts a dialog and waits for the answer. */
    private fun askInstallPreview(meta: Sgp22.ProfileMeta?): Boolean {
        val latch = CountDownLatch(1)
        val confirmed = AtomicBoolean(false)
        ui {
            val message = buildString {
                if (meta != null) {
                    append("The server is ready to hand over this profile:\n\n")
                    append("Name: ${meta.displayName}\n")
                    meta.iccid?.let { append("ICCID ${Hex.grouped(it)}\n") }
                } else {
                    append("The server is ready to hand over a profile (no preview data available).\n")
                }
                append("\nInstall it onto the card now?")
            }
            AlertDialog.Builder(this)
                .setTitle("Confirm installation")
                .setMessage(message)
                .setPositiveButton(R.string.add_install) { _, _ ->
                    confirmed.set(true)
                    latch.countDown()
                }
                .setNegativeButton("Cancel") { _, _ -> latch.countDown() }
                .setOnCancelListener { latch.countDown() }
                .show()
        }
        latch.await()
        return confirmed.get()
    }

    private fun promptEnable(iccid: String) {
        AlertDialog.Builder(this)
            .setTitle("Make it the active eSIM?")
            .setMessage(
                "The new profile is on the card but not enabled yet. Enable it now?\n\n" +
                        "The phone will re-read the card (a moment of no service).",
            )
            .setPositiveButton("Enable now") { _, _ ->
                runAction("Enabling profile") { euicc -> euicc.enableProfile(iccid) }
            }
            .setNegativeButton("Later", null)
            .show()
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
            btnSlot.isEnabled = false
            btnAdd.isEnabled = false
        }
        executor.execute {
            try {
                block()
            } catch (e: Exception) {
                val message = e.message ?: e.toString()
                appendLog("ERROR: $message")
                if (message.contains("6F00", ignoreCase = true)) {
                    appendLog("Hint: the modem is refusing card writes right now. Toggle the built-in eSIM slot off/on under Settings → SIM cards, then retry.")
                }
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
        btnSlot.isEnabled = !busy
        btnAdd.isEnabled = !busy
    }

    private fun showCardInfo(eid: String?) {
        cardInfo.visibility = View.VISIBLE
        val prefix = if (demoMode) "DEMO — simulated card\n" else ""
        val eidText = if (eid != null) Hex.grouped(eid) else "not readable on this card"
        cardInfo.text = prefix + "Transport: ${transport?.name ?: "?"}\nEID: $eidText"
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
