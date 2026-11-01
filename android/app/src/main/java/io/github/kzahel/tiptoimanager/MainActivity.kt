package io.github.kzahel.tiptoimanager

import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.storage.StorageVolume
import android.provider.DocumentsContract
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import androidx.drawerlayout.widget.DrawerLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.GravityCompat
import java.util.Locale
import java.text.DateFormat
import java.util.Date

private enum class Screen(private val titleId: Int) {
    PENS(R.string.label_my_pens), CONNECT(R.string.ui_connect_a_pen), PEN(R.string.label_pen_details), COLLECTION(R.string.label_book_selections), PLAN(R.string.label_review_selection), INSTALLED(R.string.label_installed_books), STORAGE(R.string.label_storage),
    BROWSE(R.string.label_browse_books), DOWNLOADS(R.string.label_downloads), BOOK(R.string.label_book_details),
    PHONE_STORAGE(R.string.label_phone_storage), TRANSFERS(R.string.label_transfers), HELP(R.string.label_help_about);
    val title: String get() = t(titleId)
}

class MainActivity : ComponentActivity() {
    private val app get() = application as ManagerApp
    private val green = Color.rgb(0, 107, 94)
    private val ink = Color.rgb(25, 45, 39)
    private val muted = Color.rgb(96, 112, 103)
    private val soft = Color.rgb(229, 242, 235)
    private val pageColor = Color.rgb(246, 248, 245)
    private lateinit var drawer: DrawerLayout
    private lateinit var root: LinearLayout
    private lateinit var drawerConnection: TextView
    private lateinit var body: LinearLayout
    private val navItems = mutableMapOf<Screen, LinearLayout>()
    private val backStack = mutableListOf<Screen>()
    private val queries = mutableMapOf<Screen, String>()
    private var screen = Screen.PENS
    private var selectedKey: String? = null
    private var selectedFile: String? = null
    private var progressLabel: TextView? = null
    private var progressBar: ProgressBar? = null
    private var busyLabel: TextView? = null
    private var infoDialog: AlertDialog? = null
    private var penSort = PenSort.TITLE
    private var lastConnectedKey: String? = null
    private var connectTargetId: String? = null
    private var connectIgnoredKey: String? = null
    private var connectReading = false
    private var connectError: String? = null
    private var connectCanceled = false
    private var usbState = UsbConnectionState(false, emptyList())
    private val connectionHandler = Handler(Looper.getMainLooper())
    private val connectionPoll = object : Runnable {
        override fun run() {
            if (screen == Screen.CONNECT) {
                val next = UsbConnection.observe(this@MainActivity)
                if (!next.attached && next.drives.isEmpty()) connectIgnoredKey = null
                if (next != usbState) { usbState = next; render() }
            }
            connectionHandler.postDelayed(this, 1000)
        }
    }

