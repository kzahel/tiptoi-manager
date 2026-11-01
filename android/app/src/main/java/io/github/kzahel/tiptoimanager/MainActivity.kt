package io.github.kzahel.tiptoimanager

import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
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
import android.widget.ScrollView
import android.widget.TextView
import androidx.drawerlayout.widget.DrawerLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.GravityCompat
import java.util.Locale

private enum class Screen(val title: String) {
    PEN("My pen"), INSTALLED("Installed books"), STORAGE("Storage"),
    BROWSE("Browse books"), DOWNLOADS("Downloads"), BOOK("Book details"),
    TRANSFERS("Transfers"), HELP("Help & about")
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
    private var screen = Screen.PEN
    private var selectedKey: String? = null
    private var selectedFile: String? = null
    private var progressLabel: TextView? = null
    private var progressBar: ProgressBar? = null
    private var busyLabel: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.let { state ->
            screen = runCatching { Screen.valueOf(state.getString("screen")!!) }.getOrDefault(Screen.PEN)
            selectedKey = state.getString("bookKey"); selectedFile = state.getString("bookFile")
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
        app.changed = { if (!isFinishing && !isDestroyed) render() }
        app.progressed = { updateProgress() }
        render()
    }

    override fun onPause() {
        app.changed = null; app.progressed = null
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("screen", screen.name)
        outState.putString("bookKey", selectedKey); outState.putString("bookFile", selectedFile)
        outState.putStringArrayList("backStack", ArrayList(backStack.map { it.name }))
        queries.forEach { (key, value) -> outState.putString("query_${key.name}", value) }
        super.onSaveInstanceState(outState)
    }

