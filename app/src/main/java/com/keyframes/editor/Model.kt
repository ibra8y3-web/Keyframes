package com.keyframes.editor

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class Fx { NONE, GRAY, SEPIA, INVERT, WARM, COOL, CINE, VIVID, FADE, NOIR }

data class Clip(
    val id: Long, val uri: Uri, val durMs: Long,
    val start: Long = 0, val end: Long = durMs,
    val speed: Float = 1f, val volume: Float = 1f,
    val rot: Int = 0, val mirror: Boolean = false, val fx: Fx = Fx.NONE,
    val bright: Float = 0f, val contrast: Float = 0f, val sat: Float = 0f,
    val hue: Float = 0f, val temp: Float = 0f,
    val zoom: Float = 1f, val panX: Float = 0f, val panY: Float = 0f,
    val fadeIn: Long = 0, val fadeOut: Long = 0,
    val isImage: Boolean = false
) {
    val lenMs get() = end - start
    val outMs get() = (lenMs / speed).toLong()
}

data class TextItem(
    val id: Long, val text: String, val start: Long, val end: Long,
    val y: Float = -0.7f, val size: Int = 64, val color: Int = -1
)

data class Sticker(
    val id: Long, val uri: Uri, val start: Long, val end: Long,
    val x: Float = 0f, val y: Float = 0f, val scale: Float = 0.3f
)

data class Music(val uri: Uri, val volume: Float = 0.8f)

data class Project(
    val clips: List<Clip> = emptyList(),
    val texts: List<TextItem> = emptyList(),
    val music: Music? = null,
    val ratio: Float = 0f,
    val stickers: List<Sticker> = emptyList(),
    val voice: Music? = null
) {
    val totalMs get() = clips.sumOf { it.outMs }
}

object ProjectStore {
    private fun file(c: Context) = File(c.filesDir, "project.json")

    fun save(c: Context, p: Project) {
        runCatching {
            val o = JSONObject()
            o.put("ratio", p.ratio.toDouble())
            o.put("clips", JSONArray().apply {
                p.clips.forEach {
                    put(JSONObject().put("id", it.id).put("uri", it.uri.toString()).put("dur", it.durMs)
                        .put("s", it.start).put("e", it.end).put("sp", it.speed.toDouble())
                        .put("v", it.volume.toDouble()).put("r", it.rot).put("m", it.mirror)
                        .put("fx", it.fx.name).put("b", it.bright.toDouble())
                        .put("c", it.contrast.toDouble()).put("sa", it.sat.toDouble())
                        .put("h", it.hue.toDouble()).put("t", it.temp.toDouble()).put("z", it.zoom.toDouble())
                        .put("px", it.panX.toDouble()).put("py", it.panY.toDouble())
                        .put("fi", it.fadeIn).put("fo", it.fadeOut).put("img", it.isImage))
                }
            })
            o.put("texts", JSONArray().apply {
                p.texts.forEach {
                    put(JSONObject().put("id", it.id).put("t", it.text).put("s", it.start).put("e", it.end)
                        .put("y", it.y.toDouble()).put("z", it.size).put("c", it.color))
                }
            })
            o.put("stickers", JSONArray().apply {
                p.stickers.forEach {
                    put(JSONObject().put("id", it.id).put("uri", it.uri.toString()).put("s", it.start).put("e", it.end)
                        .put("x", it.x.toDouble()).put("y", it.y.toDouble()).put("sc", it.scale.toDouble()))
                }
            })
            p.music?.let { o.put("music", JSONObject().put("uri", it.uri.toString()).put("v", it.volume.toDouble())) }
            p.voice?.let { o.put("voice", JSONObject().put("uri", it.uri.toString()).put("v", it.volume.toDouble())) }
            file(c).writeText(o.toString())
        }
    }

    fun load(c: Context): Project = runCatching {
        val o = JSONObject(file(c).readText())
        val cl = o.getJSONArray("clips")
        val clips = (0 until cl.length()).map {
            val j = cl.getJSONObject(it)
            Clip(
                j.getLong("id"), Uri.parse(j.getString("uri")), j.getLong("dur"), j.getLong("s"), j.getLong("e"),
                j.getDouble("sp").toFloat(), j.getDouble("v").toFloat(), j.getInt("r"), j.getBoolean("m"),
                Fx.valueOf(j.getString("fx")), j.getDouble("b").toFloat(), j.getDouble("c").toFloat(),
                j.getDouble("sa").toFloat(), j.optDouble("h", 0.0).toFloat(), j.optDouble("t", 0.0).toFloat(),
                j.optDouble("z", 1.0).toFloat(), j.optDouble("px", 0.0).toFloat(), j.optDouble("py", 0.0).toFloat(),
                j.optLong("fi", 0), j.optLong("fo", 0), j.optBoolean("img", false)
            )
        }
        val tl = o.getJSONArray("texts")
        val texts = (0 until tl.length()).map {
            val j = tl.getJSONObject(it)
            TextItem(j.getLong("id"), j.getString("t"), j.getLong("s"), j.getLong("e"),
                j.getDouble("y").toFloat(), j.getInt("z"), j.getInt("c"))
        }
        val music = o.optJSONObject("music")?.let { Music(Uri.parse(it.getString("uri")), it.getDouble("v").toFloat()) }
        val voice = o.optJSONObject("voice")?.let { Music(Uri.parse(it.getString("uri")), it.getDouble("v").toFloat()) }
        val sl = o.optJSONArray("stickers")
        val stickers = if (sl == null) emptyList() else (0 until sl.length()).map {
            val j = sl.getJSONObject(it)
            Sticker(j.getLong("id"), Uri.parse(j.getString("uri")), j.getLong("s"), j.getLong("e"),
                j.getDouble("x").toFloat(), j.getDouble("y").toFloat(), j.getDouble("sc").toFloat())
        }
        Project(clips, texts, music, o.getDouble("ratio").toFloat(), stickers, voice)
    }.getOrDefault(Project())
}
