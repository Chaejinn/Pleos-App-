package com.pleos.explorer.data.local

import android.content.Context
import com.pleos.explorer.domain.port.legacy.DexStore
import org.json.JSONObject
import java.io.File

/** 도감 수집 기록. filesDir/dex.json */
class FileDexStore(context: Context) : DexStore {
    private val file = File(context.filesDir, "dex.json")
    private val map: MutableMap<String, Long> = load()

    private fun load(): MutableMap<String, Long> {
        if (!file.exists()) return mutableMapOf()
        val o = JSONObject(file.readText())
        return o.keys().asSequence().associateWith { o.getLong(it) }.toMutableMap()
    }

    private fun save() = file.writeText(JSONObject(map as Map<*, *>).toString())

    override fun isCollected(id: String) = id in map
    override fun markCollected(id: String, atMs: Long) {
        if (map.putIfAbsent(id, atMs) == null) save()
    }
    override fun collectedIds(): Set<String> = map.keys
}
