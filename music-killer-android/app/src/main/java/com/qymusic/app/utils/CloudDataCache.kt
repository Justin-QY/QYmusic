package com.qymusic.app.utils

import android.os.SystemClock

/**
 * 云端数据进程内缓存。
 *
 * 目的：进入云端页 / 点「查看全部」时先展示缓存，避免每次重复拉取
 * 「我喜欢的音乐」等大数据列表导致卡顿。
 *
 * - 缓存有效期 5 分钟；
 * - 有效期内直接使用，不再请求网络；
 * - 过期时先展示旧数据，再后台刷新（调用方自行决定）。
 */
object CloudDataCache {

    private const val TTL_MS = 5 * 60 * 1000L

    private data class Entry(val data: List<Any>, val at: Long)

    private val store = mutableMapOf<String, Entry>()

    fun likedKey(platform: String) = "liked:$platform"
    fun playlistsKey(platform: String) = "playlists:$platform"
    fun artistsKey(platform: String) = "artists:$platform"
    const val DAILY_KEY = "daily"

    fun put(key: String, data: List<*>) {
        synchronized(store) {
            store[key] = Entry(data.filterNotNull(), SystemClock.elapsedRealtime())
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> get(key: String): List<T>? = synchronized(store) {
        store[key]?.data as? List<T>
    }

    fun isFresh(key: String): Boolean = synchronized(store) {
        val entry = store[key] ?: return false
        SystemClock.elapsedRealtime() - entry.at < TTL_MS
    }

    fun clearPlatform(platform: String) {
        synchronized(store) {
            store.remove(likedKey(platform))
            store.remove(playlistsKey(platform))
            store.remove(artistsKey(platform))
        }
    }

    fun clearAll() {
        synchronized(store) { store.clear() }
    }
}
