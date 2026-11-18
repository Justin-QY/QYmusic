package com.qymusic.app.utils

import android.content.Context
import com.bumptech.glide.Glide
import com.qymusic.app.QYMusicApplication
import java.io.File

/**
 * 应用存储占用统计与清理。
 *
 * 统计口径：
 * - 音频缓存：media3 SimpleCache 使用的 cacheDir/media_cache（见 QYMusicApplication）
 * - 图片缓存：Glide 默认磁盘缓存目录（cacheDir/image_manager_disk_cache）
 * - 下载文件：DownloadHelper 的下载目录（含 lyric 歌词子目录）
 * - 临时/日志：cacheDir 中除上面两个缓存目录以外的全部内容（接口缓存、升级包、临时文件等）
 */
object AppStorage {

  /** 与 QYMusicApplication 中 SimpleCache 的目录保持一致 */
  const val AUDIO_CACHE_DIR = "media_cache"

  /** Glide 默认磁盘缓存目录名（Glide 未初始化成功时兜底用） */
  const val IMAGE_CACHE_DIR = "image_manager_disk_cache"

  data class Usage(val audio: Long, val image: Long, val download: Long, val temp: Long) {
    val total: Long get() = audio + image + download + temp
  }

  fun audioCacheDir(context: Context): File = File(context.cacheDir, AUDIO_CACHE_DIR)

  fun imageCacheDir(context: Context): File =
    runCatching { Glide.getPhotoCacheDir(context) }.getOrNull()
      ?: File(context.cacheDir, IMAGE_CACHE_DIR)

  fun downloadDir(context: Context): File? =
    runCatching { DownloadHelper.getDownloadDirectory(context) }.getOrNull()

  /** 在 IO 线程调用 */
  fun usage(context: Context): Usage {
    val cacheDir = context.cacheDir
    val audioDir = audioCacheDir(context)
    val imageDir = imageCacheDir(context)
    return Usage(
      audio = sizeOf(audioDir),
      image = sizeOf(imageDir),
      download = sizeOf(downloadDir(context)),
      temp = tempSize(cacheDir, audioDir, imageDir)
    )
  }

  /** 目录占用字节数，目录不存在或不可读时返回 0 */
  fun sizeOf(dir: File?): Long {
    if (dir == null || !dir.exists()) return 0L
    return runCatching {
      dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }.getOrDefault(0L)
  }

  private fun tempSize(cacheDir: File?, audioDir: File, imageDir: File): Long {
    if (cacheDir == null || !cacheDir.exists()) return 0L
    val skip = setOf(pathOf(audioDir), pathOf(imageDir))
    return runCatching {
      cacheDir.listFiles()
        ?.filterNot { pathOf(it) in skip }
        ?.sumOf { sizeOf(it) }
        ?: 0L
    }.getOrDefault(0L)
  }

  private fun pathOf(file: File): String =
    runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)

  // ------------------------------------------------------------------ 清理

  /** 清理 media3 音频缓存，正在播放的音频会重新缓存 */
  fun clearAudioCache() {
    runCatching {
      val cache = QYMusicApplication.simpleCache
      cache.keys.toList().forEach { key -> cache.removeResource(key) }
    }
  }

  /** 清理 Glide 图片磁盘缓存（阻塞方法，需在 IO 线程调用；内存缓存另行清理） */
  fun clearImageCache(context: Context) {
    runCatching { Glide.get(context).clearDiskCache() }
  }

  /** 删除下载目录内的歌曲与歌词文件，不删除目录本身 */
  fun clearDownloadFiles(context: Context) {
    downloadDir(context)?.listFiles()?.forEach { file ->
      runCatching { file.deleteRecursively() }
    }
  }

  /** 删除 cacheDir 中除音频、图片缓存以外的临时文件 */
  fun clearTempCache(context: Context) {
    val cacheDir = context.cacheDir ?: return
    val skip = setOf(pathOf(audioCacheDir(context)), pathOf(imageCacheDir(context)))
    cacheDir.listFiles()?.forEach { file ->
      if (pathOf(file) !in skip) {
        runCatching { file.deleteRecursively() }
      }
    }
  }

  fun formatSize(bytes: Long): String = DownloadHelper.formatFileSize(bytes)
}
