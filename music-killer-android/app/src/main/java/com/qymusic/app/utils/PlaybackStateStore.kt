package com.qymusic.app.utils

import androidx.media3.common.C
import androidx.media3.common.Player
import com.qymusic.app.model.MusicModel
import com.qymusic.app.utils.SpUtils.getRequired
import com.qymusic.app.utils.SpUtils.put
import com.qymusic.app.utils.SpUtils.remove
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.util.concurrent.Executors

/**
 * 播放队列与进度落盘。
 *
 * 系统在后台清理进程后，Media3 服务会随进程一起消失，回到 App 时只剩一个空播放器，
 * 看起来就像「歌被自动暂停了」。这里在切歌、暂停、服务销毁前记录队列 / 当前曲目 / 进度，
 * [com.qymusic.app.service.MusicService] 启动时恢复成「暂停在上一首」的状态，由用户点播放继续。
 *
 * - 队列最多记录 [MAX_ITEMS] 首：歌单可能有上千首，超过时以当前歌曲为中心截取一段；
 * - 序列化与写入放到单线程池，避免主线程被长队列卡住；
 * - 播放中每 5 秒只更新进度（[savePosition]），不重复序列化整个队列。
 */
object PlaybackStateStore {

  private const val KEY_QUEUE = "playback_queue"
  private const val KEY_START = "playback_queue_start"
  private const val KEY_INDEX = "playback_index"
  private const val KEY_POSITION = "playback_position"

  private const val MAX_ITEMS = 300

  @Serializable
  data class Snapshot(
    val items: List<MusicModel>,
    /** 截取窗口在完整队列中的起始位置 */
    val start: Int,
    /** 完整队列中的当前曲目下标 */
    val index: Int,
    val position: Long,
  )

  private val serializer = ListSerializer(MusicModel.serializer())

  private val writer = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "playback-state-save").apply { isDaemon = true }
  }

  /** 队列内容有变化（切歌 / 增删）时调用，同时记录进度 */
  @JvmStatic
  fun save(player: Player) {
    val snapshot = capture(player) ?: return
    writer.execute { persist(snapshot) }
  }

  /** 只更新当前曲目和进度，播放中定时调用，避免反复序列化整个队列 */
  @JvmStatic
  fun savePosition(player: Player) {
    if (player.mediaItemCount <= 0) return
    val index = player.currentMediaItemIndex
    if (index == C.INDEX_UNSET) return
    val position = player.currentPosition.coerceAtLeast(0L)
    writer.execute {
      KEY_INDEX put index
      KEY_POSITION put position
    }
  }

  @JvmStatic
  fun load(): Snapshot? = runCatching {
    val encoded = KEY_QUEUE.getRequired("")
    if (encoded.isEmpty()) return null
    val items = json.decodeFromString(serializer, encoded)
    if (items.isEmpty()) return null
    val start = KEY_START.getRequired(0)
    val absoluteIndex = KEY_INDEX.getRequired(0)
    Snapshot(
      items = items,
      start = start,
      index = (absoluteIndex - start).coerceIn(0, items.lastIndex),
      position = KEY_POSITION.getRequired(0L),
    )
  }.getOrNull()

  /** 手动清空播放队列后调用，避免下次启动恢复旧队列 */
  @JvmStatic
  fun clear() {
    writer.execute {
      KEY_QUEUE.remove()
      KEY_START.remove()
      KEY_INDEX.remove()
      KEY_POSITION.remove()
    }
  }

  /** 在主线程（播放器所在线程）读取当前队列，序列化和写盘交给 [writer] */
  private fun capture(player: Player): Snapshot? {
    val total = player.mediaItemCount
    if (total <= 0) return null
    val current = player.currentMediaItemIndex
    val start = if (total > MAX_ITEMS) {
      ((if (current == C.INDEX_UNSET) 0 else current) - MAX_ITEMS / 2)
        .coerceIn(0, total - MAX_ITEMS)
    } else {
      0
    }
    val end = minOf(total, start + MAX_ITEMS)
    val items = ArrayList<MusicModel>(end - start)
    for (index in start until end) {
      val model = player.getMediaItemAt(index).mediaMetadata.getMusicInfo()
      if (model != null && model.id != 0L) {
        items.add(model)
      }
    }
    if (items.isEmpty()) return null
    return Snapshot(
      items = items,
      start = start,
      index = if (current == C.INDEX_UNSET) 0 else current,
      position = if (current == C.INDEX_UNSET) 0L else player.currentPosition.coerceAtLeast(0L),
    )
  }

  private fun persist(snapshot: Snapshot) {
    val encoded = runCatching { json.encodeToString(serializer, snapshot.items) }.getOrNull()
      ?: return
    KEY_QUEUE put encoded
    KEY_START put snapshot.start
    KEY_INDEX put snapshot.index
    KEY_POSITION put snapshot.position
  }
}
