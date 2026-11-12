package com.pleos.explorer.domain.port.legacy

/** 수집 기록 저장소. app 모듈에서 파일/DB로 구현한다. */
interface DexStore {
    fun isCollected(id: String): Boolean
    fun markCollected(id: String, atMs: Long)
    fun collectedIds(): Set<String>
}

class InMemoryDexStore : DexStore {
    private val map = mutableMapOf<String, Long>()
    override fun isCollected(id: String) = id in map
    override fun markCollected(id: String, atMs: Long) { map.putIfAbsent(id, atMs) }
    override fun collectedIds(): Set<String> = map.keys
}
