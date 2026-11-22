package com.usharik.routermanager

import org.json.JSONArray
import org.json.JSONObject

/**
 * Устройства, которые роутер уже забыл: прошивка перечисляет только недавно активные. История одного роутера хранится
 * в памяти и сохраняется строкой JSON через [save] (на диск — из фонового потока вызывающего).
 */
internal class DeviceHistory(raw: String?, private val limit: Int = 200) {
    private val known = linkedMapOf<String, JSONObject>()

    init {
        val arr = raw?.let { runCatching { JSONArray(it) }.getOrNull() }
        if (arr != null) for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { o -> o.optString("mac").takeIf { it.isNotEmpty() }?.let { known[it] = o } }
    }

    /** Запоминает живые устройства; возвращает JSON для сохранения или null, если запоминать нечего. */
    fun record(devices: List<RouterDevice>, now: Long): String? {
        val withMac = devices.filter { it.mac.isNotEmpty() }
        if (withMac.isEmpty()) return null
        for (d in withMac) {
            known[d.mac] = JSONObject().put("mac", d.mac).put("name", d.name).put("type", d.type.name).put("connection", d.connection)
                .put("lastSeen", mergedLastSeen(d.online, known[d.mac]?.optLong("lastSeen") ?: 0, now))
        }
        if (known.size > limit) {
            val keep = known.values.sortedByDescending { it.optLong("lastSeen") }.take(limit).map { it.optString("mac") }.toSet()
            known.keys.retainAll(keep)
        }
        return serialize()
    }

    fun forget(mac: String): String? = if (known.remove(mac) != null) serialize() else null

    /** Живые устройства из роутера плюс известные ранее, которых сейчас нет (как офлайн). */
    fun merge(live: List<RouterDevice>): List<RouterDevice> {
        // Роутер ещё перечисляет недавно отключившиеся устройства; время берём из истории.
        val current = live.map { d -> if (d.online) d else d.copy(lastSeen = known[d.mac]?.optLong("lastSeen") ?: 0) }
        val liveMacs = current.map { it.mac }.toSet()
        val offline = known.values.filter { it.optString("mac") !in liveMacs }
            .map { offlineDevice(it.optString("mac"), it.optString("name"), DeviceType.parse(it.optString("type")), it.optString("connection"), it.optLong("lastSeen")) }
            .sortedBy { it.name.lowercase() }
        return current + offline
    }

    private fun serialize() = JSONArray(known.values.toList()).toString()
}
