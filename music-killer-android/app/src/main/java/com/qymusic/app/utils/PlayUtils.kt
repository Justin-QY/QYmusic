package com.qymusic.app.utils

import android.os.Bundle
import androidx.core.os.bundleOf
import androidx.core.os.persistableBundleOf
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.Player
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