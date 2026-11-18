package com.qymusic.app.utils.lyric

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.Player
import com.qymusic.app.config.SPConfig
import com.qymusic.app.utils.DownloadHelper
import com.qymusic.app.utils.getMusicInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * 把正在播放的歌曲歌词推到 HyperOS 超级岛。
 *
 * 由 [com.qymusic.app.service.MusicService] 在播放器创建后 [attach]，服务销毁时 [detach]。
 * 每秒读一次播放进度，找到当前歌词行后交给 [SuperIslandHelper] 发通知；
 * 只有歌词行 / 播放状态 / 进度（每 5%）变化时才更新，避免频繁打扰。
 *
 * 歌词来源与歌词页保持一致：优先用已下载的 .lrc 文件，没有再走 [LyricUtils]。
 * 所有异常都在内部吞掉，开关关闭或设备不支持时完全不工作。
 */
object SuperIslandLyrics {

  private const val TAG = "SuperIslandLyrics"

  private const val TICK_INTERVAL_MS = 1000L

  private val handler = Handler(Looper.getMainLooper())

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  private val loadGeneration = AtomicInteger(0)

  private var appContext: Context? = null

  private var player: Player? = null

  /** 当前已加载歌词的歌曲标识，切歌时重新加载 */
  private var loadedKey: String? = null

  @Volatile
  private var lines: List<LrcLine> = emptyList()

  /** 上次真正发出的内容，用于节流 */
  private var lastPublished: String = ""

  private data class LrcLine(val timeMs: Long, val text: String)

  private val timeTagRegex = Regex("\\[(\\d{1,2}):(\\d{1,3})(?:[.:](\\d{1,3}))?\\]")

  private val ticker = object : Runnable {
    override fun run() {
      runCatching { publishCurrent() }
        .onFailure { Log.w(TAG, "tick failed: ${it.message}") }
      handler.postDelayed(this, TICK_INTERVAL_MS)
    }
  }

  /**
   * 开始跟随播放器（重复调用安全）。
   * 开关关闭时不启动，保持旧版行为。
   */
  @JvmStatic
  fun attach(context: Context, player: Player?) {
    if (player == null || !SPConfig.isLyricSuperIslandEnabled()) {
      detach()
      return
    }
    appContext = context.applicationContext
    this.player = player
    handler.removeCallbacks(ticker)
    handler.post(ticker)
  }

  /** 停止跟随并移除超级岛通知 */
  @JvmStatic
  fun detach() {
    handler.removeCallbacks(ticker)
    player = null
    loadedKey = null
    lines = emptyList()
    lastPublished = ""
    appContext?.let { SuperIslandHelper.clear(it) }
  }

  /**
   * 设置页切换开关后调用：打开则立即接入，关闭则立即清理。
   */
  @JvmStatic
  fun refresh(context: Context, player: Player?) {
    if (SPConfig.isLyricSuperIslandEnabled()) {
      attach(context, player)
    } else {
      detach()
    }
  }

