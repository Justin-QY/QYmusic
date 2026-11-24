package com.qymusic.app.utils

import android.os.SystemClock
import com.qymusic.app.QYMusicApplication
import com.qymusic.app.model.ArtistModel
import com.qymusic.app.model.MusicModel
import com.qymusic.app.model.PlayListModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 云端数据缓存。
 *
 * - 内存缓存有效期 5 分钟：有效期内不再请求网络；
 * - 同时落盘保存最近一次结果：服务端国内访问较慢，冷启动直接把上次数据
 *   铺出来，再由 [CloudRepository] 在后台判断是否需要刷新；
 * - 读取磁盘只在启动页的 IO 线程做一次（[warmFromDisk]），页面访问始终走内存。
 */
object CloudDataCache {

    private const val TTL_MS = 5 * 60 * 1000L

    /** filesDir 下的缓存目录 */
    private const val CACHE_DIR = "cloud_cache"

    private data class Entry(val data: List<Any>, val at: Long)

    private val store = mutableMapOf<String, Entry>()
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun likedKey(platform: String) = "liked:$platform"
    fun playlistsKey(platform: String) = "playlists:$platform"
    fun artistsKey(platform: String) = "artists:$platform"
    const val DAILY_KEY = "daily"

    fun put(key: String, data: List<*>) {
        val filtered = data.filterNotNull()
        if (filtered.isEmpty()) {
            return
        }
        synchronized(store) {
            store[key] = Entry(filtered, SystemClock.elapsedRealtime())
        }
        persist(key, filtered)
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
        val keys = listOf(likedKey(platform), playlistsKey(platform), artistsKey(platform))
        synchronized(store) {
            keys.forEach { store.remove(it) }
        }
        deleteFiles(keys)
    }

    fun clearAll() {
        synchronized(store) { store.clear() }
        ioScope.launch {
            runCatching { cacheDir()?.deleteRecursively() }
        }
    }

    /**
     * 启动时把磁盘缓存读进内存（IO 线程调用）。
     * 时间戳按文件最后修改时间换算，过期数据依然保留展示，由调用方决定是否刷新。
     */
    fun warmFromDisk() {
        val files = runCatching { cacheDir()?.listFiles() }.getOrNull() ?: return
        val now = System.currentTimeMillis()
        files.forEach { file ->
            if (!file.isFile || !file.name.endsWith(".json")) {
                return@forEach
            }
            val key = file.nameWithoutExtension.replace('_', ':')
            val data: List<Any>? = when {
                key.startsWith("liked:") -> decode(file, ListSerializer(MusicModel.serializer()))
                key.startsWith("artists:") -> decode(file, ListSerializer(ArtistModel.serializer()))
                key.startsWith("playlists:") || key == DAILY_KEY ->
                    decode(file, ListSerializer(PlayListModel.serializer()))
                else -> null
            }
            if (data.isNullOrEmpty()) {
                return@forEach
            }
            val age = (now - file.lastModified()).coerceAtLeast(0L)
            synchronized(store) {
                store[key] = Entry(data, SystemClock.elapsedRealtime() - age)
            }
        }
    }

    // ------------------------------------------------------------------ 磁盘读写

    private fun cacheDir(): File? = runCatching {
        File(QYMusicApplication.context.filesDir, CACHE_DIR)
    }.getOrNull()

    private fun fileName(key: String): String = key.replace(':', '_') + ".json"

    private fun persist(key: String, data: List<Any>) {
        val payload = when {
            key.startsWith("liked:") -> runCatching {
                json.encodeToString(
                    ListSerializer(MusicModel.serializer()),
                    data.filterIsInstance<MusicModel>()
                )
            }.getOrNull()
            key.startsWith("artists:") -> runCatching {
                json.encodeToString(
                    ListSerializer(ArtistModel.serializer()),
                    data.filterIsInstance<ArtistModel>()
                )
            }.getOrNull()
            key.startsWith("playlists:") || key == DAILY_KEY -> runCatching {
                json.encodeToString(
                    ListSerializer(PlayListModel.serializer()),
                    data.filterIsInstance<PlayListModel>()
                )
            }.getOrNull()
            else -> null
        } ?: return

        ioScope.launch {
            runCatching {
                val dir = cacheDir() ?: return@runCatching
                if (!dir.exists()) {
                    dir.mkdirs()
                }
                File(dir, fileName(key)).writeText(payload)
            }
        }
    }

    private fun deleteFiles(keys: List<String>) {
        ioScope.launch {
            runCatching {
                val dir = cacheDir() ?: return@runCatching
                keys.forEach { File(dir, fileName(it)).delete() }
            }
        }
    }

    private fun <T> decode(file: File, serializer: KSerializer<List<T>>): List<T>? =
        runCatching { json.decodeFromString(serializer, file.readText()) }.getOrNull()
}
