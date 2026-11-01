package io.github.kzahel.tiptoimanager

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.DocumentsContract
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

class MainActivity : Activity() {
    private val app get() = application as ManagerApp
    private val green = Color.rgb(0, 107, 94)
    private val ink = Color.rgb(25, 45, 39)
    private val muted = Color.rgb(85, 104, 96)
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var body: LinearLayout
    private var tab = 0
    private var query = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tab = savedInstanceState?.getInt("tab") ?: 0
        query = savedInstanceState?.getString("query").orEmpty()
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(246, 248, 245))
            setPadding(dp(20), dp(12), dp(20), 0)
        }
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                view.setPadding(dp(20) + bars.left, dp(12) + bars.top, dp(20) + bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(dp(20) + insets.systemWindowInsetLeft, dp(12) + insets.systemWindowInsetTop,
                    dp(20) + insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        setContentView(root)
        render()
    }

    override fun onResume() {
        super.onResume()
        app.changed = { if (!isFinishing && !isDestroyed) render() }
        app.progressed = { if (::status.isInitialized) status.text = app.status }
        render()
    }

    override fun onPause() {
        app.changed = null; app.progressed = null
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("tab", tab); outState.putString("query", query)
        super.onSaveInstanceState(outState)
    }

    private fun render() {
        if (app.busy) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        root.removeAllViews()
        root.addView(text("TIPTOI MANAGER", 12, green, true).apply { letterSpacing = .12f })
        root.addView(text(if (tab == 0) { if (app.pen == null) "Connect your pen." else "Your pen, connected." } else "A library to take along.", 28, ink, true))
        root.addView(text("USB books · stored locally · no account", 14, muted))
        val tabs = row()
        tabs.addView(button("Connected pen", "pen_tab") { tab = 0; render() }, weighted())
        tabs.addView(button("Library", "library_tab") { tab = 1; render() }, weighted())
        root.addView(tabs)
        val banner = panel()
        status = text(app.status, 14, if (app.failure) Color.rgb(155, 42, 34) else ink)
        status.contentDescription = "Operation status"
        banner.addView(status)
        if (app.busy) banner.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
        })
        root.addView(banner)
        val scroll = ScrollView(this).apply { isFillViewport = true }
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        if (tab == 0) penView() else libraryView()
    }

    private fun penView() {
        val actions = row()
        actions.addView(button(if (app.tree == null) "Choose pen folder" else "Change pen folder", "choose_pen") { chooseFolder() }, weighted())
        if (app.tree != null) actions.addView(button("Scan again", "scan_pen") { app.refreshPen() }, weighted())
        body.addView(actions)
        val pen = app.pen
        if (pen == null) {
            body.addView(text("Plug the pen into your phone using a data cable or OTG adapter, then choose its top-level USB folder.", 17, ink))
            body.addView(text("Only the folder you select is accessible. Existing books and pen settings are preserved.", 14, muted))
            return
        }
        val summary = panel()
        summary.addView(text("${pen.files.size} files · ${pen.ids} product IDs", 21, ink, true))
        summary.addView(text("${size(pen.bytes)} of GME content", 14, muted))
        summary.addView(text(pen.free?.let { "${size(it)} free on pen" } ?: "Free space is not reported by this storage provider.", 14, muted))
        summary.addView(text("Pen identity ${pen.fingerprint.take(8)} · last scanned this session", 12, muted))
        body.addView(summary)
        body.addView(text("Installed content", 20, ink, true))
        body.addView(text("Several files may share one product ID. Companion files stay together.", 13, muted))
        pen.files.forEach { file ->
            val card = panel()
            val entry = app.catalog.find { it.filename.equals(file.name, true) }
            card.addView(text(entry?.title ?: file.name.removeSuffix(".gme").replace('_', ' '), 16, ink, true))
            card.addView(text("${file.id?.let { "ID $it" } ?: "Invalid header"} · ${size(file.size)}", 13, muted))
            if (file.problem != null) card.addView(text(file.problem, 13, Color.RED))
            body.addView(card)
        }
    }

    private fun libraryView() {
        body.addView(button("Refresh catalog", "refresh_catalog") { app.refreshCatalog() })
        body.addView(text("German catalog · fetched only when you ask. Downloaded files stay on this phone for offline installation.", 13, muted))
        val search = EditText(this).apply {
            hint = "Search title, filename or product ID"
            contentDescription = "Search books"
            setSingleLine(true)
            setText(query)
            setTextColor(ink)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        body.addView(search)
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(results)
        fun showResults() {
            results.removeAllViews()
            val matches = app.catalog.filter { it.title.contains(query, true) || it.filename.contains(query, true) || it.id.toString() == query.trim() }
            results.addView(text("${matches.size} content files${if (matches.size > 80) " · showing 80, search to narrow" else ""}", 13, muted))
            matches.take(80).forEach { entry ->
                val card = panel()
                card.addView(text(entry.title, 17, ink, true))
                card.addView(text("ID ${entry.id} · ${size(entry.size)} · version ${entry.version}", 12, muted))
                val present = app.pen?.files?.any { it.name.equals(entry.filename, true) } == true
                val cached = app.cached(entry)
                if (present) card.addView(text("On connected pen", 13, green, true))
                if (cached) card.addView(text("Saved offline · verified at download", 13, green))
                val actions = row()
                actions.addView(button(if (cached) "Download again" else "Download", "download_${entry.id}_${entry.filename}") { app.download(entry) }
                    .apply { contentDescription = "Download ${entry.title}" }, weighted())
                if (cached && !present && app.pen != null) actions.addView(button("Install on pen", "install_${entry.id}_${entry.filename}") {
                    confirmInstall(entry)
                }.apply { contentDescription = "Install ${entry.title} on pen" }, weighted())
                card.addView(actions)
                results.addView(card)
            }
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { query = s.toString(); showResults() }
            override fun afterTextChanged(s: Editable?) {}
        })
        showResults()
    }

    private fun confirmInstall(entry: CatalogEntry) {
        val pen = app.pen ?: return
        val unknownSpace = if (pen.free == null) "\n\nThe provider cannot report free space. A full drive will interrupt the transfer." else ""
        AlertDialog.Builder(this).setTitle("Install ${entry.title}?")
            .setMessage("Copy ${size(entry.size)} to pen ${pen.fingerprint.take(8)}. Existing files are preserved. Keep the pen connected and this app open until verification finishes.$unknownSpace")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Install") { _, _ -> app.install(entry) }.show()
    }

    @Suppress("DEPRECATION")
    private fun chooseFolder() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            app.tree?.let { putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
        }, 41)
    }

    @Deprecated("Platform activity result bridge for a dependency-light spike")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 41 && resultCode == RESULT_OK) data?.data?.let { uri ->
            try {
                val flags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                check(flags == Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) {
                    "Both read and write folder access are needed."
                }
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                app.selectTree(uri)
            } catch (e: Exception) { AlertDialog.Builder(this).setMessage(e.message ?: "Folder access was not granted.").setPositiveButton("OK", null).show() }
        }
    }

    private fun text(value: String, size: Int, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(color)
        setPadding(0, dp(5), 0, dp(5))
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }
    private fun button(label: String, id: String, action: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; setTextColor(green); textSize = 14f
        tag = id
        contentDescription = label
        isEnabled = !app.busy
        setOnClickListener { action() }
        minHeight = dp(48)
    }
    private fun panel() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(10), dp(14), dp(10))
        background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(16).toFloat() }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(8), 0, dp(8)) }
    }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun weighted() = LinearLayout.LayoutParams(0, -2, 1f)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun size(bytes: Long) = if (bytes >= 1_000_000_000) String.format(Locale.ROOT, "%.2f GB", bytes / 1e9)
        else String.format(Locale.ROOT, "%.1f MB", bytes / 1e6)
}
