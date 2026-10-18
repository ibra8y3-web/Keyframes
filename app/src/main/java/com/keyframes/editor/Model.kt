package com.keyframes.editor

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class Fx { NONE, GRAY, SEPIA, INVERT, WARM, COOL, CINE, VIVID, FADE, NOIR, MIDNIGHT, VINTAGE, GOLDEN }

enum class Anim { NONE, SLIDE_LEFT, SLIDE_RIGHT, SLIDE_UP, SLIDE_DOWN, ZOOM, POP }

enum class Loop { NONE, KEN_IN, KEN_OUT, PULSE, SHAKE }

data class Keyframe(val tMs: Long, val zoom: Float, val panX: Float, val panY: Float)

data class Clip(
    val id: Long, val uri: Uri, val durMs: Long,
    val start: Long = 0, val end: Long = durMs,
    val speed: Float = 1f, val volume: Float = 1f,
    val rot: Int = 0, val mirror: Boolean = false, val fx: Fx = Fx.NONE,
    val bright: Float = 0f, val contrast: Float = 0f, val sat: Float = 0f,
    val hue: Float = 0f, val temp: Float = 0f,
    val zoom: Float = 1f, val panX: Float = 0f, val panY: Float = 0f,
    val fadeIn: Long = 0, val fadeOut: Long = 0,
    val isImage: Boolean = false,
    val pitch: Float = 1f, val tilt: Float = 0f, val flipV: Boolean = false,
    val animIn: Anim = Anim.NONE, val animOut: Anim = Anim.NONE, val loop: Loop = Loop.NONE, val animMs: Long = 500,
    val keys: List<Keyframe> = emptyList()
) {
    val lenMs get() = end - start
    val outMs get() = (lenMs / speed).toLong()
}

data class TextItem(
    val id: Long, val text: String, val start: Long, val end: Long,
    val y: Float = -0.7f, val size: Int = 64, val color: Int = -1,
    val bold: Boolean = false, val bg: Boolean = false, val font: Int = 0, val anim: Int = 0
)

data class Sticker(
    val id: Long, val uri: Uri, val start: Long, val end: Long,
    val x: Float = 0f, val y: Float = 0f, val scale: Float = 0.3f, val alpha: Float = 1f, val anim: Int = 0
)

/** kind: 0 music, 1 voice recording, 2 sound effect, 3 text to speech, 4 audio extracted from a clip. */
data class AudioTrack(
    val id: Long, val uri: Uri, val label: String, val kind: Int,
    val startMs: Long, val srcStart: Long, val srcEnd: Long, val durMs: Long, val volume: Float = 1f
) {
    val lenMs get() = srcEnd - srcStart
}

data class Project(
    val clips: List<Clip> = emptyList(),
    val texts: List<TextItem> = emptyList(),
    val audio: List<AudioTrack> = emptyList(),
    val ratio: Float = 0f,
    val stickers: List<Sticker> = emptyList(),
    val fit: Boolean = false
) {
    val totalMs get() = clips.sumOf { it.outMs }
}

object Srt {
    private val TIME = Regex("(\\d+):(\\d+):(\\d+)[,.](\\d+)\\s*-->\\s*(\\d+):(\\d+):(\\d+)[,.](\\d+)")

    private fun ms(h: String, mi: String, s: String, f: String) =
        ((h.toLong() * 60 + mi.toLong()) * 60 + s.toLong()) * 1000 + f.padEnd(3, '0').take(3).toLong()

    fun parse(text: String): List<Triple<Long, Long, String>> {
        val out = mutableListOf<Triple<Long, Long, String>>()
        text.replace("\r", "").replace("\uFEFF", "").split(Regex("\n\\s*\n")).forEach { block ->
            val lines = block.trim().lines()
            val ti = lines.indexOfFirst { TIME.containsMatchIn(it) }
            if (ti < 0) return@forEach
            val m = TIME.find(lines[ti])!!.groupValues
            val s = ms(m[1], m[2], m[3], m[4]); val e = ms(m[5], m[6], m[7], m[8])
            val t = lines.drop(ti + 1).joinToString("\n").replace(Regex("<[^>]+>"), "").trim()
            if (t.isNotEmpty() && e > s) out += Triple(s, e, t)
        }
        return out
    }
}

object ProjectStore {
    private fun dir(c: Context) = File(c.filesDir, "drafts").apply { mkdirs() }
    private fun file(c: Context, id: Long) = File(dir(c), "$id.json")
    private fun cur(c: Context) = File(c.filesDir, "current.txt")

    fun currentId(c: Context): Long {
        val id = runCatching { cur(c).readText().trim().toLong() }.getOrNull()
        if (id != null) return id
        return System.currentTimeMillis().also { setCurrent(c, it) }
    }

    fun setCurrent(c: Context, id: Long) { runCatching { cur(c).writeText(id.toString()) } }

    fun ids(c: Context): List<Long> =
        dir(c).listFiles()?.mapNotNull { it.nameWithoutExtension.toLongOrNull() }?.sortedDescending() ?: emptyList()

    fun delete(c: Context, id: Long) { runCatching { file(c, id).delete() } }

