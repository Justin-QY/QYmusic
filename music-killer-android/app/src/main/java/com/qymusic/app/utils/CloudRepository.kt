package com.qymusic.app.utils

import com.drake.net.Get
import com.qymusic.app.config.Api
import com.qymusic.app.config.SPConfig
import com.qymusic.app.model.ArtistModel
import com.qymusic.app.model.MusicModel
import com.qymusic.app.model.Page
import com.qymusic.app.model.PlayListModel
import com.qymusic.app.ui.activity.PlatformLoginActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/**
 * 云端数据统一入口。
 *
 * 云端页预览、「查看全部」、启动页预取都走这里，保证：
 * 1. 5 分钟缓存有效期内不重复请求；
 * 2. 同一份数据正在请求时，其它调用方复用同一个 Deferred，不再发第二遍；
 * 3. 列表始终按「完整数据」拉取并写入缓存，预览页只取前几条展示，
 *    避免预览页用 30 条结果覆盖「查看全部」的完整缓存。
 */
object CloudRepository {

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val inFlight = mutableMapOf<String, Deferred<*>>()

    // ------------------------------------------------------------------ 对外

    suspend fun liked(platform: String): List<MusicModel> {
        val key = CloudDataCache.likedKey(platform)
        if (CloudDataCache.isFresh(key)) {
            CloudDataCache.get<MusicModel>(key)?.let { return it }
        }
        return shared(key) { fetchLiked(platform) }.await()
    }

    suspend fun playlists(platform: String): List<PlayListModel> {
        val key = CloudDataCache.playlistsKey(platform)
        if (CloudDataCache.isFresh(key)) {
            CloudDataCache.get<PlayListModel>(key)?.let { return it }
        }
        return shared(key) { fetchPlaylists(platform) }.await()
    }

    suspend fun artists(platform: String): List<ArtistModel> {
        val key = CloudDataCache.artistsKey(platform)
        if (CloudDataCache.isFresh(key)) {
            CloudDataCache.get<ArtistModel>(key)?.let { return it }
        }
        return shared(key) { fetchArtists(platform) }.await()
    }

    suspend fun daily(): List<PlayListModel> {
        val key = CloudDataCache.DAILY_KEY
        if (CloudDataCache.isFresh(key)) {
            CloudDataCache.get<PlayListModel>(key)?.let { return it }
        }
        return shared(key) { fetchDaily() }.await()
    }

    /** 退出登录 / 换账号：清缓存并取消还在跑的请求，避免旧账号数据回填 */
    fun clearPlatform(platform: String) {
        CloudDataCache.clearPlatform(platform)
        val keys = listOf(
            CloudDataCache.likedKey(platform),
            CloudDataCache.playlistsKey(platform),
            CloudDataCache.artistsKey(platform),
        )
        synchronized(lock) {
            keys.forEach { key -> inFlight.remove(key)?.cancel() }
        }
    }

    // ------------------------------------------------------------------ 合并并发请求

    @Suppress("UNCHECKED_CAST")
    private fun <T> shared(
        key: String,
        request: suspend CoroutineScope.() -> List<T>,
    ): Deferred<List<T>> =
        synchronized(lock) {
            inFlight[key]?.let { return@synchronized it as Deferred<List<T>> }
            val deferred = ioScope.async {
                val result = request()
                // 空列表不写缓存，下次还能重新拉
                if (result.isNotEmpty()) {
                    CloudDataCache.put(key, result)
                }
                result
            }
            inFlight[key] = deferred
            deferred.invokeOnCompletion {
                synchronized(lock) {
                    if (inFlight[key] === deferred) {
                        inFlight.remove(key)
                    }
                }
            }
            deferred
        }

    // ------------------------------------------------------------------ 请求

    private suspend fun CoroutineScope.fetchLiked(platform: String): List<MusicModel> {
        val isNetease = platform == PlatformLoginActivity.PLATFORM_NETEASE
        val url = when (platform) {
            PlatformLoginActivity.PLATFORM_QQ -> Api.QQ_LIKED
            PlatformLoginActivity.PLATFORM_KUGOU -> Api.KUGOU_LIKED
            else -> Api.NETEASE_LIKED
        }
        return Get<List<MusicModel>>(url) {
            addQuery("cookie", cookieOf(platform))
            if (isNetease) {
                addQuery("uid", uidOf(platform))
                addQuery("pageNo", 1)
                addQuery("pageSize", FULL_LIMIT)
            } else {
                addQuery("limit", FULL_LIMIT)
            }
        }.await()
    }

    private suspend fun CoroutineScope.fetchPlaylists(platform: String): List<PlayListModel> {
        val isNetease = platform == PlatformLoginActivity.PLATFORM_NETEASE
        val url = when (platform) {
            PlatformLoginActivity.PLATFORM_QQ -> Api.QQ_PLAYLISTS
            PlatformLoginActivity.PLATFORM_KUGOU -> Api.KUGOU_PLAYLISTS
            else -> Api.NETEASE_PLAYLISTS
        }
        return Get<List<PlayListModel>>(url) {
            addQuery("cookie", cookieOf(platform))
            if (isNetease) {
                addQuery("uid", uidOf(platform))
            }
        }.await()
    }

    private suspend fun CoroutineScope.fetchArtists(platform: String): List<ArtistModel> =
        Get<List<ArtistModel>>(Api.NETEASE_ARTISTS) {
            addQuery("cookie", cookieOf(platform))
        }.await()

    private suspend fun CoroutineScope.fetchDaily(): List<PlayListModel> =
        Get<Page<PlayListModel>>(Api.catalog(Api.PLAYLIST_RECOMMEND)) {
            addQuery("id", "rcm")
        }.await().data

    private fun cookieOf(p: String): String = when (p) {
        PlatformLoginActivity.PLATFORM_QQ -> SPConfig.qqCookie()
        PlatformLoginActivity.PLATFORM_KUGOU -> SPConfig.kugouCookie()
        else -> SPConfig.neteaseCookie()
    }

    private fun uidOf(p: String): Long = when (p) {
        PlatformLoginActivity.PLATFORM_QQ -> SPConfig.qqUin().toLongOrNull() ?: 0L
        PlatformLoginActivity.PLATFORM_KUGOU -> SPConfig.kugouUid()
        else -> SPConfig.neteaseUid()
    }

    /** 与「查看全部」一致的上限，预览页也从这份缓存里截取 */
    private const val FULL_LIMIT = 300
}
