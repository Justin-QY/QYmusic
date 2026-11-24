package com.qymusic.app.utils

import com.qymusic.app.config.SPConfig
import com.qymusic.app.ui.activity.PlatformLoginActivity
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * 启动页后台预取云端页数据。
 *
 * 云端页首次进入会选中「第一个已登录的启用平台」，这里提前拉取该平台的
 * 我喜欢的音乐 / 歌单 / 歌手和每日推荐。请求统一走 [CloudRepository]，
 * 和云端页并发时只会发一次真实请求，结果写入 5 分钟缓存。
 *
 * 任何一个请求失败都静默忽略——云端页自己还会再请求一次兜底。
 */
object CloudPrefetch {

    /** 与云端页首次进入时的平台选择规则保持一致 */
    fun activePlatform(): String? =
        SPConfig.enabledCloudPlatforms().firstOrNull { isLogged(it) }

    private fun isLogged(p: String): Boolean = when (p) {
        PlatformLoginActivity.PLATFORM_QQ -> SPConfig.isQqLoggedIn()
        PlatformLoginActivity.PLATFORM_KUGOU -> SPConfig.isKugouLoggedIn()
        else -> SPConfig.isNeteaseLoggedIn()
    }

    suspend fun prefetch() = coroutineScope {
        val platform = activePlatform()
        if (platform == null) {
            return@coroutineScope
        }
        val isNetease = platform == PlatformLoginActivity.PLATFORM_NETEASE
        launch { runCatching { CloudRepository.liked(platform) } }
        launch { runCatching { CloudRepository.playlists(platform) } }
        if (isNetease) {
            launch { runCatching { CloudRepository.artists(platform) } }
        }
        launch { runCatching { CloudRepository.daily() } }
    }
}
