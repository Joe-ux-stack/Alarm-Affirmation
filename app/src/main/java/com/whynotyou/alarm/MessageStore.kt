package com.whynotyou.alarm

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Recordings from people the user loves (voice memos or video). Stored as private copies.
 * Several can be kept; one is picked per morning (rotating).
 */
data class Message(val id: String, val name: String, val from: String, val durationMs: Long, val isVideo: Boolean) {
    fun file(c: Context) = File(c.filesDir, "msg_$id")
}

object MessageStore {

    private const val PREFS = "messages"
    private const val KEY_LIST = "list"
    private const val KEY_NEXT = "next_index"

    fun all(c: Context): List<Message> {
        val arr = JSONArray(prefs(c).getString(KEY_LIST, "[]") ?: "[]")
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Message(o.getString("id"), o.getString("name"), o.optString("from", ""), o.getLong("dur"), o.optBoolean("video", false))
        }.filter { it.file(c).exists() }
    }

    fun import(c: Context, uri: Uri, from: String): Message? {
        val id = System.currentTimeMillis().toString(36)
        val mime = c.contentResolver.getType(uri) ?: ""
        val dest = File(c.filesDir, "msg_$id")
        try {
            c.contentResolver.openInputStream(uri)?.use { i -> dest.outputStream().use { o -> i.copyTo(o) } } ?: return null
        } catch (e: Exception) { dest.delete(); return null }

        val name = displayName(c, uri) ?: "Message"
        val dur = duration(dest)
        val msg = Message(id, name, from, dur, mime.startsWith("video"))
        save(c, all(c) + msg)
        return msg
    }

    fun remove(c: Context, id: String) {
        all(c).firstOrNull { it.id == id }?.file(c)?.delete()
        save(c, all(c).filter { it.id != id })
    }

    /** Rotating pick: a different one each morning. Null when none are stored. */
    fun pickForToday(c: Context): Message? {
        val list = all(c)
        if (list.isEmpty()) return null
        val idx = prefs(c).getInt(KEY_NEXT, 0) % list.size
        prefs(c).edit().putInt(KEY_NEXT, idx + 1).apply()
        return list[idx]
    }

    fun peekNext(c: Context): Message? {
        val list = all(c)
        if (list.isEmpty()) return null
        return list[prefs(c).getInt(KEY_NEXT, 0) % list.size]
    }

    private fun save(c: Context, list: List<Message>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("name", it.name).put("from", it.from).put("dur", it.durationMs).put("video", it.isVideo))
        }
        prefs(c).edit().putString(KEY_LIST, arr.toString()).apply()
    }

    private fun duration(f: File): Long = try {
        MediaMetadataRetriever().run {
            setDataSource(f.absolutePath)
            val d = extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            release(); d ?: 0L
        }
    } catch (e: Exception) { 0L }

    private fun displayName(c: Context, uri: Uri): String? = try {
        c.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    } catch (e: Exception) { null }

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
