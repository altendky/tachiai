package net.fstab.tachiai.feature.presentation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import net.fstab.tachiai.platform.media.NativeMixedSide
import net.fstab.tachiai.presentation.ScrubPreviewSource
import net.fstab.tachiai.presentation.ScrubSpriteSheets
import net.fstab.tachiai.presentation.ScrubTile
import net.fstab.tachiai.presentation.scrubSheetsToFetch
import net.fstab.tachiai.presentation.scrubTileBounds

// The viewer's scrub previews (#673). UI thread only.
internal interface ViewerScrubPreviews {
    // Delivers the target tile, or null when there is none (yet). A later
    // delivery follows when its sheet arrives while this is still the target.
    fun request(side: NativeMixedSide, positionMs: Long, durationMs: Long, deliver: (Bitmap?) -> Unit)
    // The scrub ended: fetch nothing more for it.
    fun stop()
    // The viewer ended: forget everything.
    fun clear()
}

// Fetches sheets only while scrubbing: the target's sheet first, then its
// neighbours. Two workers let a new target start while a sheet for an older
// one finishes; queued work for an older target is dropped. Decoded sheets stay
// in a small memory cache; nothing is written to disk and no address is
// logged. A failure only means no preview; a new scrub may try again.
internal class ScrubPreviewLoader(
    private val sources: (NativeMixedSide) -> ScrubPreviewSource?,
    cacheBytes: Int = 8 * 1024 * 1024,
) : ViewerScrubPreviews {
    private class Resolved(val source: ScrubPreviewSource, val sheets: ScrubSpriteSheets)
    private class Request(val source: ScrubPreviewSource, val positionMs: Long, val durationMs: Long,
        val deliver: (Bitmap?) -> Unit, val generation: Long)

    private val main = Handler(Looper.getMainLooper())
    private val worker = ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, LinkedBlockingQueue()) { task ->
        Thread(task, "scrub-previews").apply { isDaemon = true }
    }.apply { allowCoreThreadTimeOut(true) }
    private val lock = Any()
    private val resolveLock = Any()
    private val resolved = HashMap<String, Resolved>()
    private val unavailable = HashSet<String>()
    private val failedSheets = HashSet<String>()
    private val loading = HashSet<String>()
    private val sheets = object : LruCache<String, Bitmap>(cacheBytes) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    @Volatile private var generation = 0L
    // The current scrub target, kept until the scrub ends.
    @Volatile private var latest: Request? = null

    override fun request(side: NativeMixedSide, positionMs: Long, durationMs: Long, deliver: (Bitmap?) -> Unit) {
        val source = sources(side)
        val current = ++generation
        val (known, missing) = if (source == null) null to true
            else synchronized(lock) { resolved[source.key] to (source.key in unavailable) }
        if (source == null || missing) { latest = null; deliver(null); return }
        val request = Request(known?.source ?: source, positionMs, durationMs, deliver, current)
        latest = request
        val image = known?.let { tileImage(it, request) }
        deliver(image)
        if (known == null || image == null) worker.execute { serve(request) }
    }

    override fun stop() {
        generation++
        latest = null
        synchronized(lock) { unavailable.clear(); failedSheets.clear() }
    }

    override fun clear() {
        stop()
        synchronized(lock) { resolved.clear() }
        sheets.evictAll()
    }

    private fun current(request: Request) = request.generation == generation

    // Worker thread.
    private fun serve(request: Request) {
        if (!current(request)) return
        val entry = resolve(request) ?: return
        if (entry.sheets.tileAt(request.positionMs, request.durationMs) == null) return
        for (index in scrubSheetsToFetch(entry.sheets, request.positionMs, request.durationMs)) {
            if (!current(request)) return
            val url = entry.sheets.sheetUrl(index) ?: continue
            if (sheets.get(url) != null) { deliverLatest(); continue }
            val claimed = synchronized(lock) { url !in failedSheets && loading.add(url) }
            if (!claimed) continue
            try { if (load(entry.source, url) != null) deliverLatest() }
            finally { synchronized(lock) { loading -= url } }
        }
    }

    private fun resolve(request: Request): Resolved? {
        val key = request.source.key
        synchronized(lock) { resolved[key]?.let { return it } }
        return synchronized(resolveLock) {
            synchronized(lock) {
                resolved[key]?.let { return it }
                if (key in unavailable) return null
            }
            val started = SystemClock.elapsedRealtime()
            val found = runCatching { request.source.resolve() }.getOrNull()
            Log.d(TAG, "resolve=${if (found == null) "NONE" else "READY"} ms=${SystemClock.elapsedRealtime() - started}")
            synchronized(lock) {
                if (found == null) { unavailable += key; null }
                else Resolved(request.source, found).also { resolved[key] = it }
            }
        }
    }

    // Shows the current target's tile if its sheet is now loaded.
    private fun deliverLatest() = main.post {
        val request = latest?.takeIf(::current) ?: return@post
        val entry = synchronized(lock) { resolved[request.source.key] } ?: return@post
        tileImage(entry, request)?.let(request.deliver)
    }

    private fun tileImage(entry: Resolved, request: Request): Bitmap? {
        val tile = entry.sheets.tileAt(request.positionMs, request.durationMs) ?: return null
        val image = sheets.get(tile.sheetUrl) ?: return null
        return crop(entry.sheets, tile, image)
    }

    private fun load(source: ScrubPreviewSource, url: String): Bitmap? {
        val started = SystemClock.elapsedRealtime()
        var outcome = "UNAVAILABLE"
        val bitmap = try {
            source.fetch(url)?.let { outcome = "UNDECODABLE"; decode(it) }
        } catch (_: Exception) { outcome = "FAILED"; null } catch (_: OutOfMemoryError) { outcome = "MEMORY"; null }
        // Fixed outcomes only: never the sheet's address.
        Log.d(TAG, "sheet=${if (bitmap == null) outcome else "LOADED"} ms=${SystemClock.elapsedRealtime() - started}")
        if (bitmap == null) synchronized(lock) { failedSheets += url } else sheets.put(url, bitmap)
        return bitmap
    }

    private companion object { const val TAG = "TachiaiScrubPreview" }

    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth !in 1..4096 || bounds.outHeight !in 1..4096) return null
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 })
    }

    private fun crop(sheets: ScrubSpriteSheets, tile: ScrubTile, image: Bitmap): Bitmap? {
        val bounds = scrubTileBounds(sheets, tile, image.width, image.height) ?: return null
        return runCatching { Bitmap.createBitmap(image, bounds.left, bounds.top, bounds.width, bounds.height) }.getOrNull()
    }
}
