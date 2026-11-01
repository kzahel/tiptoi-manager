package io.github.kzahel.tiptoimanager

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList

/** Resource lookup for UI and background work; stored values stay language independent. */
object UiText {
    private var application: Context? = null
    fun initialize(context: Context) { application = context.applicationContext }
    fun context(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return base
        val language = base.getSharedPreferences("language", Context.MODE_PRIVATE).getString("tag", "") ?: ""
        if (language.isEmpty()) return base
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList.forLanguageTags(language))
        return base.createConfigurationContext(config)
    }
    fun language(base: Context): String = if (Build.VERSION.SDK_INT >= 33)
        base.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags()
        else base.getSharedPreferences("language", Context.MODE_PRIVATE).getString("tag", "") ?: ""
    fun setLanguage(base: Context, tag: String) {
        require(tag in setOf("", "en", "de", "fr"))
        if (Build.VERSION.SDK_INT >= 33) base.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(tag)
        else base.getSharedPreferences("language", Context.MODE_PRIVATE).edit().putString("tag", tag).commit()
    }
    fun text(id: Int, vararg args: Any?): String = application?.let { context(it).getString(id, *args) } ?: "message:$id"
    fun plural(id: Int, count: Int, vararg args: Any?): String = application?.let { context(it).resources.getQuantityString(id, count, *args) } ?: "$count"
    fun quantity(id: Int, count: Int): String = application?.let { context(it).resources.getQuantityString(id, count, count) } ?: "$count"
}
fun t(id: Int, vararg args: Any?) = UiText.text(id, *args)
fun p(id: Int, count: Int, vararg args: Any?) = UiText.plural(id, count, *args)
fun q(id: Int, count: Int) = UiText.quantity(id, count)

fun taskText(code: String): String = when (code) {
    "Loading local library" -> t(R.string.task_loading_local_library)
    "Reading pen" -> t(R.string.task_reading_pen)
    "Naming pen" -> t(R.string.task_naming_pen)
    "Forgetting pen" -> t(R.string.task_forgetting_pen)
    "Saving pen selection" -> t(R.string.task_saving_pen_selection)
    "Saving book selection" -> t(R.string.task_saving_book_selection)
    "Downloading catalog" -> t(R.string.task_downloading_catalog)
    "Download" -> t(R.string.task_download)
    "Sync" -> t(R.string.task_sync)
    "Install" -> t(R.string.task_install)
    "Deleting phone download" -> t(R.string.task_deleting_phone_download)
    "Deleting phone backups" -> t(R.string.task_deleting_phone_backups)
    "Clearing phone audio" -> t(R.string.task_clearing_phone_audio)
    "Remove" -> t(R.string.task_remove)
    "Restore" -> t(R.string.task_restore)
    else -> code
}
