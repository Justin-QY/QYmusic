package com.qymusic.app.utils.lyric

import com.drake.net.Get
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import com.qymusic.app.config.Api
import com.qymusic.app.config.SPConfig
import com.qymusic.app.model.MusicModel

object LyricUtils {

  /** 网易云公开歌词接口，用作服务端取不到歌词时的兜底 */
  private const val NETEASE_LYRIC_URL = "https://music.163.com/api/song/lyric"

  /**
   * 根据歌曲来源获取歌词。
   *
   * 网易云走服务端 /lyric/{id}，QQ 音乐走 /qq/lyric/{id}?sourceId=，
   * 酷狗概念版走 /kg/lyric/{id}?sourceId=<hash>。
   * 只有网易云在服务端没有歌词时才直连公开接口兜底，其他音源不做客户端直连。
   */
  @JvmStatic
  suspend fun getLyricContent(musicModel: MusicModel): String {
    val source = musicModel.source
    if (source != "wy" && source != "qq" && source != "kg") {
      return ""
    }
    val isQq = source == "qq"
    val isKugou = source == "kg"
    return coroutineScope {
      val fromServer = try {
        val path = when {
          isQq -> Api.QQ_LYRIC
          isKugou -> Api.KG_LYRIC
          else -> Api.LYRIC
        }
        parseWrappedLyric(
          Get<String>("$path/${musicModel.id}") {
            if (isQq || isKugou) {
              addQuery("sourceId", musicModel.sourceId)
            } else {
              addQuery("translate", SPConfig.isShowTranslation())
            }
          }.await().orEmpty()
        )
      } catch (e: Exception) {
        ""
      }
      if (isQq || isKugou) {
        return@coroutineScope fromServer
      }
      if (fromServer.isNotBlank()) {
        return@coroutineScope fromServer
      }

      // 网易云服务端没有歌词时，客户端直连公开接口再试一次
      try {
        val raw = Get<String>(NETEASE_LYRIC_URL) {
          addQuery("id", musicModel.id)
          addQuery("lv", -1)
          addQuery("kv", -1)
          addQuery("tv", -1)
          addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36")
          addHeader("Referer", "https://music.163.com/")
        }.await().orEmpty()
        val json = JSONObject(raw)
        json.optJSONObject("lrc")?.optString("lyric").orEmpty()
      } catch (e: Exception) {
        ""
      }
    }
  }

  /**
   * 解析服务端返回的外层 JSON，取出 data 字段。
   * drake 对 String 类型会直接返回响应体原文，所以这里必须自己拆一层。
   * 如果拿到的已经是纯歌词（不是 JSON），原样返回。
   */
  private fun parseWrappedLyric(raw: String): String {
    val text = raw.trim()
    if (text.isEmpty()) {
      return ""
    }
    if (!text.startsWith("{")) {
      return raw
    }
    return try {
      val json = JSONObject(text)
      if (json.optInt("code", 0) == 200) json.optString("data") else ""
    } catch (e: Exception) {
      ""
    }
  }

}