    override fun attachBaseContext(base: android.content.Context) { super.attachBaseContext(UiText.context(base)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        penSort = runCatching { PenSort.valueOf(getPreferences(MODE_PRIVATE).getString("penSort", "TITLE")!!) }.getOrDefault(PenSort.TITLE)
        savedInstanceState?.let { state ->
            lastConnectedKey = state.getString("lastConnectedKey")
            screen = runCatching { Screen.valueOf(state.getString("screen")!!) }.getOrDefault(Screen.PENS)
            selectedKey = state.getString("bookKey"); selectedFile = state.getString("bookFile")
            connectTargetId = state.getString("connectTargetId")
            connectIgnoredKey = state.getString("connectIgnoredKey")
            connectReading = state.getBoolean("connectReading")
            connectCanceled = state.getBoolean("connectCanceled")
            state.getStringArrayList("backStack")?.forEach { name ->
                runCatching { Screen.valueOf(name) }.getOrNull()?.let { backStack.add(it) }
            }
            Screen.entries.forEach { queries[it] = state.getString("query_${it.name}").orEmpty() }
        }
        drawer = DrawerLayout(this).apply { setScrimColor(0x660C251D) }
        root = column().apply { setBackgroundColor(pageColor) }
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                val keyboard = insets.getInsets(WindowInsets.Type.ime())
                view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, keyboard.bottom))
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        drawer.addView(root, DrawerLayout.LayoutParams(-1, -1))
        createDrawer()
        setContentView(drawer)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { goBack() }
        })
        render()
    }

    override fun onResume() {
        super.onResume()
        app.changed = { if (!isFinishing && !isDestroyed) { connectionNavigation(); render() } }
        app.startConnectionWatch()
        usbState = UsbConnection.observe(this)
        connectionHandler.post(connectionPoll)
        app.progressed = { updateProgress() }
        app.metadataChanged = { article ->
            if (screen == Screen.BOOK && selectedBooks().any { it.article == article }) {
                if (infoDialog?.isShowing == true) infoDialog?.setMessage(fileInfoMessage())
                else {
                    val position = (body.parent as? ScrollView)?.scrollY ?: 0
                    render()
                    (body.parent as? ScrollView)?.post { (body.parent as? ScrollView)?.scrollTo(0, position) }
                }
            }
        }
        connectionNavigation()
        render()
    }

    private fun connectionNavigation() {
        val key = app.displayConnection?.identity?.key
        if (screen == Screen.CONNECT && key != null && key != lastConnectedKey && !app.busy) {
            connectError = null; connectCanceled = false
        }
        if (key != null && key != lastConnectedKey && screen == Screen.PENS) {
            backStack.clear(); backStack.add(Screen.PENS); screen = Screen.PEN
        }
        lastConnectedKey = key
    }

    override fun onPause() {
        connectionHandler.removeCallbacks(connectionPoll)
        app.stopConnectionWatch()
        app.changed = null; app.progressed = null; app.metadataChanged = null
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("screen", screen.name)
        outState.putString("lastConnectedKey", lastConnectedKey)
        outState.putString("bookKey", selectedKey); outState.putString("bookFile", selectedFile)
        outState.putString("connectTargetId", connectTargetId)
        outState.putString("connectIgnoredKey", connectIgnoredKey)
        outState.putBoolean("connectReading", connectReading)
        outState.putBoolean("connectCanceled", connectCanceled)
        outState.putStringArrayList("backStack", ArrayList(backStack.map { it.name }))
        queries.forEach { (key, value) -> outState.putString("query_${key.name}", value) }
        super.onSaveInstanceState(outState)
    }

    private fun goBack() {
        when {
            drawer.isDrawerOpen(GravityCompat.START) -> drawer.closeDrawer(GravityCompat.START)
            backStack.isNotEmpty() -> { screen = backStack.removeAt(backStack.lastIndex); render() }
            screen != Screen.PENS -> navigate(Screen.PENS, false)
            else -> finish()
        }
    }

    private fun navigate(destination: Screen, push: Boolean = true) {
        hideKeyboard()
        if (push) backStack.add(screen) else backStack.clear()
        screen = destination
        drawer.closeDrawer(GravityCompat.START)
        render()
    }

    private fun createDrawer() {
        val content = column().apply {
            setPadding(dp(16), dp(48), dp(16), dp(24)); setBackgroundColor(Color.WHITE)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        val brand = row().apply { setPadding(dp(12), dp(8), dp(12), dp(24)) }
        brand.addView(icon(UiIcon.PEN, green, 30))
        brand.addView(text(t(R.string.app_name), 21, ink, true), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12) })
        content.addView(brand)
        fun section(label: String) { content.addView(text(label, 11, muted, true).apply {
            letterSpacing = .12f; setPadding(dp(16), dp(22), 0, dp(8))
        }) }
        fun item(destination: Screen, symbol: UiIcon) {
            val line = row().apply {
                setPadding(dp(16), dp(15), dp(16), dp(15))
                contentDescription = destination.title
                isClickable = true; isFocusable = true
                setOnClickListener { navigate(destination, false) }
            }
            line.addView(icon(symbol, green, 22))
            line.addView(text(destination.title, 16, ink, true), LinearLayout.LayoutParams(-1, -2).apply { marginStart = dp(18) })
            content.addView(line, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })
            navItems[destination] = line
        }
        section(t(R.string.ui_device)); item(Screen.PENS, UiIcon.PEN)
        section(t(R.string.ui_library)); item(Screen.BROWSE, UiIcon.BOOKS); item(Screen.DOWNLOADS, UiIcon.DOWNLOAD)
        item(Screen.PHONE_STORAGE, UiIcon.STORAGE); item(Screen.COLLECTION, UiIcon.BOOKS)
        section(t(R.string.ui_activity)); item(Screen.TRANSFERS, UiIcon.TRANSFER)
        section(t(R.string.ui_support)); item(Screen.HELP, UiIcon.INFO)
        drawerConnection = text("", 13, muted).apply { setPadding(dp(16), dp(32), 0, 0) }
        content.addView(drawerConnection)
        val scroll = ScrollView(this).apply { addView(content); setBackgroundColor(Color.WHITE) }
        drawer.addView(scroll, DrawerLayout.LayoutParams(dp(300), -1, GravityCompat.START))
    }

    private fun render() {
        if (app.busy) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        root.removeAllViews()
        progressLabel = null; progressBar = null; busyLabel = null
        val active = if (screen in listOf(Screen.CONNECT, Screen.PEN, Screen.INSTALLED, Screen.STORAGE, Screen.PLAN)) Screen.PENS else screen
        navItems.forEach { (destination, view) ->
            view.isSelected = destination == active
            view.background = rounded(if (destination == active) soft else Color.WHITE, 14)
        }
        if (app.demoMode) {
            val banner = row().apply { setPadding(dp(20), dp(8), dp(20), dp(8)); setBackgroundColor(soft) }
            banner.addView(text(t(R.string.ui_demo_banner), 13, green, true), LinearLayout.LayoutParams(0, -2, 1f))
            banner.addView(Button(this).apply { text = t(R.string.ui_exit_demo); isAllCaps = false; setOnClickListener { app.setDemo(false); navigate(Screen.PENS, false) } })
            root.addView(banner)
        }
        drawerConnection.text = app.connectedName?.let { t(R.string.ui_connected_name, it) } ?: t(R.string.ui_no_pen_connected)
        toolbar()
        if (app.busy && screen != Screen.TRANSFERS && screen != Screen.CONNECT) {
            val strip = row().apply {
                setPadding(dp(20), dp(10), dp(16), dp(10)); setBackgroundColor(soft)
                isClickable = true; isFocusable = true
                setOnClickListener { navigate(Screen.TRANSFERS, false) }
            }
            strip.addView(ProgressBar(this).apply { isIndeterminate = true }, LinearLayout.LayoutParams(dp(20), dp(20)))
            busyLabel = text(if (app.transfer?.running == true) t(R.string.ui_transfer_in_progress) else app.status, 13, green)
            strip.addView(busyLabel, LinearLayout.LayoutParams(-1, -2).apply { marginStart = dp(12) })
            root.addView(strip)
        } else if (app.failure && screen != Screen.CONNECT && !(screen == Screen.TRANSFERS && app.transfer?.failed == true)) {
            val problem = text(t(R.string.ui_action_failed_view_details), 13, 0xFF9B2A22.toInt(), true).apply {
                setPadding(dp(20), dp(12), dp(20), dp(12)); setBackgroundColor(0xFFFDECE8.toInt())
                isClickable = true; isFocusable = true
                setOnClickListener { showMessage(t(R.string.ui_couldn_t_complete_the_action), app.status) }
            }
            root.addView(problem)
        }
        when (screen) {
            Screen.BROWSE, Screen.INSTALLED, Screen.DOWNLOADS -> listScreen()
            else -> {
                scrollBody()
                when (screen) {
                    Screen.PENS -> pensScreen()
                    Screen.CONNECT -> connectScreen()
                    Screen.PEN -> penScreen()
                    Screen.COLLECTION -> collectionScreen()
                    Screen.PLAN -> planScreen()
                    Screen.STORAGE -> storageScreen()
                    Screen.PHONE_STORAGE -> phoneStorageScreen()
                    Screen.BOOK -> bookScreen()
                    Screen.TRANSFERS -> transfersScreen()
                    Screen.HELP -> helpScreen()
                    else -> Unit
                }
            }
        }
    }

    private fun toolbar() {
        val header = row().apply { setPadding(dp(8), dp(4), dp(8), dp(4)); setBackgroundColor(Color.WHITE) }
        val child = screen in listOf(Screen.CONNECT, Screen.PEN, Screen.INSTALLED, Screen.STORAGE, Screen.BOOK, Screen.PLAN)
        header.addView(iconButton(if (child) UiIcon.BACK else UiIcon.MENU, if (child) t(R.string.ui_back) else t(R.string.ui_open_navigation_menu)) {
            if (child) goBack() else { hideKeyboard(); drawer.openDrawer(GravityCompat.START) }
        })
        header.addView(text(if (screen == Screen.PEN) app.viewedProfile?.name ?: screen.title else screen.title, 22, ink, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
        when (screen) {
            Screen.PENS, Screen.PEN, Screen.INSTALLED, Screen.STORAGE, Screen.PLAN -> if (app.tree != null) header.addView(iconButton(UiIcon.REFRESH, t(R.string.ui_refresh_pen)) { app.refreshPen() }.apply { isEnabled = !app.busy })
            Screen.BROWSE -> header.addView(iconButton(UiIcon.REFRESH, t(R.string.ui_refresh_catalog)) { app.refreshCatalog() }.apply { isEnabled = !app.busy })
            Screen.BOOK -> {
                header.addView(iconButton(UiIcon.INFO, t(R.string.ui_file_details)) { fileDetails() })
                if (selectedEntry() != null || selectedFile?.let { app.backup(it) } != null) {
                    val more = iconButton(UiIcon.MORE, t(R.string.ui_more_book_actions)) {}
                    more.isEnabled = !app.busy
                    more.setOnClickListener { bookMenu(more) }
                    header.addView(more)
                }
            }
            else -> Unit
        }
        root.addView(header)
        root.addView(View(this).apply { setBackgroundColor(0xFFE3E9E4.toInt()) }, LinearLayout.LayoutParams(-1, dp(1)))
    }

    private fun scrollBody() {
        body = column().apply { setPadding(dp(20), dp(20), dp(20), dp(24)) }
        val scroll = ScrollView(this).apply { addView(body); clipToPadding = false }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun pensScreen() {
        if (app.knownProfiles.isEmpty()) emptyState(UiIcon.PEN, t(R.string.ui_add_your_first_pen), t(R.string.ui_connect_by_usb_and_choose_its_folder))
        app.knownProfiles.sortedByDescending { it.identity.key == app.displayConnection?.identity?.key }.forEach { profile ->
            val connected = profile.identity.key == app.displayConnection?.identity?.key
            body.addView(navRow(UiIcon.PEN, profile.name, if (connected) p(R.plurals.ui_connected_1_d_audio_files, profile.files.size, profile.files.size)
                else p(R.plurals.ui_last_seen_1_s_2_d_files, profile.files.size, dateTime(profile.lastSeen), profile.files.size)) {
                app.selectProfile(profile.id); navigate(Screen.PEN)
            })
        }
        gap(body, 16)
        body.addView(action(if (app.knownProfiles.isEmpty()) t(R.string.ui_connect_a_pen) else t(R.string.ui_connect_another_pen)) { startConnection() })
        body.addView(action(t(R.string.ui_demo), false) { app.setDemo(true); navigate(Screen.PENS, false) })
        if (app.tree != null) body.addView(action(t(R.string.ui_scan_connected_pen), false) { app.refreshPen() })
    }

    private fun penScreen() {
        val profile = app.viewedProfile
        if (profile == null) { emptyState(UiIcon.PEN, t(R.string.ui_connect_your_pen)); body.addView(action(t(R.string.ui_connect_a_pen)) { startConnection() }); return }
        val connected = app.viewedPen != null
        val hero = card(if (connected) green else Color.WHITE)
        val color = if (connected) Color.WHITE else ink
        hero.addView(icon(UiIcon.PEN, if (connected) Color.WHITE else green, 44)); gap(hero, 16)
        hero.addView(text(profile.name, 28, color, true)); gap(hero, 8)
        hero.addView(text(if (connected) t(R.string.ui_connected_by_usb) else t(R.string.ui_saved_inventory_1_s, dateTime(profile.lastSeen)), 14,
            if (connected) 0xFFD0EEE4.toInt() else muted))
        body.addView(hero); gap(body, 20)
        if (!profile.identity.hasSerial) { body.addView(text(t(R.string.ui_serial_unavailable_identity_unverified), 14, muted)); gap(body, 12) }
        body.addView(navRow(UiIcon.BOOKS, t(R.string.ui_installed_books), if (connected) t(R.string.ui_audio_files_1_d, profile.files.size) else t(R.string.ui_last_observed_audio_files_1_d, profile.files.size)) { navigate(Screen.INSTALLED) })
        body.addView(navRow(UiIcon.STORAGE, t(R.string.ui_storage), profile.free?.let { if (connected) t(R.string.ui_1_s_free, size(it)) else t(R.string.ui_1_s_free_last_observed, size(it)) } ?: t(R.string.ui_view_storage)) { navigate(Screen.STORAGE) })
        body.addView(navRow(UiIcon.BOOKS, t(R.string.ui_selected_books), p(R.plurals.ui_1_d_audio_files_review_changes, app.targets(profile).size, app.targets(profile).size)) { navigate(Screen.PLAN) })
        body.addView(action(t(R.string.ui_rename_pen), false) { renamePen(profile) })
        body.addView(action(t(R.string.ui_pen_information), false) { showMessage(profile.name, listOfNotNull(
            profile.identity.serialSuffix?.let { t(R.string.ui_serial_ending_1_s, it) }, profile.identity.firmware?.let { t(R.string.ui_firmware, it) },
            profile.identity.language, profile.identity.processor,
            if (!profile.identity.hasSerial) t(R.string.ui_serial_unavailable_this_profile_uses_an_unverified_metadata_fingerpr) else null
        ).joinToString("\n")) })
        if (!connected) body.addView(action(t(R.string.ui_connect_this_pen)) { connectionHelp(profile) })
        body.addView(action(t(R.string.ui_connect_setup), false) { startConnection(profile) })
        if (!connected) body.addView(action(t(R.string.ui_forget_profile), false) {
            AlertDialog.Builder(this).setTitle(t(R.string.ui_forget_1_s, profile.name))
                .setMessage(t(R.string.ui_removes_this_phone_s_name_saved_inventory_and_individual_selection_b))
                .setNegativeButton(t(R.string.ui_cancel), null).setPositiveButton(t(R.string.ui_forget)) { _, _ -> app.forgetProfile(profile.id); navigate(Screen.PENS, false) }.show()
        })
    }

    private fun storageScreen() {
        val profile = app.viewedProfile ?: return
        val summary = card()
        summary.addView(icon(UiIcon.STORAGE, green, 36)); gap(summary, 16)
        summary.addView(text(profile.free?.let(::size) ?: t(R.string.ui_unknown), 36, ink, true))
        summary.addView(text(if (app.viewedPen != null) t(R.string.ui_free_space_1_s, profile.name) else t(R.string.ui_last_observed_free_space_1_s, dateTime(profile.lastSeen)), 15, muted))
        body.addView(summary); gap(body, 20)
        body.addView(navRow(UiIcon.BOOKS, t(R.string.ui_audio_files), p(R.plurals.ui_1_d_files_2_s, profile.files.size, profile.files.size, size(profile.bytes))) { navigate(Screen.INSTALLED) })
        body.addView(action(t(R.string.ui_review_oldest_files), false) { reviewOldest() })
    }

    private fun renamePen(profile: PenProfile) {
        val input = EditText(this).apply { setSingleLine(true); setText(profile.name); selectAll(); contentDescription = t(R.string.ui_pen_name) }
        val dialog = AlertDialog.Builder(this).setTitle(t(R.string.ui_rename_pen)).setView(input)
            .setNegativeButton(t(R.string.ui_cancel), null).setPositiveButton(t(R.string.ui_save), null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = runCatching { PenProfiles.validName(input.text.toString()) }.getOrElse { input.error = it.message; return@setOnClickListener }
                app.renameProfile(profile.id, name); dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun connectionHelp(profile: PenProfile) {
        startConnection(profile)
    }

    private fun startConnection(profile: PenProfile? = null) {
        if (app.demoMode) { showMessage(t(R.string.ui_demo_banner), t(R.string.ui_demo_readonly)); return }
        connectTargetId = profile?.id
        connectIgnoredKey = if (profile == null) app.pen?.identity?.key else null
        connectReading = false; connectError = null; connectCanceled = false
        usbState = UsbConnection.observe(this)
        navigate(Screen.CONNECT)
    }

    private fun connectScreen() {
        if (app.pen == null) connectIgnoredKey = null
        if (connectReading && !app.busy) {
            connectReading = false
            connectError = if (app.failure) app.status else null
        }
        val target = app.knownProfiles.find { it.id == connectTargetId }
        val connected = app.pen?.takeIf { it.identity.key != connectIgnoredKey }
        val wrongPen = connected != null && target != null && connected.identity.key != target.identity.key
        val ready = connected != null && !wrongPen && connectError == null && !app.busy
        val access = usbState.attached && usbState.drives.isNotEmpty() && connectIgnoredKey == null
        val step = if (ready) 3 else if (access || connectReading || connectError != null) 2 else 1
        body.addView(text(t(R.string.connect_step, step), 13, green, true)); gap(body, 16)
        val hero = card(if (ready) green else Color.WHITE)
        val color = if (ready) Color.WHITE else ink
        hero.addView(icon(if (ready) UiIcon.CHECK else UiIcon.USB, if (ready) Color.WHITE else green, 56))
        gap(hero, 20)
        val title = when {
            ready -> t(R.string.connect_ready)
            app.busy -> t(R.string.connect_reading)
            connectError != null -> t(R.string.connect_try_folder)
            wrongPen || target != null -> t(R.string.ui_connect_1_s, target!!.name)
            connectIgnoredKey != null -> t(R.string.connect_swap)
            access -> t(R.string.connect_allow_title)
            else -> t(R.string.connect_plug_title)
        }
        hero.addView(text(title, 26, color, true)); gap(hero, 12)
        val description = when {
            ready -> t(R.string.connect_ready_body, app.connectedName ?: t(R.string.ui_selected_pen))
            app.busy -> t(R.string.connect_reading_body)
            connectError != null -> connectError!!
            wrongPen -> t(R.string.connect_wrong_pen, app.connectedName ?: t(R.string.ui_selected_pen), target!!.name)
            connectIgnoredKey != null -> t(R.string.connect_swap_body)
            access -> t(R.string.connect_allow_body)
            else -> t(R.string.connect_plug_body)
        }
        hero.addView(text(description, 16, if (ready) Color.WHITE else muted))
        body.addView(hero); gap(body, 20)
        when {
            ready -> body.addView(action(t(R.string.connect_view_pen)) {
                app.knownProfiles.find { it.identity.key == connected!!.identity.key }?.let { app.selectProfile(it.id) }
                backStack.removeAll { it == Screen.CONNECT }
                navigate(Screen.PEN, false)
            })
            app.busy -> body.addView(ProgressBar(this).apply { isIndeterminate = true })
            wrongPen -> body.addView(action(t(R.string.connect_use_this_pen)) { connectTargetId = null; render() })
            access || connectError != null -> body.addView(action(t(R.string.connect_allow_access)) { chooseDrive() })
            else -> {
                body.addView(ProgressBar(this).apply { isIndeterminate = true }, LinearLayout.LayoutParams(dp(28), dp(28)))
                gap(body, 12); body.addView(text(t(R.string.connect_waiting), 14, muted))
            }
        }
        if (!ready && !app.busy) {
            if (connectCanceled) { gap(body, 12); body.addView(text(t(R.string.connect_canceled), 14, muted)) }
            body.addView(action(t(R.string.connect_choose_manually), false) { chooseFolder() })
            body.addView(action(t(R.string.connect_cable_help), false) {
                showMessage(t(R.string.connect_cable_help), t(R.string.connect_cable_body))
            })
        }
        if (ready) {
            gap(body, 8); body.addView(text(t(R.string.connect_remembered), 14, muted))
            body.addView(action(t(R.string.connect_review_access), false) { chooseDrive() })
        }
    }

    private fun chooseDrive() {
        usbState = UsbConnection.observe(this)
        val drives = usbState.drives
        when (drives.size) {
            0 -> chooseFolder()
            1 -> chooseFolder(drives.single())
            else -> AlertDialog.Builder(this).setTitle(t(R.string.connect_choose_drive))
                .setItems(drives.map { it.getDescription(this) }.toTypedArray()) { _, index -> chooseFolder(drives[index]) }
                .setNegativeButton(t(R.string.ui_cancel), null).show()
        }
    }

    private fun chooseBookPens(entry: CatalogEntry) {
        val pens = app.knownProfiles.toList()
        val labels = listOf(t(R.string.ui_family_collection)) + pens.map { t(R.string.ui_1_s_individual_selection, it.name) }
        fun contains(entries: List<CatalogEntry>) = entries.any { it.cacheKey == entry.cacheKey && it.filename.equals(entry.filename, true) }
        val checked = (listOf(contains(app.familySelection)) + pens.map { contains(it.additions) }).toBooleanArray()
        AlertDialog.Builder(this).setTitle(t(R.string.ui_choose_pens))
            .setMultiChoiceItems(labels.toTypedArray(), checked) { _, which, enabled -> checked[which] = enabled }
            .setNegativeButton(t(R.string.ui_cancel), null).setPositiveButton(t(R.string.ui_save)) { _, _ ->
                app.setContentTargets(entry, pens.filterIndexed { index, _ -> checked[index + 1] }.map { it.id }.toSet(), checked[0])
            }.show()
    }

    private fun collectionScreen() {
        body.addView(text(t(R.string.ui_family_collection), 22, ink, true)); gap(body, 8)
        body.addView(text(t(R.string.ui_shared_selections_connect_each_pen_to_review_and_apply), 14, muted)); gap(body, 16)
        if (app.familySelection.isEmpty()) body.addView(text(t(R.string.ui_choose_pens_on_a_book_to_add_it_here), 14, muted))
        app.familySelection.forEach { entry -> selectionRow(entry, null) }
        gap(body, 24)
        app.knownProfiles.forEach { profile ->
            body.addView(text(profile.name, 20, ink, true)); gap(body, 10)
            body.addView(navRow(UiIcon.BOOKS, t(R.string.ui_family_collection), if (profile.useFamily) t(R.string.ui_included) else t(R.string.ui_not_included)) {
                app.setFamilyEnabled(profile.id, !profile.useFamily)
            })
            profile.additions.forEach { entry -> selectionRow(entry, profile.id) }
            body.addView(action(t(R.string.ui_review_for_1_s, profile.name), false) { app.selectProfile(profile.id); navigate(Screen.PLAN) })
            gap(body, 20)
        }
        body.addView(action(t(R.string.ui_browse_books)) { navigate(Screen.BROWSE, false) })
    }

    private fun selectionRow(entry: CatalogEntry, profileId: String?) {
        body.addView(bookRow(app.title(entry), if (profileId == null) t(R.string.ui_family_selection) else t(R.string.ui_individual_selection), onPen(entry.filename), app.book(entry.filename, entry.id)) {
            AlertDialog.Builder(this).setTitle(app.title(entry)).setItems(arrayOf(t(R.string.ui_view_book), t(R.string.ui_remove_from_selection))) { _, which ->
                if (which == 0) openBook(entry, entry.filename) else app.selectContent(entry, profileId, false)
            }.setNegativeButton(t(R.string.ui_cancel), null).show()
        })
    }

    private fun planScreen() {
        val profile = app.viewedProfile ?: return
        val plan = app.plan() ?: return
        val connected = app.viewedPen != null
        body.addView(text(profile.name, 25, ink, true)); gap(body, 8)
        body.addView(text(if (connected) t(R.string.ui_connected_by_usb) else t(R.string.ui_saved_inventory_1_s, dateTime(profile.lastSeen)), 14, muted)); gap(body, 20)
        if (plan.items.isEmpty()) {
            emptyState(UiIcon.BOOKS, t(R.string.ui_no_selected_books))
            body.addView(action(t(R.string.ui_browse_books)) { navigate(Screen.BROWSE, false) })
        } else {
            body.addView(text(t(R.string.ui_to_add_1_d_already_present_2_d, plan.additions.size, plan.items.count { it.state == PlanState.PRESENT }), 19, ink, true)); gap(body, 8)
            body.addView(text(t(R.string.ui_1_s_to_add_existing_files_are_kept, size(plan.bytes)), 14, muted)); gap(body, 16)
            if (plan.conflicts > 0) { body.addView(text(t(R.string.ui_conflicting_filenames_need_review_no_existing_file_will_be_overwritt), 14, 0xFF9B2A22.toInt())); gap(body, 12) }
            plan.items.forEach { item ->
                val status = when (item.state) {
                    PlanState.PRESENT -> t(R.string.ui_already_present_filename_size_and_audio_id_match)
                    PlanState.CONFLICT -> t(R.string.ui_filename_conflict)
                    PlanState.ADD -> if (app.cached(item.entry)) t(R.string.ui_ready_offline) else t(R.string.ui_download_needed)
                }
                body.addView(bookRow(app.title(item.entry), status, item.state == PlanState.PRESENT, app.book(item.entry.filename, item.entry.id)) { openBook(item.entry, item.entry.filename) })
            }
            if (plan.items.any { item -> app.catalog.any { it.id == item.entry.id && !it.filename.equals(item.entry.filename, true) } }) {
                gap(body, 12); body.addView(text(t(R.string.ui_some_audio_shares_an_id_with_other_files_select_any_required_compani), 13, muted))
            }
            if (!connected) body.addView(action(t(R.string.ui_connect_1_s, profile.name)) { connectionHelp(profile) })
            else {
                val fits = plan.additions.isEmpty() || (app.viewedPen?.free?.let { it >= plan.bytes + 1_048_576 } ?: true)
                if (plan.additions.isEmpty() && plan.conflicts == 0) body.addView(chip(t(R.string.ui_all_selected_books_are_present), UiIcon.CHECK))
                else body.addView(action(t(R.string.ui_add_selected_books)) { confirmSelection(plan, profile) }.apply {
                    isEnabled = !app.busy && plan.additions.isNotEmpty() && plan.conflicts == 0 && fits && profile.identity.hasSerial
                    alpha = if (isEnabled) 1f else .45f
                })
                if (!fits) body.addView(action(t(R.string.ui_make_space_on_pen), false) { reviewOldest() })
                if (!profile.identity.hasSerial) body.addView(text(t(R.string.ui_collection_sync_needs_a_recognized_pen_serial), 14, muted))
            }
        }
        body.addView(action(t(R.string.ui_manage_selections), false) { navigate(Screen.COLLECTION) })
    }

    private fun confirmSelection(plan: PenPlan, profile: PenProfile) {
        val missingDownloads = plan.additions.count { !app.cached(it) }
        AlertDialog.Builder(this).setTitle(t(R.string.ui_add_books_to_1_s, profile.name))
            .setMessage(t(R.string.ui_1_d_files_2_s_3_d_downloads_needed_existing_pen_files_stay_in_place, plan.additions.size, size(plan.bytes), missingDownloads) +
                if (app.audioRetention == AudioRetention.KEEP) t(R.string.ui_downloads_stay_on_this_phone_for_your_other_pens) else t(R.string.ui_transfer_only_audio_is_discarded_after_each_transfer))
            .setNegativeButton(t(R.string.ui_cancel), null).setPositiveButton(t(R.string.ui_add_books)) { _, _ ->
                navigate(Screen.TRANSFERS); app.applySelection(plan)
            }.show()
    }

    private fun listScreen() {
        val search = EditText(this).apply {
            hint = t(R.string.ui_search_titles_or_ids); contentDescription = t(R.string.ui_search_books)
            setSingleLine(true); setTextColor(ink); textSize = 16f
            background = rounded(Color.WHITE, 14)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setText(queries[screen].orEmpty())
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, _, _ -> hideKeyboard(); clearFocus(); true }
        }
        root.addView(search, LinearLayout.LayoutParams(-1, dp(54)).apply { setMargins(dp(20), dp(16), dp(20), 0) })
        if (screen == Screen.INSTALLED) {
            root.addView(action(t(R.string.ui_sort_1_s, penSort.label), false) {
                AlertDialog.Builder(this).setTitle(t(R.string.ui_sort_installed_files))
                    .setSingleChoiceItems(PenSort.entries.map { it.label }.toTypedArray(), penSort.ordinal) { dialog, which ->
                        setSort(PenSort.entries[which]); dialog.dismiss(); render()
                    }.setNegativeButton(t(R.string.ui_cancel), null).show()
            }.apply { setPadding(dp(20), 0, dp(20), 0); gravity = Gravity.START or Gravity.CENTER_VERTICAL })
        }
        scrollBody()
        fun populate() {
            body.removeAllViews()
            val query = queries[screen].orEmpty().trim()
            fun matches(title: String, id: Int?) = query.isEmpty() || title.contains(query, true) || id?.toString() == query
            if (screen == Screen.INSTALLED) {
                val profile = app.viewedProfile
                if (profile == null) { emptyState(UiIcon.PEN, t(R.string.ui_choose_a_pen_profile)); return }
                if (app.viewedPen == null) { body.addView(text(t(R.string.ui_saved_inventory_1_s, dateTime(profile.lastSeen)), 13, muted)); gap(body, 10) }
                val files = penSort.order(profile.files.filter { matches(titleFor(it.name), it.id) },
                    { titleFor(it.name) }, { it.size }, { it.modifiedAt })
                body.addView(text(p(R.plurals.ui_1_d_audio_files, files.size, files.size), 13, muted)); gap(body, 12)
                if (penSort == PenSort.OLDEST || penSort == PenSort.NEWEST) {
                    body.addView(text(t(R.string.ui_file_modified_date_not_last_played), 12, muted)); gap(body, 12)
                }
                if (files.isEmpty()) emptyState(UiIcon.BOOKS, t(R.string.ui_no_matches))
                files.forEach { file ->
                    val subtitle = if (penSort == PenSort.OLDEST || penSort == PenSort.NEWEST)
                        "${date(file.modifiedAt)} · ${size(file.size)}" else size(file.size)
                    body.addView(bookRow(titleFor(file.name), subtitle, true, app.book(file.name, file.id)) {
                    openBook(app.catalog.find { it.filename.equals(file.name, true) }, file.name)
                }) }
            } else {
                if (screen == Screen.DOWNLOADS) body.addView(navRow(UiIcon.STORAGE, t(R.string.ui_phone_storage), size(app.audioUsage.total)) {
                    navigate(Screen.PHONE_STORAGE)
                })
                val entries = app.catalog.filter { entry ->
                    val info = app.book(entry.filename, entry.id)
                    val searchable = listOf(entry.title, info?.title, info?.series).filterNotNull().joinToString(" ")
                    (screen != Screen.DOWNLOADS || app.cached(entry) || app.backup(entry.filename) != null) && (matches(searchable, entry.id) ||
                        (query.isNotEmpty() && app.metadata.forEntry(entry).any { book -> book.article == query ||
                            app.metadata.page(book.article)?.isbn?.replace("-", "") == query.replace("-", "") }))
                }.sortedBy { app.title(it).lowercase(Locale.ROOT) }
                val orphanBackups = if (screen == Screen.DOWNLOADS) app.backups.latestCopies().filter { backup ->
                    app.catalog.none { it.filename.equals(backup.filename, true) } && matches(titleFor(backup.filename), backup.audioId)
                } else emptyList()
                val count = entries.size + orphanBackups.size
                body.addView(text(q(if (screen == Screen.DOWNLOADS) R.plurals.saved_files else R.plurals.catalog_items, count), 13, muted)); gap(body, 12)
                if (app.metadata.books.isEmpty() && app.metadataNotice != null) {
                    body.addView(text(t(R.string.ui_book_details_unavailable_refresh_to_retry), 13, muted)); gap(body, 10)
                }
                if (entries.isEmpty() && orphanBackups.isEmpty()) {
                    when {
                        query.isNotEmpty() -> emptyState(UiIcon.BOOKS, t(R.string.ui_no_matches))
                        screen == Screen.DOWNLOADS -> {
                            emptyState(UiIcon.DOWNLOAD, t(R.string.ui_no_downloads_yet), t(R.string.ui_save_books_for_offline_installation))
                            body.addView(action(t(R.string.ui_browse_books)) { navigate(Screen.BROWSE, false) })
                        }
                        else -> { emptyState(UiIcon.BOOKS, t(R.string.ui_load_your_library)); body.addView(action(t(R.string.ui_load_catalog)) { app.refreshCatalog() }) }
                    }
                }
                entries.take(80).forEach { entry ->
                    val present = onPen(entry.filename)
                    val info = app.book(entry.filename, entry.id)
                    val subtitle = listOfNotNull(if (present) if (app.viewedPen != null) t(R.string.ui_on_named_pen, app.viewedProfile?.name) else t(R.string.ui_last_seen_on_1_s, app.viewedProfile?.name) else if (app.backup(entry.filename) != null) t(R.string.ui_pen_backup) else if (app.cached(entry)) t(R.string.ui_downloaded) else null,
                        info?.series ?: size(entry.size)).joinToString(" · ")
                    body.addView(bookRow(app.title(entry), subtitle, present, info) { openBook(entry, entry.filename) })
                }
                orphanBackups.forEach { backup -> body.addView(bookRow(titleFor(backup.filename), t(R.string.ui_pen_backup_1_s, size(backup.size)),
                    onPen(backup.filename), app.book(backup.filename, backup.audioId)) { openBook(null, backup.filename) }) }
                if (entries.size > 80) { gap(body, 12); body.addView(text(t(R.string.ui_search_to_see_more_titles), 13, muted)) }
            }
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { queries[screen] = s.toString(); populate() }
            override fun afterTextChanged(s: Editable?) {}
        })
        populate()
    }

    private fun openBook(entry: CatalogEntry?, filename: String) {
        selectedKey = entry?.cacheKey; selectedFile = filename
        navigate(Screen.BOOK)
    }

    private fun selectedEntry(): CatalogEntry? = app.catalog.find { it.cacheKey == selectedKey }
        ?: app.catalog.find { it.filename.equals(selectedFile, true) }

    private fun selectedBooks(): List<BookInfo> {
        if (app.demoMode) return listOfNotNull(selectedFile?.let(DemoLibrary::book))
        val entry = selectedEntry()
        val id = entry?.id ?: app.visibleFiles.find { it.name.equals(selectedFile, true) }?.id ?: selectedFile?.let { app.backup(it)?.audioId }
        if (id == 14999) return emptyList()
        return selectedFile?.let { app.metadata.forFile(it, id) }.orEmpty()
    }

    private fun bookScreen() {
        val entry = selectedEntry()
        val filename = entry?.filename ?: selectedFile ?: return
        val file = app.visibleFiles.find { it.name.equals(filename, true) }
        val backup = app.backup(filename)
        val cached = entry?.let(app::cached) == true
        val present = file != null
        val connected = app.viewedPen != null
        val liveFile = app.viewedPen?.files?.find { it.name.equals(filename, true) }
        val book = app.book(filename, entry?.id ?: file?.id ?: backup?.audioId)
        book?.let { app.requestPublisherDetails(it) }
        val page = book?.let { app.metadata.page(it.article) }
        val cover = card(Color.WHITE)
        cover.gravity = Gravity.CENTER_HORIZONTAL
        cover.setPadding(dp(12), dp(12), dp(12), dp(12))
        if (book != null) cover.addView(coverView(book, page?.coverUrl ?: book.coverUrl, 300), LinearLayout.LayoutParams(-1, dp(250)))
        else {
            cover.setPadding(dp(24), dp(36), dp(24), dp(36))
            cover.addView(icon(UiIcon.BOOKS, green, 64))
        }
        body.addView(cover); gap(body, 24)
        body.addView(text(book?.title ?: entry?.title ?: titleFor(filename), 26, ink, true))
        book?.series?.let { gap(body, 8); body.addView(text(it, 15, muted)) }
        gap(body, 12)
        val age = book?.ageFrom?.let { from -> if (book.ageTo != null && book.ageTo != from) t(R.string.ui_age_range, from, book.ageTo) else t(R.string.ui_age_min, from) }
        val bytes = file?.size ?: backup?.size ?: entry?.size ?: 0
        body.addView(text(listOfNotNull(age, size(bytes)).joinToString(" · "), 15, muted))
        gap(body, 20)
        app.viewedProfile?.let { body.addView(text(t(R.string.ui_pen_1_s, it.name), 14, muted)); gap(body, 10) }
        if (present) { body.addView(chip(if (connected) t(R.string.ui_on_your_pen) else t(R.string.ui_in_saved_inventory), UiIcon.CHECK)); gap(body, 10) }
        if (cached) { body.addView(chip(t(R.string.ui_saved_on_this_phone), UiIcon.DOWNLOAD)); gap(body, 10) }
        if (backup != null) { body.addView(chip(t(R.string.ui_pen_backup_on_this_phone), UiIcon.STORAGE)); gap(body, 10) }
        if (file?.problem != null) body.addView(text(t(R.string.msg_unreadable_header), 14, 0xFF9B2A22.toInt()))
        gap(body, 20)
        when (if (present && !connected) BookPrimaryAction.CONNECT else BookActions.primary(present, BookActions.removable(filename, file?.id), backup != null,
            cached, connected, entry != null, app.viewedPen?.free, bytes, app.audioRetention == AudioRetention.TRANSFER_ONLY)) {
            BookPrimaryAction.REMOVE -> body.addView(action(t(R.string.ui_remove_from_pen), false) { liveFile?.let(::confirmRemove) }.apply {
                setTextColor(0xFF9B2A22.toInt())
            })
            BookPrimaryAction.RESTORE -> body.addView(action(t(R.string.ui_restore_to_pen)) { backup?.let(::confirmRestore) })
            BookPrimaryAction.INSTALL -> body.addView(action(t(R.string.ui_install_on_pen)) { entry?.let(::confirmInstall) })
            BookPrimaryAction.DOWNLOAD -> body.addView(action(t(R.string.ui_download_to_phone)) { entry?.let { navigate(Screen.TRANSFERS); app.download(it) } })
            BookPrimaryAction.DOWNLOAD_INSTALL -> body.addView(action(t(R.string.ui_download_install)) { entry?.let { confirmInstall(it, true) } })
            BookPrimaryAction.CONNECT -> body.addView(action(t(R.string.ui_connect_a_pen)) { navigate(Screen.PEN, false) })
            BookPrimaryAction.MAKE_SPACE -> body.addView(action(t(R.string.ui_make_space_on_pen)) { reviewOldest() })
            BookPrimaryAction.NONE -> if (present) body.addView(text(t(R.string.ui_shared_support_file), 14, muted))
        }
        if (entry != null) body.addView(action(t(R.string.ui_choose_pens), false) { chooseBookPens(entry) })
        if (cached || backup != null) body.addView(action(t(R.string.ui_manage_phone_copies), false) { managePhoneCopies() })
        book?.takeIf { it.description.isNotBlank() || it.shortDescription.isNotBlank() }?.let {
            gap(body, 24)
            body.addView(text(t(R.string.ui_about_this_book), 19, ink, true)); gap(body, 10)
            val summary = it.shortDescription.ifBlank { it.description }
            body.addView(text(summary, 15, muted).apply { maxLines = 4; ellipsize = TextUtils.TruncateAt.END })
            body.addView(action(t(R.string.ui_read_more), false) { showMessage(it.title, it.description.ifBlank { summary }) })
        }
    }

    private fun fileDetails() {
        selectedBooks().take(5).forEach { app.requestPublisherDetails(it) }
        infoDialog?.dismiss()
        infoDialog = AlertDialog.Builder(this).setTitle(t(R.string.ui_book_file_details)).setMessage(fileInfoMessage())
            .setPositiveButton(t(R.string.ui_done), null).apply {
                selectedBooks().firstOrNull()?.let { book -> setNeutralButton(t(R.string.ui_publisher_page)) { _, _ ->
                    startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(book.publisherUrl)))
                } }
            }.show()
    }

    private fun fileInfoMessage(): String {
        val entry = selectedEntry()
        val file = app.visibleFiles.find { it.name.equals(selectedFile, true) }
        val backup = selectedFile?.let { app.backup(it) }
        val editions = selectedBooks()
        val fields = mutableListOf<String>()
        if (editions.size > 1) fields.add(p(R.plurals.ui_1_d_publisher_products_share_this_audio_the_pen_does_not_identify_yo, editions.size, editions.size))
        editions.take(5).forEach { book ->
            val page = app.metadata.page(book.article)
            fields.add(listOfNotNull(book.title, t(R.string.ui_article, book.article),
                page?.isbn?.let { t(R.string.ui_isbn, it) } ?: if (book.article in app.pendingPublisherPages) t(R.string.ui_loading_isbn) else t(R.string.ui_isbn_not_available),
                page?.pages?.let { p(R.plurals.ui_pages, it, it) }).joinToString("\n"))
        }
        fields.addAll(listOfNotNull(selectedFile, (entry?.id ?: file?.id)?.let { t(R.string.ui_audio_id_1_s, it) },
            entry?.version?.let { t(R.string.ui_audio_version_1_s, it) }, file?.let { t(R.string.ui_pen_file_modified_1_s, date(it.modifiedAt)) },
            backup?.let { t(R.string.ui_phone_backup_saved_1_s_original_pen_file_modified_2_s, date(it.savedAt), date(it.sourceDate)) }, file?.problem?.let { t(R.string.msg_unreadable_header) }))
        if (file != null || backup != null) fields.add(t(R.string.ui_file_dates_are_reported_by_storage_they_may_reflect_a_copy_or_publis))
        return fields.joinToString("\n\n")
    }

    private fun transfersScreen() {
        val transfer = app.transfer
        if (transfer == null) {
            if (app.busy) { emptyState(UiIcon.REFRESH, t(R.string.ui_working)); progressLabel = text(app.status, 15, muted); body.addView(progressLabel) }
            else { emptyState(UiIcon.TRANSFER, t(R.string.ui_no_active_transfers), t(R.string.ui_downloads_and_installs_appear_here)); body.addView(action(t(R.string.ui_browse_books)) { navigate(Screen.BROWSE, false) }) }
            return
        }
        val entry = app.catalog.find { it.cacheKey == transfer.bookKey }
        val symbol = if (transfer.running) UiIcon.TRANSFER else if (transfer.failed) UiIcon.INFO else UiIcon.CHECK
        val heading = if (transfer.running) t(R.string.ui_1_s_in_progress, taskText(transfer.action)) else if (transfer.failed) t(R.string.ui_task_failed, taskText(transfer.action)) else t(R.string.ui_task_complete, taskText(transfer.action))
        val summary = card()
        summary.addView(icon(symbol, if (transfer.failed) 0xFF9B2A22.toInt() else green, 48)); gap(summary, 20)
        summary.addView(text(heading, 25, ink, true)); gap(summary, 12)
        summary.addView(text(entry?.let(app::title) ?: transfer.title, 17, ink))
        transfer.target?.let { gap(summary, 8); summary.addView(text(t(R.string.ui_to_1_s, it), 15, muted)) }
        progressLabel = text(transfer.stage, 14, muted)
        gap(summary, 16); summary.addView(progressLabel)
        if (transfer.running) {
            progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
            summary.addView(progressBar, LinearLayout.LayoutParams(-1, dp(8)).apply { topMargin = dp(16) })
            gap(summary, 14); summary.addView(text(t(R.string.ui_keep_the_app_open_and_the_pen_connected), 13, muted))
        }
        body.addView(summary); gap(body, 24)
        if (!transfer.running) {
            if (transfer.filename == "@selection") body.addView(action(t(R.string.ui_review_selection)) { navigate(Screen.PLAN) })
            else body.addView(action(t(R.string.ui_view_book)) { openBook(entry, transfer.filename) })
        }
        if (transfer.failed) body.addView(action(t(R.string.ui_connection_help), false) { navigate(Screen.HELP, false) })
        updateProgress()
    }

    private fun updateProgress() {
        val transfer = app.transfer
        progressLabel?.text = transfer?.stage ?: app.status
        progressBar?.let { bar -> bar.isIndeterminate = transfer?.percent == null; transfer?.percent?.let { bar.progress = it } }
        busyLabel?.text = if (transfer?.running == true) "${taskText(transfer.action)} · ${transfer.percent?.let { "$it%" } ?: transfer.stage}" else app.status
    }

    private fun helpScreen() {
        body.addView(action(t(R.string.ui_language), false) {
            val tags = listOf("", "en", "de", "fr")
            AlertDialog.Builder(this).setTitle(t(R.string.ui_language))
                .setSingleChoiceItems(arrayOf(t(R.string.ui_device_language), "English", "Deutsch", "Français"), tags.indexOf(UiText.language(this))) { dialog, index ->
                    if (!app.busy) { UiText.setLanguage(this, tags[index]); dialog.dismiss(); recreate() }
                }.setNegativeButton(t(R.string.ui_cancel), null).show()
        })
        body.addView(action(t(R.string.ui_demo), false) { app.setDemo(true); navigate(Screen.PENS, false) })
        body.addView(text(t(R.string.ui_getting_started), 22, ink, true)); gap(body, 20)
        body.addView(navRow(UiIcon.PEN, t(R.string.ui_1_connect_your_pen), t(R.string.ui_use_a_usb_data_cable_or_adapter)) { startConnection() })
        body.addView(navRow(UiIcon.BOOKS, t(R.string.ui_2_find_a_book), t(R.string.ui_browse_or_search_the_catalog)) { navigate(Screen.BROWSE, false) })
        body.addView(navRow(UiIcon.DOWNLOAD, t(R.string.ui_3_download_install), t(R.string.ui_keep_the_pen_connected_until_done)) { navigate(Screen.DOWNLOADS, false) })
        gap(body, 28)
        val about = card()
        about.addView(text(t(R.string.app_name), 19, ink, true)); gap(about, 8)
        about.addView(text(t(R.string.ui_independent_open_source_usb_manager_no_account_your_library_stays_on), 14, muted))
        gap(about, 12)
        about.addView(action(t(R.string.ui_phone_copies_backups), false) {
            showMessage(t(R.string.ui_phone_copies_backups), t(R.string.ui_choose_keep_downloads_for_offline_reuse_or_transfer_only_to_discard))
        })
        gap(about, 12)
        about.addView(text(t(R.string.ui_independent), 13, muted))
        about.addView(text(t(R.string.ui_catalog_language), 13, muted))
        about.addView(action(t(R.string.ui_privacy), false) { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://kzahel.github.io/tiptoi-manager/privacy/"))) })
        about.addView(action(t(R.string.ui_source_support), false) { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/kzahel/tiptoi-manager"))) })
        gap(about, 12)
        about.addView(text(t(R.string.ui_version_1_s, BuildConfig.VERSION_NAME), 12, muted))
        gap(about, 12)
        about.addView(action(t(R.string.ui_open_source_licenses), false) {
            val notices = assets.open("NOTICE.txt").bufferedReader().use { it.readText() }
            val licenses = listOf("MIT.txt", "Apache-2.0.txt", "jsoup-MIT.txt", "desugar-GPL-2-with-classpath.txt")
                .joinToString("\n\n") { name -> assets.open(name).bufferedReader().use { it.readText() } }
            showMessage(t(R.string.ui_open_source_licenses), t(R.string.ui_1_s_2_s, notices, licenses))
        })
        body.addView(about)
    }

    private fun confirmInstall(entry: CatalogEntry, downloadFirst: Boolean = false) {
        val pen = app.viewedPen ?: return
        val profile = app.viewedProfile ?: return
        val unknown = if (pen.free == null) t(R.string.ui_free_space_is_unavailable) else ""
        AlertDialog.Builder(this).setTitle(t(R.string.ui_install_on_1_s, profile.name))
            .setMessage(t(R.string.ui_1_s_2_s_existing_files_stay_untouched_3_s, app.title(entry), size(entry.size), unknown) +
                if (downloadFirst) t(R.string.ui_downloads_temporarily_verifies_the_pen_copy_then_discards_the_phone) else
                    if (app.audioRetention == AudioRetention.TRANSFER_ONLY) t(R.string.ui_phone_download_deleted_after_a_verified_install) else "")
            .setNegativeButton(t(R.string.ui_cancel), null).setPositiveButton(t(R.string.ui_install)) { _, _ ->
                navigate(Screen.TRANSFERS)
                if (downloadFirst) app.downloadAndInstall(entry, profile.id) else app.install(entry, profile.id)
            }.show()
    }

    private fun bookMenu(anchor: View) {
        val entry = selectedEntry()
        PopupMenu(this, anchor).apply {
            if (entry != null && app.audioRetention == AudioRetention.KEEP) {
                val label = if (app.cached(entry)) t(R.string.ui_re_download_phone_copy) else t(R.string.ui_download_phone_copy)
                menu.add(label).setOnMenuItemClickListener {
                    AlertDialog.Builder(this@MainActivity).setTitle(if (app.cached(entry)) t(R.string.ui_re_download_phone_copy_773303) else t(R.string.ui_download_phone_copy_9fea6b))
                        .setMessage(t(R.string.ui_1_s_2_s_saved_on_this_phone_for_offline_installation, app.title(entry), size(entry.size)))
                        .setNegativeButton(t(R.string.ui_cancel), null).setPositiveButton(t(R.string.ui_download)) { _, _ ->
                            navigate(Screen.TRANSFERS); app.download(entry)
                        }.show()
                    true
                }
            }
            if (entry?.let(app::cached) == true || selectedFile?.let { app.backup(it) } != null) {
                menu.add(t(R.string.ui_manage_phone_copies)).setOnMenuItemClickListener { managePhoneCopies(); true }
            }
            menu.add(t(R.string.ui_phone_storage)).setOnMenuItemClickListener { navigate(Screen.PHONE_STORAGE); true }
            show()
        }
    }

    private fun managePhoneCopies() {
        val filename = selectedFile ?: return
        val entry = selectedEntry()
        val choices = mutableListOf<Pair<String, () -> Unit>>()
        if (entry?.let(app::cached) == true) choices.add(t(R.string.ui_delete_phone_download) to { confirmDeleteDownload(app.cache(entry)) })
        if (app.backup(filename) != null) choices.add(t(R.string.ui_delete_pen_backups) to { confirmDeleteBackups(filename) })
        AlertDialog.Builder(this).setTitle(t(R.string.ui_phone_copies)).setItems(choices.map { it.first }.toTypedArray()) { _, which ->
            choices[which].second()
        }.setNegativeButton(t(R.string.ui_cancel), null).show()
    }

    private fun phoneStorageScreen() {
        val usage = app.audioUsage
        val summary = card()
        summary.addView(icon(UiIcon.STORAGE, green, 36)); gap(summary, 16)
        summary.addView(text(size(usage.total), 36, ink, true))
        summary.addView(text(t(R.string.ui_audio_on_this_phone), 15, muted))
        body.addView(summary); gap(body, 20)
        body.addView(navRow(UiIcon.DOWNLOAD, t(R.string.ui_download_storage), app.audioRetention.label) { chooseRetention() })
        if (app.audioRetention == AudioRetention.TRANSFER_ONLY) {
            body.addView(text(t(R.string.ui_new_downloads_are_temporary_existing_downloads_stay_until_installed), 13, muted)); gap(body, 12)
        }
        val downloads = app.phoneAudio.downloadFiles()
        body.addView(navRow(UiIcon.BOOKS, t(R.string.ui_downloaded_audio), p(R.plurals.ui_1_d_files_2_s, downloads.size, downloads.size, size(usage.downloads))) {
            AlertDialog.Builder(this).setTitle(t(R.string.ui_phone_downloads)).setItems(downloads.map { localDownloadTitle(it) }.toTypedArray()) { _, which ->
                confirmDeleteDownload(downloads[which])
            }.setNegativeButton(t(R.string.ui_done), null).show()
        })
        body.addView(navRow(UiIcon.STORAGE, t(R.string.ui_pen_backups), p(R.plurals.ui_1_d_copies_2_s, app.backups.all.size, app.backups.all.size, size(usage.backups))) {
            val copies = app.backups.latestCopies()
            AlertDialog.Builder(this).setTitle(t(R.string.ui_pen_backups_on_phone)).setItems(copies.map { titleFor(it.filename) }.toTypedArray()) { _, which ->
                confirmDeleteBackups(copies[which].filename)
            }.setNegativeButton(t(R.string.ui_done), null).show()
        })
        if (usage.temporary > 0) { gap(body, 8); body.addView(text(t(R.string.ui_temporary_audio_1_s, size(usage.temporary)), 14, muted)) }
        gap(body, 16)
        body.addView(action(t(R.string.ui_clear_downloads), false) { confirmClearPhoneAudio(false) }.apply { isEnabled = !app.busy && usage.downloads + usage.temporary > 0 })
        body.addView(action(t(R.string.ui_delete_all_phone_audio), false) { confirmClearPhoneAudio(true) }.apply {
            setTextColor(0xFF9B2A22.toInt()); isEnabled = !app.busy && usage.total > 0
        })
        gap(body, 16)
        body.addView(text(t(R.string.ui_pen_backups_stay_until_you_delete_them_catalog_details_and_artwork_a), 13, muted))
    }

    private fun chooseRetention() {
        AlertDialog.Builder(this).setTitle(t(R.string.ui_download_storage))
            .setSingleChoiceItems(arrayOf(t(R.string.ui_keep_downloads_reuse_audio_offline), t(R.string.ui_transfer_only_temporary_audio_deleted_after_transfer)), app.audioRetention.ordinal) { dialog, which ->
                app.updateAudioRetention(AudioRetention.entries[which]); dialog.dismiss()
            }.setNegativeButton(t(R.string.ui_cancel), null).show()
    }

    private fun localDownloadTitle(file: java.io.File): String =
        app.catalog.find { it.cacheKey == file.name }?.let(app::title) ?: t(R.string.ui_older_download_1_s, file.name.take(8))

    private fun confirmDeleteDownload(file: java.io.File) {
        AlertDialog.Builder(this).setTitle(t(R.string.ui_delete_phone_download_a3aa92))
            .setMessage(t(R.string.ui_1_s_2_s_on_this_phone_the_pen_and_pen_backups_are_unchanged, localDownloadTitle(file), size(file.length())))
            .setNegativeButton(t(R.string.ui_cancel), null).setPositiveButton(t(R.string.ui_delete)) { _, _ -> app.deletePhoneDownload(file) }.show()
    }

    private fun confirmDeleteBackups(filename: String) {
        val copies = app.backups.all.filter { it.filename.equals(filename, true) }
        AlertDialog.Builder(this).setTitle(t(R.string.ui_delete_phone_backups))
            .setMessage(p(R.plurals.ui_1_s_2_d_backups_3_s_these_backups_cannot_be_restored_after_deletion, copies.size, titleFor(filename), copies.size, size(copies.sumOf { it.size })))
            .setNegativeButton(t(R.string.ui_cancel), null).setPositiveButton(t(R.string.ui_delete)) { _, _ -> app.deletePhoneBackups(filename) }.show()
    }

    private fun confirmClearPhoneAudio(includeBackups: Boolean) {
        AlertDialog.Builder(this).setTitle(if (includeBackups) t(R.string.ui_delete_all_phone_audio_1e6a6f) else t(R.string.ui_clear_downloads_e81a1d))
            .setMessage(if (includeBackups) t(R.string.ui_deletes_all_downloaded_audio_temporary_files_and_all_pen_backups_on)
                else t(R.string.ui_deletes_downloaded_and_temporary_audio_on_this_phone_pen_removal_bac))
            .setNegativeButton(t(R.string.ui_cancel), null).setPositiveButton(t(R.string.ui_delete)) { _, _ -> app.clearPhoneAudio(includeBackups) }.show()
    }

    private fun confirmRemove(file: PenFile) {
        val profile = app.viewedProfile ?: return
        val shared = app.visibleFiles.any { it.id == file.id && it.name != file.name } == true
        val warning = if (shared) t(R.string.ui_other_files_share_this_audio_id_and_will_stay_on_the_pen_related_con) else ""
        AlertDialog.Builder(this).setTitle(t(R.string.ui_remove_from_1_s, profile.name))
            .setMessage(t(R.string.ui_1_s_free_up_2_s_on_the_pen_a_verified_backup_will_be_kept_on_this_ph, titleFor(file.name), size(file.size), warning))
            .setNegativeButton(t(R.string.ui_cancel), null).setPositiveButton(t(R.string.ui_remove)) { _, _ ->
                navigate(Screen.TRANSFERS); app.remove(file, profile.id)
            }.show()
    }

    private fun confirmRestore(backup: RemovalBackup) {
        val profile = app.viewedProfile ?: return
        AlertDialog.Builder(this).setTitle(t(R.string.ui_restore_to_1_s, profile.name))
            .setMessage(t(R.string.ui_1_s_2_s_from_this_phone_s_backup_existing_filenames_are_preserved, titleFor(backup.filename), size(backup.size)))
            .setNegativeButton(t(R.string.ui_cancel), null).setPositiveButton(t(R.string.ui_restore)) { _, _ ->
                navigate(Screen.TRANSFERS); app.restore(backup, profile.id)
            }.show()
    }

    private fun setSort(value: PenSort) {
        penSort = value
        getPreferences(MODE_PRIVATE).edit().putString("penSort", value.name).apply()
    }

    private fun reviewOldest() {
        setSort(PenSort.OLDEST)
        queries[Screen.INSTALLED] = ""
        navigate(Screen.INSTALLED)
    }

    @Suppress("DEPRECATION")
    private fun chooseFolder(drive: StorageVolume? = null) {
        if (app.demoMode) { showMessage(t(R.string.ui_demo_banner), t(R.string.ui_demo_readonly)); return }
        if (app.busy) return
        connectCanceled = false; connectError = null
        startActivityForResult(UsbConnection.folderIntent(drive, if (drive == null) app.tree else null), 41)
    }

    @Deprecated("Platform activity result bridge")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 41) return
        if (resultCode != RESULT_OK || data?.data == null) { connectCanceled = true; render(); return }
        data.data?.let { uri ->
            try {
                val flags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                check(flags == (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)) { t(R.string.ui_read_and_write_access_are_needed) }
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                connectReading = true; connectError = null; connectIgnoredKey = null
                if (!app.selectTree(uri)) { connectReading = false; connectError = t(R.string.connect_busy); render() }
            } catch (e: Exception) { connectError = e.message ?: t(R.string.ui_access_was_not_granted); render() }
        }
    }

    private fun navRow(symbol: UiIcon, title: String, subtitle: String, action: () -> Unit): LinearLayout {
        val line = row().apply {
            setPadding(dp(16), dp(18), dp(12), dp(18))
            background = ripple(Color.WHITE, 16); isClickable = true; isFocusable = true
            contentDescription = "$title. $subtitle"; setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
        }
        line.addView(icon(symbol, green, 25))
        val labels = column().apply { addView(text(title, 17, ink, true)); gap(this, 5); addView(text(subtitle, 13, muted)) }
        line.addView(labels, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(16); marginEnd = dp(10) })
        line.addView(icon(UiIcon.CHEVRON, muted, 18))
        return line
    }

    private fun bookRow(title: String, subtitle: String, installed: Boolean, book: BookInfo? = null, action: () -> Unit): LinearLayout {
        val line = navRow(if (installed) UiIcon.CHECK else UiIcon.BOOKS, title, subtitle, action)
        if (book != null) {
            line.removeViewAt(0)
            line.addView(coverView(book, book.thumbUrl, 180), 0, LinearLayout.LayoutParams(dp(50), dp(64)))
        }
        val labels = line.getChildAt(1) as LinearLayout
        (labels.getChildAt(0) as TextView).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END; textSize = 16f }
        return line
    }
    private fun coverView(book: BookInfo, url: String?, pixels: Int) = CoverView(this).apply {
        setImageDrawable(IconDrawable(UiIcon.BOOKS, green))
        scaleType = ImageView.ScaleType.FIT_CENTER
        contentDescription = t(R.string.ui_cover_of_1_s, book.title)
        if (app.demoMode) setImageResource(DemoLibrary.cover(book.article))
        else app.covers.bind(this, url, pixels)
    }
    private fun emptyState(symbol: UiIcon, title: String, subtitle: String? = null) {
        gap(body, 36); body.addView(icon(symbol, green, 48)); gap(body, 20)
        body.addView(text(title, 24, ink, true))
        if (subtitle != null) { gap(body, 10); body.addView(text(subtitle, 15, muted)) }
        gap(body, 28)
    }
    private fun chip(label: String, symbol: UiIcon) = row().apply {
        setPadding(dp(12), dp(10), dp(12), dp(10)); background = rounded(soft, 12)
        addView(icon(symbol, green, 17)); addView(text(label, 14, green, true), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) })
        layoutParams = LinearLayout.LayoutParams(-2, -2)
    }
    private fun action(label: String, primary: Boolean = true, action: () -> Unit) = Button(this).apply {
        text = label; textSize = 16f; isAllCaps = false; setTextColor(if (primary) Color.WHITE else green)
        setTypeface(typeface, Typeface.BOLD); minHeight = dp(52)
        background = ripple(if (primary) green else pageColor, 14)
        stateListAnimator = null
        elevation = if (primary) dp(2).toFloat() else 0f
        isEnabled = !app.busy; alpha = if (isEnabled) 1f else .45f
        setOnClickListener { action() }
        setPadding(dp(12), dp(12), dp(12), dp(12))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8); bottomMargin = dp(4) }
    }
    private fun iconButton(symbol: UiIcon, label: String, action: () -> Unit) = ImageButton(this).apply {
        setImageDrawable(IconDrawable(symbol, ink)); contentDescription = label
        background = ripple(Color.WHITE, 24); setPadding(dp(12), dp(12), dp(12), dp(12))
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)); setOnClickListener { action() }
    }
    private fun icon(symbol: UiIcon, color: Int, size: Int) = ImageView(this).apply {
        setImageDrawable(IconDrawable(symbol, color)); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
    }
    private fun text(value: String, size: Int, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }
    private fun card(color: Int = Color.WHITE) = column().apply {
        setPadding(dp(22), dp(24), dp(22), dp(24)); background = rounded(color, 20)
        layoutParams = LinearLayout.LayoutParams(-1, -2)
    }
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun ripple(color: Int, radius: Int) = RippleDrawable(ColorStateList.valueOf(0x26006B5E), rounded(color, radius), rounded(Color.WHITE, radius))
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun gap(parent: LinearLayout, height: Int) { parent.addView(View(this), LinearLayout.LayoutParams(1, dp(height))) }
    private fun onPen(filename: String) = app.visibleFiles.any { it.name.equals(filename, true) }
    private fun titleFor(filename: String): String {
        val entry = app.catalog.find { it.filename.equals(filename, true) }
        if (entry != null) return app.title(entry)
        val id = app.visibleFiles.find { it.name.equals(filename, true) }?.id ?: app.backup(filename)?.audioId
        return app.book(filename, id)?.title ?: filename.removeSuffix(".gme").replace('_', ' ')
    }
    private fun hideKeyboard() { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(root.windowToken, 0); root.clearFocus() }
    private fun showMessage(title: String, message: String) { AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton(t(R.string.ui_done), null).show() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun dateTime(millis: Long) = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, resources.configuration.locales[0]).format(Date(millis))
    private fun date(millis: Long?) = millis?.let { DateFormat.getDateInstance(DateFormat.MEDIUM, resources.configuration.locales[0]).format(Date(it)) } ?: t(R.string.ui_date_unavailable)
    private fun size(bytes: Long) = if (bytes >= 1_000_000_000) t(R.string.ui_gb, bytes / 1e9) else t(R.string.ui_mb, bytes / 1e6)
}
