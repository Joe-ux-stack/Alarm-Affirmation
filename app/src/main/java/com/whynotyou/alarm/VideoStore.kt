package com.whynotyou.alarm

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.File

/**
 * Keeps the user's chosen wake-up video as a private copy in app storage, so it plays
 * even after the picker's URI permission expires or the file moves.
 */
object VideoStore {

    private const val PREFS = "video"
    private const val KEY_NAME = "chosen_name"
    private const val KEY_DURATION = "chosen_duration_ms"
    private const val FILE = "wakeup_custom.mp4"

    /** Copies the picked video into app storage. Returns false if it couldn't be read. */
    fun importFrom(context: Context, uri: Uri): Boolean {
        val dest = File(context.filesDir, FILE)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { out -> input.copyTo(out) }
            } ?: return false
        } catch (e: Exception) {
            dest.delete()
            return false
        }

        val name = queryDisplayName(context, uri) ?: "Custom video"
        val duration = readDurationMs(dest)
        prefs(context).edit()
            .putString(KEY_NAME, name)
            .putLong(KEY_DURATION, duration)
            .apply()
        return true
    }

    fun clear(context: Context) {
        File(context.filesDir, FILE).delete()
        prefs(context).edit().clear().apply()
    }

    fun hasCustom(context: Context): Boolean = File(context.filesDir, FILE).exists()

    fun customName(context: Context): String? =
        if (hasCustom(context)) prefs(context).getString(KEY_NAME, "Custom video") else null

    /** Uri the player should load: the custom file if present, else the bundled clip. */
    fun playbackUri(context: Context): Uri =
        if (hasCustom(context)) Uri.fromFile(File(context.filesDir, FILE))
        else Uri.parse("android.resource://${context.packageName}/${R.raw.wakeup}")

    /** Length of whatever will play; used to decide when "I'm up" unlocks. */
    fun playbackDurationMs(context: Context): Long =
        if (hasCustom(context)) prefs(context).getLong(KEY_DURATION, 10_000L).coerceAtLeast(1_000L)
        else 10_000L

    private fun readDurationMs(file: File): Long = try {
        MediaMetadataRetriever().run {
            setDataSource(file.absolutePath)
            val d = extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            release()
            d ?: 10_000L
        }
    } catch (e: Exception) { 10_000L }

    private fun queryDisplayName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (e: Exception) { null }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
