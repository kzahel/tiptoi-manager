package io.github.kzahel.tiptoimanager

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.Executors

/** Offscreen rows do not request artwork until Android actually draws them. */
class CoverView(context: Context) : ImageView(context) {
    var loadWhenDrawn: (() -> Unit)? = null
    override fun onDraw(canvas: Canvas) {
        loadWhenDrawn?.let { loadWhenDrawn = null; post { it() } }
        super.onDraw(canvas)
    }
}

class CoverImages(private val directory: File) {
    private val worker = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private val memory = object : LruCache<String, Bitmap>(12 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = maxOf(1, value.allocationByteCount / 1024)
    }
    private val pending = mutableMapOf<String, MutableList<WeakReference<ImageView>>>()
    private val failed = mutableSetOf<String>()
    private val diskGuard = Any()
    fun clearFailures() { failed.clear() }
    fun file(url: String): File = File(directory, sha256(url.toByteArray()) + ".img")

    fun bind(view: CoverView, url: String?, pixels: Int) {
        if (url == null || !PublisherUrls.image(url)) return
        val target = pixels.coerceIn(48, 1024)
        val key = "$url@$target"
        view.tag = key
        memory.get(key)?.let { view.setImageBitmap(it); return }
        view.loadWhenDrawn = { load(view, url, target, key) }
    }

    private fun load(view: ImageView, url: String, target: Int, key: String) {
        memory.get(key)?.let { view.setImageBitmap(it); return }
        if (key in failed) return
        pending[key]?.let { it.add(WeakReference(view)); return }
        pending[key] = mutableListOf(WeakReference(view))
        worker.execute {
            val bitmap = runCatching {
                val destination = file(url)
                if (!destination.exists()) {
                    val bytes = PublisherHttp.get(url, 5_000_000, PublisherUrls::image)
                    validate(bytes)
                    synchronized(diskGuard) {
                        if (!destination.exists()) atomicWrite(destination, bytes)
                        val files = directory.listFiles()?.filter { it.extension == "img" }?.sortedBy { it.lastModified() }.orEmpty()
                        var total = files.sumOf { it.length() }
                        for (old in files) {
                            if (total <= 64L * 1024 * 1024) break
                            if (old != destination) { val length = old.length(); if (old.delete()) total -= length }
                        }
                    }
                }
                destination.setLastModified(System.currentTimeMillis())
                decode(destination, target) ?: run { destination.delete(); error("Invalid cover image") }
            }.getOrNull()
            main.post {
                if (bitmap != null) memory.put(key, bitmap) else failed.add(key)
                pending.remove(key)?.forEach { reference ->
                    reference.get()?.takeIf { it.tag == key }?.let { if (bitmap != null) it.setImageBitmap(bitmap) }
                }
            }
        }
    }

    private fun validate(bytes: ByteArray) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth in 1..10_000 && bounds.outHeight in 1..10_000) { "Unsupported cover image." }
    }

    private fun decode(source: File, target: Int): Bitmap? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, options)
        if (options.outWidth !in 1..10_000 || options.outHeight !in 1..10_000) return null
        var sample = 1
        while (options.outWidth / sample > target * 2 || options.outHeight / sample > target * 2) sample *= 2
        options.inSampleSize = sample; options.inJustDecodeBounds = false
        return BitmapFactory.decodeFile(source.path, options)
    }
}
