package com.qymusic.app.utils

import android.net.Uri
import android.os.Bundle
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import com.qymusic.app.config.SPConfig
import com.qymusic.app.enums.MusicBridge
import com.qymusic.app.model.MusicModel
import java.lang.reflect.Field
import java.util.Locale

fun Player.playOrPause() {
  if (isPlaying) {
    pause()
  } else {
    startPlayback()
  }
}

/**
 * 开始播放当前媒体。
 *
 * - 播放器处于 IDLE（刚 stop 过、首次加载）时先 prepare()，否则 play() 不会生效；
 * - 暂停 / 缓冲中只需要把 playWhenReady 置回 true。
 *
 * 之前多个入口直接用 `Util.handlePlayButtonAction(player)`，它按 playWhenReady 判断，
 * 缓冲阶段（isPlaying=false 但 playWhenReady=true）会被当成「正在播放」而忽略命令，
 * 表现为第一下点了没反应，要再点一次。
 */
fun Player.startPlayback() {
  if (playbackState == Player.STATE_IDLE) {
    prepare()
  }
  play()
}

// 转换为可读格式
fun Long.formatDuration(): String {
  val seconds = (this / 1000) % 60
  val minutes = (this / 1000 / 60) % 60
  val hours = this / 1000 / 3600

  return if (hours > 0) {
    String.format(Locale.CHINA, "%d:%02d:%02d", hours, minutes, seconds)
  } else {
    String.format(Locale.CHINA, "%02d:%02d", minutes, seconds)
  }
}

/**
 * 反射字段只解析一次。
 *
 * [toBundle] 在创建每个播放项时都会调用，整单播放 300 首就是 300 次字段扫描；
 * 缓存 Field 数组后只剩取值本身的开销。
 */
private val musicModelFields: Array<out Field> by lazy {
  MusicModel::class.java.declaredFields.onEach { it.isAccessible = true }
}

fun MusicModel.toBundle(): Bundle {
  val bundle = Bundle()
  musicModelFields.forEach {
    when (val value = it.get(this)) {
      is String -> bundle.putString(it.name, value)
      is Int -> bundle.putInt(it.name, value)
      is Long -> bundle.putLong(it.name, value)
      is Boolean -> bundle.putBoolean(it.name, value)
      is Float -> bundle.putFloat(it.name, value)
    }
  }
  return bundle
}

@Suppress("Deprecation")
fun MediaMetadata.getMusicInfo(): MusicModel? {
  val extras = extras ?: return null
  val musicModel = MusicModel()
  musicModelFields.forEach {
    it.set(musicModel, extras.get(it.name))
  }
  return musicModel
}

/**
 * 构建播放地址。
 *
 * 真实 CDN 地址由 [com.qymusic.app.service.MusicService] 的 ResolvingDataSource 解析，
 * 这里只负责携带 id / 音质 / 平台等解析参数；本地歌曲直接用 file:// 地址。
 *
 * @param bridge 当前音质；无损不可用时自动回落到 320k
 */
fun MusicModel.playUri(bridge: String = SPConfig.currentBridge()): Uri {
  if (localPath.isNotEmpty()) {
    return "file://$localPath".toUri()
  }
  val actualBridge = if (bridge == MusicBridge.FLAC_2000K.level && !hasLossless) {
    MusicBridge.MP3_320K.level
  } else {
    bridge
  }
  return Uri.Builder()
    .scheme("http")
    .authority("localhost")
    .appendQueryParameter("id", id.toString())
    .appendQueryParameter("bridge", actualBridge)
    .appendQueryParameter("source", source)
    .appendQueryParameter("sourceId", sourceId)
    .apply {
      if (source == "qq") {
        appendQueryParameter("qqCookie", SPConfig.qqCookie())
      }
      if (source == "kg") {
        appendQueryParameter("kgCookie", SPConfig.kugouCookie())
      }
    }
    .appendQueryParameter("name", showName)
    .appendQueryParameter("singer", artist)
    .appendQueryParameter("album", album)
    .appendQueryParameter("duration", duration.toString())
    .build()
}

/**
 * 构建播放项，播放队列、通知栏、以及进程被杀后的恢复都共用这一份元数据。
 */
/**
 * 媒体缓存 key。
 *
 * - 带音质：同一首歌切到不同音质时不要复用旧缓存；
 * - v4：旧版（v3 及更早）可能缓存过官方源返回的试听片段。那些短文件被当成完整音频后，
 *   ExoPlayer 播到片段末尾就会自动切下一首，表现为「歌没放完就跳了」。升级 key 后不再命中旧缓存。
 */
fun mediaCacheKey(source: String, id: String, bridge: String): String =
  "$source-$id-${bridge.ifEmpty { "default" }}-v4"

fun MusicModel.toMediaItem(bridge: String = SPConfig.currentBridge()): MediaItem {
  val pic = pic120.ifEmpty { pic }
  return MediaItem.Builder()
    .setUri(playUri(bridge))
    .setMediaId(id.toString())
    .setMediaMetadata(
      MediaMetadata.Builder()
        // 通知栏 / 锁屏 / 车机都读这里的标题和歌手
        .setTitle(name)
        .setArtist(artist)
        .setAlbumTitle(album)
        .setArtworkUri(pic.toUri())
        .setIsPlayable(true)
        .setExtras(toBundle())
        .build()
    )
    .build()
}