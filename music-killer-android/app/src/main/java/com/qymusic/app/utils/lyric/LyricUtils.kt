package com.qymusic.app.utils.lyric

import com.drake.net.Get
import kotlinx.coroutines.coroutineScope
import okio.ByteString
import org.json.JSONObject
import com.qymusic.app.config.Api
import com.qymusic.app.config.SPConfig
import com.qymusic.app.model.MusicModel

object LyricUtils {

  private const val LYRICS_URL = "http://newlyric.kuwo.cn/newlyric.lrc"

  /** 网易云公开歌词接口，用作服务端取不到歌词时的兜底 */
  private const val NETEASE_LYRIC_URL = "https://music.163.com/api/song/lyric"

  /**
   * 根据歌曲来源获取歌词。
   *
   * 网易云歌曲走服务端 /lyric/{id}（网易云 LRC），
   * 酷我歌曲仍然直连酷我歌词接口。
   */
  @JvmStatic
  suspend fun getLyricContent(musicModel: MusicModel): String {
    if (musicModel.source == "wy" || musicModel.source == "qq") {
      val isQq = musicModel.source == "qq"
      return coroutineScope {
        val fromServer = try {
          val path = if (isQq) Api.QQ_LYRIC else Api.LYRIC
          parseWrappedLyric(
            Get<String>("$path/${musicModel.id}") {
              addQuery("translate", !isQq && SPConfig.isShowTranslation())
              if (isQq) addQuery("sourceId", musicModel.sourceId)
            }.await().orEmpty()
          )
        } catch (e: Exception) {
          ""
        }
        if (fromServer.isNotBlank() || isQq) {
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
    return getLyricContent(musicModel.id)
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

  @JvmStatic
  suspend fun getLyricContent(musicId: Long) = coroutineScope {
    // 1. 构建请求参数
    val params = KuwoLyricDecryptor.buildParams(musicId, false)

    // 2. 发起 HTTP 请求获取响应数据
    val responseData = Get<ByteString>("${LYRICS_URL}?$params").await()

    // 3. 解密歌词
    val rawLyric = KuwoLyricDecryptor.decodeLyrics(responseData.toByteArray(), false)

    // 4. 转换格式
    return@coroutineScope KuwoLyricDecryptor.convertKuwoLrc(rawLyric)
  }

}