    private fun goBack() {
        when {
            drawer.isDrawerOpen(GravityCompat.START) -> drawer.closeDrawer(GravityCompat.START)
            backStack.isNotEmpty() -> { screen = backStack.removeAt(backStack.lastIndex); render() }
            screen != Screen.PEN -> navigate(Screen.PEN, false)
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
        brand.addView(text("Tiptoi Manager", 21, ink, true), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12) })
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
        section("DEVICE"); item(Screen.PEN, UiIcon.PEN)
        section("LIBRARY"); item(Screen.BROWSE, UiIcon.BOOKS); item(Screen.DOWNLOADS, UiIcon.DOWNLOAD)
        section("ACTIVITY"); item(Screen.TRANSFERS, UiIcon.TRANSFER)
        section("SUPPORT"); item(Screen.HELP, UiIcon.INFO)
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
        val active = if (screen == Screen.INSTALLED || screen == Screen.STORAGE) Screen.PEN else screen
        navItems.forEach { (destination, view) ->
            view.isSelected = destination == active
            view.background = rounded(if (destination == active) soft else Color.WHITE, 14)
        }
        drawerConnection.text = if (app.pen != null) "Pen connected" else "No pen connected"
        toolbar()
        if (app.busy && screen != Screen.TRANSFERS) {
            val strip = row().apply {
                setPadding(dp(20), dp(10), dp(16), dp(10)); setBackgroundColor(soft)
                isClickable = true; isFocusable = true
                setOnClickListener { navigate(Screen.TRANSFERS, false) }
            }
            strip.addView(ProgressBar(this).apply { isIndeterminate = true }, LinearLayout.LayoutParams(dp(20), dp(20)))
            busyLabel = text(if (app.transfer?.running == true) "Transfer in progress" else app.status, 13, green)
            strip.addView(busyLabel, LinearLayout.LayoutParams(-1, -2).apply { marginStart = dp(12) })
            root.addView(strip)
        } else if (app.failure && !(screen == Screen.TRANSFERS && app.transfer?.failed == true)) {
            val problem = text("Action failed · View details", 13, 0xFF9B2A22.toInt(), true).apply {
                setPadding(dp(20), dp(12), dp(20), dp(12)); setBackgroundColor(0xFFFDECE8.toInt())
                isClickable = true; isFocusable = true
                setOnClickListener { showMessage("Couldn't complete the action", app.status) }
            }
            root.addView(problem)
        }
        when (screen) {
            Screen.BROWSE, Screen.INSTALLED, Screen.DOWNLOADS -> listScreen()
            else -> {
                scrollBody()
                when (screen) {
                    Screen.PEN -> penScreen()
                    Screen.STORAGE -> storageScreen()
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
        val child = screen in listOf(Screen.INSTALLED, Screen.STORAGE, Screen.BOOK)
        header.addView(iconButton(if (child) UiIcon.BACK else UiIcon.MENU, if (child) "Back" else "Open navigation menu") {
            if (child) goBack() else { hideKeyboard(); drawer.openDrawer(GravityCompat.START) }
        })
        header.addView(text(screen.title, 22, ink, true), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
        when (screen) {
            Screen.PEN, Screen.INSTALLED, Screen.STORAGE -> if (app.tree != null) header.addView(iconButton(UiIcon.REFRESH, "Refresh pen") { app.refreshPen() }.apply { isEnabled = !app.busy })
            Screen.BROWSE -> header.addView(iconButton(UiIcon.REFRESH, "Refresh catalog") { app.refreshCatalog() }.apply { isEnabled = !app.busy })
            Screen.BOOK -> header.addView(iconButton(UiIcon.INFO, "File details") { fileDetails() })
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

    private fun penScreen() {
        val pen = app.pen
        if (pen == null) {
            emptyState(UiIcon.PEN, if (app.busy) "Looking for your pen…" else "Connect your pen", "Use a USB data cable or adapter.")
            body.addView(action("Choose USB folder") { chooseFolder() })
            if (app.tree != null) body.addView(action("Scan again", false) { app.refreshPen() })
            return
        }
        val hero = card(green)
        hero.addView(icon(UiIcon.PEN, Color.WHITE, 48))
        gap(hero, 16)
        hero.addView(text("tiptoi", 30, Color.WHITE, true))
        hero.addView(text("Connected by USB", 14, 0xFFD0EEE4.toInt()))
        body.addView(hero)
        gap(body, 24)
        body.addView(text("ON YOUR PEN", 11, muted, true).apply { letterSpacing = .1f })
        gap(body, 10)
        body.addView(navRow(UiIcon.BOOKS, "Installed books", "${pen.files.size} audio files") { navigate(Screen.INSTALLED) })
        body.addView(navRow(UiIcon.STORAGE, "Storage", pen.free?.let { "${size(it)} free" } ?: "View storage") { navigate(Screen.STORAGE) })
        gap(body, 24)
        body.addView(action("Browse books") { navigate(Screen.BROWSE, false) })
        body.addView(action("Connection settings", false) {
            AlertDialog.Builder(this).setTitle("Connection settings")
                .setItems(arrayOf("Scan again", "Choose another USB folder")) { _, which ->
                    if (which == 0) app.refreshPen() else chooseFolder()
                }.setNegativeButton("Cancel", null).show()
        })
    }

    private fun storageScreen() {
        val pen = app.pen
        if (pen == null) { emptyState(UiIcon.STORAGE, "No pen connected"); return }
        val summary = card()
        summary.addView(icon(UiIcon.STORAGE, green, 36)); gap(summary, 16)
        summary.addView(text(pen.free?.let(::size) ?: "Unknown", 36, ink, true))
        summary.addView(text("Free space", 15, muted))
        body.addView(summary); gap(body, 20)
        body.addView(navRow(UiIcon.BOOKS, "Audio files", "${pen.files.size} files · ${size(pen.bytes)}") { navigate(Screen.INSTALLED) })
    }

    private fun listScreen() {
        val search = EditText(this).apply {
            hint = "Search titles or IDs"; contentDescription = "Search books"
            setSingleLine(true); setTextColor(ink); textSize = 16f
            background = rounded(Color.WHITE, 14)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setText(queries[screen].orEmpty())
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, _, _ -> hideKeyboard(); clearFocus(); true }
        }
        root.addView(search, LinearLayout.LayoutParams(-1, dp(54)).apply { setMargins(dp(20), dp(16), dp(20), 0) })
        scrollBody()
        fun populate() {
            body.removeAllViews()
            val query = queries[screen].orEmpty().trim()
            fun matches(title: String, id: Int?) = query.isEmpty() || title.contains(query, true) || id?.toString() == query
            if (screen == Screen.INSTALLED) {
                val pen = app.pen
                if (pen == null) { emptyState(UiIcon.PEN, "No pen connected"); return }
                val files = pen.files.filter { matches(titleFor(it.name), it.id) }
                body.addView(text("${files.size} audio files", 13, muted)); gap(body, 12)
                if (files.isEmpty()) emptyState(UiIcon.BOOKS, "No matches")
                files.forEach { file -> body.addView(bookRow(titleFor(file.name), size(file.size), true) {
                    openBook(app.catalog.find { it.filename.equals(file.name, true) }, file.name)
                }) }
            } else {
                val entries = app.catalog.filter { (screen != Screen.DOWNLOADS || app.cached(it)) && matches(it.title, it.id) }
                body.addView(text("${entries.size} ${if (screen == Screen.DOWNLOADS) "downloads" else "items"}", 13, muted)); gap(body, 12)
                if (entries.isEmpty()) {
                    when {
                        query.isNotEmpty() -> emptyState(UiIcon.BOOKS, "No matches")
                        screen == Screen.DOWNLOADS -> {
                            emptyState(UiIcon.DOWNLOAD, "No downloads yet", "Save books for offline installation.")
                            body.addView(action("Browse books") { navigate(Screen.BROWSE, false) })
                        }
                        else -> { emptyState(UiIcon.BOOKS, "Load your library"); body.addView(action("Load catalog") { app.refreshCatalog() }) }
                    }
                }
                entries.take(80).forEach { entry ->
                    val present = onPen(entry.filename)
                    val subtitle = listOfNotNull(if (present) "On pen" else if (app.cached(entry)) "Downloaded" else null, size(entry.size)).joinToString(" · ")
                    body.addView(bookRow(entry.title, subtitle, present) { openBook(entry, entry.filename) })
                }
                if (entries.size > 80) { gap(body, 12); body.addView(text("Search to see more titles", 13, muted)) }
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

    private fun bookScreen() {
        val entry = selectedEntry()
        val filename = entry?.filename ?: selectedFile ?: return
        val file = app.pen?.files?.find { it.name.equals(filename, true) }
        val cached = entry?.let(app::cached) == true
        val present = file != null
        val cover = card(soft)
        cover.gravity = Gravity.CENTER_HORIZONTAL
        cover.setPadding(dp(24), dp(36), dp(24), dp(36))
        cover.addView(icon(UiIcon.BOOKS, green, 64))
        body.addView(cover); gap(body, 24)
        body.addView(text(entry?.title ?: titleFor(filename), 26, ink, true))
        gap(body, 12)
        body.addView(text(size(entry?.size ?: file?.size ?: 0), 15, muted))
        gap(body, 20)
        if (present) { body.addView(chip("On your pen", UiIcon.CHECK)); gap(body, 10) }
        if (cached) { body.addView(chip("Saved on this phone", UiIcon.DOWNLOAD)); gap(body, 10) }
        if (file?.problem != null) body.addView(text(file.problem, 14, 0xFF9B2A22.toInt()))
        gap(body, 20)
        when {
            entry == null -> Unit
            !cached -> body.addView(action("Download") { navigate(Screen.TRANSFERS); app.download(entry) })
            !present && app.pen != null -> body.addView(action("Install on pen") { confirmInstall(entry) })
            !present -> body.addView(action("Connect a pen") { navigate(Screen.PEN, false) })
        }
        if (cached) body.addView(action("Download again", false) {
            AlertDialog.Builder(this).setTitle("Download again?").setMessage(entry.title)
                .setNegativeButton("Cancel", null).setPositiveButton("Download") { _, _ ->
                    navigate(Screen.TRANSFERS); app.download(entry)
                }.show()
        })
    }

    private fun fileDetails() {
        val entry = selectedEntry()
        val file = app.pen?.files?.find { it.name.equals(selectedFile, true) }
        showMessage("File details", listOfNotNull(selectedFile, (entry?.id ?: file?.id)?.let { "Product ID $it" },
            entry?.version?.let { "Catalog version $it" }, file?.problem).joinToString("\n\n"))
    }

    private fun transfersScreen() {
        val transfer = app.transfer
        if (transfer == null) {
            if (app.busy) { emptyState(UiIcon.REFRESH, "Working…"); progressLabel = text(app.status, 15, muted); body.addView(progressLabel) }
            else { emptyState(UiIcon.TRANSFER, "No active transfers", "Downloads and installs appear here."); body.addView(action("Browse books") { navigate(Screen.BROWSE, false) }) }
            return
        }
        val entry = app.catalog.find { it.cacheKey == transfer.bookKey }
        val symbol = if (transfer.running) UiIcon.TRANSFER else if (transfer.failed) UiIcon.INFO else UiIcon.CHECK
        val heading = if (transfer.running) "${transfer.action} in progress" else if (transfer.failed) "${transfer.action} failed" else "${transfer.action} complete"
        val summary = card()
        summary.addView(icon(symbol, if (transfer.failed) 0xFF9B2A22.toInt() else green, 48)); gap(summary, 20)
        summary.addView(text(heading, 25, ink, true)); gap(summary, 12)
        if (entry != null) summary.addView(text(entry.title, 17, ink))
        progressLabel = text(transfer.stage, 14, muted)
        gap(summary, 16); summary.addView(progressLabel)
        if (transfer.running) {
            progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
            summary.addView(progressBar, LinearLayout.LayoutParams(-1, dp(8)).apply { topMargin = dp(16) })
            gap(summary, 14); summary.addView(text("Keep the app open and the pen connected.", 13, muted))
        }
        body.addView(summary); gap(body, 24)
        if (!transfer.running && entry != null) body.addView(action("View book") { openBook(entry, entry.filename) })
        if (transfer.failed) body.addView(action("Connection help", false) { navigate(Screen.HELP, false) })
        updateProgress()
    }

    private fun updateProgress() {
        val transfer = app.transfer
        progressLabel?.text = transfer?.stage ?: app.status
        progressBar?.let { bar -> bar.isIndeterminate = transfer?.percent == null; transfer?.percent?.let { bar.progress = it } }
        busyLabel?.text = if (transfer?.running == true) "${transfer.action} · ${transfer.percent?.let { "$it%" } ?: transfer.stage}" else app.status
    }

    private fun helpScreen() {
        body.addView(text("Getting started", 22, ink, true)); gap(body, 20)
        body.addView(navRow(UiIcon.PEN, "1. Connect your pen", "Use a USB data cable or adapter.") { navigate(Screen.PEN, false) })
        body.addView(navRow(UiIcon.BOOKS, "2. Find a book", "Browse or search the catalog.") { navigate(Screen.BROWSE, false) })
        body.addView(navRow(UiIcon.DOWNLOAD, "3. Download & install", "Keep the pen connected until done.") { navigate(Screen.DOWNLOADS, false) })
        gap(body, 28)
        val about = card()
        about.addView(text("Tiptoi Manager", 19, ink, true)); gap(about, 8)
        about.addView(text("Independent, open-source USB manager.\nNo account. Your library stays on this phone.", 14, muted))
        gap(about, 12)
        about.addView(text("Experimental preview · 0.1.0", 12, muted))
        gap(about, 12)
        about.addView(action("Open-source licenses", false) {
            val notices = assets.open("NOTICE.txt").bufferedReader().use { it.readText() }
            val projectLicense = assets.open("MIT.txt").bufferedReader().use { it.readText() }
            val license = assets.open("Apache-2.0.txt").bufferedReader().use { it.readText() }
            showMessage("Open-source licenses", "$notices\n\n$projectLicense\n\n$license")
        })
        body.addView(about)
    }

    private fun confirmInstall(entry: CatalogEntry) {
        val pen = app.pen ?: return
        val unknown = if (pen.free == null) "\nFree space is unavailable." else ""
        AlertDialog.Builder(this).setTitle("Install on your pen?")
            .setMessage("${entry.title}\n${size(entry.size)} · Existing files stay untouched.$unknown")
            .setNegativeButton("Cancel", null).setPositiveButton("Install") { _, _ ->
                navigate(Screen.TRANSFERS); app.install(entry)
            }.show()
    }

    @Suppress("DEPRECATION")
    private fun chooseFolder() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            app.tree?.let { putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
        }, 41)
    }

    @Deprecated("Platform activity result bridge")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 41 && resultCode == RESULT_OK) data?.data?.let { uri ->
            try {
                val flags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                check(flags == (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)) { "Read and write access are needed." }
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                screen = Screen.PEN; backStack.clear(); app.selectTree(uri)
            } catch (e: Exception) { showMessage("Folder access", e.message ?: "Access was not granted.") }
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

    private fun bookRow(title: String, subtitle: String, installed: Boolean, action: () -> Unit): LinearLayout {
        val line = navRow(if (installed) UiIcon.CHECK else UiIcon.BOOKS, title, subtitle, action)
        val labels = line.getChildAt(1) as LinearLayout
        (labels.getChildAt(0) as TextView).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END; textSize = 16f }
        return line
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
        layoutParams = LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(8); bottomMargin = dp(4) }
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
    private fun onPen(filename: String) = app.pen?.files?.any { it.name.equals(filename, true) } == true
    private fun titleFor(filename: String) = app.catalog.find { it.filename.equals(filename, true) }?.title ?: filename.removeSuffix(".gme").replace('_', ' ')
    private fun hideKeyboard() { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(root.windowToken, 0); root.clearFocus() }
    private fun showMessage(title: String, message: String) { AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("Done", null).show() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun size(bytes: Long) = if (bytes >= 1_000_000_000) String.format(Locale.ROOT, "%.2f GB", bytes / 1e9) else String.format(Locale.ROOT, "%.1f MB", bytes / 1e6)
}