    fun save(c: Context, id: Long, p: Project) {
        runCatching {
            val o = JSONObject()
            o.put("ratio", p.ratio.toDouble()); o.put("fit", p.fit)
            o.put("clips", JSONArray().apply {
                p.clips.forEach { k ->
                    put(JSONObject().apply {
                        put("id", k.id); put("uri", k.uri.toString()); put("dur", k.durMs)
                        put("s", k.start); put("e", k.end); put("sp", k.speed.toDouble())
                        put("v", k.volume.toDouble()); put("r", k.rot); put("m", k.mirror)
                        put("fx", k.fx.name); put("b", k.bright.toDouble()); put("c", k.contrast.toDouble())
                        put("sa", k.sat.toDouble()); put("h", k.hue.toDouble()); put("t", k.temp.toDouble())
                        put("z", k.zoom.toDouble()); put("px", k.panX.toDouble()); put("py", k.panY.toDouble())
                        put("fi", k.fadeIn); put("fo", k.fadeOut); put("img", k.isImage)
                        put("pt", k.pitch.toDouble()); put("tl", k.tilt.toDouble()); put("fv", k.flipV)
                        put("ai", k.animIn.name); put("ao", k.animOut.name); put("lp", k.loop.name); put("am", k.animMs)
                        put("keys", JSONArray().apply {
                            k.keys.forEach { kf ->
                                put(JSONObject().put("t", kf.tMs).put("z", kf.zoom.toDouble()).put("x", kf.panX.toDouble()).put("y", kf.panY.toDouble()))
                            }
                        })
                    })
                }
            })
            o.put("texts", JSONArray().apply {
                p.texts.forEach { t ->
                    put(JSONObject().apply {
                        put("id", t.id); put("t", t.text); put("s", t.start); put("e", t.end)
                        put("y", t.y.toDouble()); put("z", t.size); put("c", t.color)
                        put("b", t.bold); put("bg", t.bg); put("f", t.font); put("an", t.anim)
                    })
                }
            })
            o.put("stickers", JSONArray().apply {
                p.stickers.forEach { s ->
                    put(JSONObject().apply {
                        put("id", s.id); put("uri", s.uri.toString()); put("s", s.start); put("e", s.end)
                        put("x", s.x.toDouble()); put("y", s.y.toDouble()); put("sc", s.scale.toDouble()); put("al", s.alpha.toDouble()); put("an", s.anim)
                    })
                }
            })
            o.put("audio", JSONArray().apply {
                p.audio.forEach { a ->
                    put(JSONObject().apply {
                        put("id", a.id); put("uri", a.uri.toString()); put("l", a.label); put("k", a.kind)
                        put("st", a.startMs); put("ss", a.srcStart); put("se", a.srcEnd); put("d", a.durMs); put("v", a.volume.toDouble())
                    })
                }
            })
            file(c, id).writeText(o.toString())
        }
    }

    fun load(c: Context, id: Long): Project = runCatching {
        val o = JSONObject(file(c, id).readText())
        val cl = o.getJSONArray("clips")
        val clips = (0 until cl.length()).map {
            val j = cl.getJSONObject(it)
            val ka = j.optJSONArray("keys")
            val keys = if (ka == null) emptyList() else (0 until ka.length()).map { n ->
                val q = ka.getJSONObject(n)
                Keyframe(q.getLong("t"), q.getDouble("z").toFloat(), q.getDouble("x").toFloat(), q.getDouble("y").toFloat())
            }
            Clip(
                j.getLong("id"), Uri.parse(j.getString("uri")), j.getLong("dur"), j.getLong("s"), j.getLong("e"),
                j.getDouble("sp").toFloat(), j.getDouble("v").toFloat(), j.getInt("r"), j.getBoolean("m"),
                Fx.valueOf(j.getString("fx")), j.getDouble("b").toFloat(), j.getDouble("c").toFloat(),
                j.getDouble("sa").toFloat(), j.optDouble("h", 0.0).toFloat(), j.optDouble("t", 0.0).toFloat(),
                j.optDouble("z", 1.0).toFloat(), j.optDouble("px", 0.0).toFloat(), j.optDouble("py", 0.0).toFloat(),
                j.optLong("fi", 0), j.optLong("fo", 0), j.optBoolean("img", false),
                j.optDouble("pt", 1.0).toFloat(), j.optDouble("tl", 0.0).toFloat(), j.optBoolean("fv", false),
                runCatching { Anim.valueOf(j.optString("ai", "NONE")) }.getOrDefault(Anim.NONE),
                runCatching { Anim.valueOf(j.optString("ao", "NONE")) }.getOrDefault(Anim.NONE),
                runCatching { Loop.valueOf(j.optString("lp", "NONE")) }.getOrDefault(Loop.NONE),
                j.optLong("am", 500), keys
            )
        }
        val tl = o.optJSONArray("texts")
        val texts = if (tl == null) emptyList() else (0 until tl.length()).map {
            val j = tl.getJSONObject(it)
            TextItem(j.getLong("id"), j.getString("t"), j.getLong("s"), j.getLong("e"),
                j.getDouble("y").toFloat(), j.getInt("z"), j.getInt("c"),
                j.optBoolean("b", false), j.optBoolean("bg", false), j.optInt("f", 0), j.optInt("an", 0))
        }
        val sl = o.optJSONArray("stickers")
        val stickers = if (sl == null) emptyList() else (0 until sl.length()).map {
            val j = sl.getJSONObject(it)
            Sticker(j.getLong("id"), Uri.parse(j.getString("uri")), j.getLong("s"), j.getLong("e"),
                j.getDouble("x").toFloat(), j.getDouble("y").toFloat(), j.getDouble("sc").toFloat(), j.optDouble("al", 1.0).toFloat(), j.optInt("an", 0))
        }
        val al = o.optJSONArray("audio")
        val audio = if (al == null) emptyList() else (0 until al.length()).map {
            val j = al.getJSONObject(it)
            AudioTrack(j.getLong("id"), Uri.parse(j.getString("uri")), j.getString("l"), j.getInt("k"),
                j.getLong("st"), j.getLong("ss"), j.getLong("se"), j.getLong("d"), j.getDouble("v").toFloat())
        }
        Project(clips, texts, audio, o.optDouble("ratio", 0.0).toFloat(), stickers, o.optBoolean("fit", false))
    }.getOrDefault(Project())
}