  private fun publishCurrent() {
    val context = appContext ?: return
    if (!SPConfig.isLyricSuperIslandEnabled()) {
      detach()
      return
    }
    val activePlayer = player ?: return
    val item = activePlayer.currentMediaItem ?: return
    val metadata = item.mediaMetadata
    val title = metadata.title?.toString().orEmpty()
    val artist = metadata.artist?.toString().orEmpty()

    val mediaKey = item.mediaId + "|" + title
    if (mediaKey != loadedKey) {
      loadedKey = mediaKey
      lines = emptyList()
      // 非小米 / 非 HyperOS 设备即使开了开关也不去拉歌词、不发通知
      if (SuperIslandHelper.isSupported(context)) {
        loadLyrics(context, item)
      }
    }

    if (!SuperIslandHelper.isSupported(context)) {
      return
    }

    val duration = activePlayer.duration.let { if (it > 0L) it else 0L }
    val position = activePlayer.currentPosition.coerceAtLeast(0L)
    val isPlaying = activePlayer.isPlaying
    if (!isPlaying) {
      // 暂停后超级岛没有意义，直接收掉，避免留下一条常驻通知
      SuperIslandHelper.clear(context)
      lastPublished = ""
      return
    }
    val index = indexOf(position)
    val lyric = lines.getOrNull(index)?.text.orEmpty()
    val nextLyric = lines.getOrNull(index + 1)?.text.orEmpty()
    val progress = if (duration > 0L) {
      ((position.coerceAtMost(duration) * 100L) / duration).toInt().coerceIn(0, 100)
    } else {
      0
    }

    val key = listOf(
      title,
      artist,
      lyric,
      nextLyric,
      (progress / 5).toString(),
      isPlaying.toString(),
    ).joinToString("|")
    if (key == lastPublished) {
      return
    }
    val published = SuperIslandHelper.publish(
      context = context,
      title = title.ifBlank { "QY MUSIC" },
      artist = artist,
      lyric = lyric.ifBlank { title },
      nextLyric = nextLyric,
      progress = progress,
      isPlaying = isPlaying,
    )
    if (published) {
      lastPublished = key
    }
  }

  private fun loadLyrics(context: Context, item: androidx.media3.common.MediaItem) {
    val generation = loadGeneration.incrementAndGet()
    val model = runCatching { item.mediaMetadata.getMusicInfo() }.getOrNull()
    if (model == null || model.id == 0L) {
      return
    }
    scope.launch {
      val raw = try {
        val lyricFile = runCatching { DownloadHelper.getLyricFilePath(context, model) }.getOrNull()
        if (lyricFile != null && lyricFile.exists() && lyricFile.length() > 0L) {
          lyricFile.readText()
        } else {
          LyricUtils.getLyricContent(model)
        }
      } catch (e: Exception) {
        Log.w(TAG, "load lyric failed: ${e.message}")
        ""
      }
      // 已经切歌 / 被新的加载覆盖，丢弃旧结果
      if (generation != loadGeneration.get()) {
        return@launch
      }
      lines = parseLrc(raw)
    }
  }

  /** 二分找到最后一个 time <= position 的行 */
  private fun indexOf(positionMs: Long): Int {
    val current = lines
    if (current.isEmpty()) {
      return -1
    }
    var left = 0
    var right = current.size - 1
    var result = 0
    while (left <= right) {
      val middle = (left + right) / 2
      if (current[middle].timeMs <= positionMs) {
        result = middle
        left = middle + 1
      } else {
        right = middle - 1
      }
    }
    return result
  }

  private fun parseLrc(raw: String): List<LrcLine> {
    if (raw.isBlank()) {
      return emptyList()
    }
    val result = ArrayList<LrcLine>()
    for (line in raw.lineSequence()) {
      val tags = timeTagRegex.findAll(line).toList()
      if (tags.isEmpty()) {
        continue
      }
      val text = line.substring(tags.last().range.last + 1).trim()
      if (text.isEmpty()) {
        continue
      }
      for (match in tags) {
        val minutes = match.groupValues[1].toLongOrNull() ?: continue
        val seconds = match.groupValues[2].toLongOrNull() ?: 0L
        val fraction = fractionToMs(match.groupValues[3])
        result.add(LrcLine(minutes * 60_000L + seconds * 1_000L + fraction, text))
      }
    }
    result.sortBy { it.timeMs }
    return result
  }

  /** `[00:12.34]` / `[00:12.345]` / `[00:12]` 都按毫秒处理 */
  private fun fractionToMs(fraction: String): Long = when (fraction.length) {
    0 -> 0L
    1 -> fraction.toLongOrNull()?.times(100L) ?: 0L
    2 -> fraction.toLongOrNull()?.times(10L) ?: 0L
    else -> fraction.take(3).toLongOrNull() ?: 0L
  }
}
