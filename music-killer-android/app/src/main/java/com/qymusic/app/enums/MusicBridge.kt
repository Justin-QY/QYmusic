package com.qymusic.app.enums

/**
 * 音质
 *
 * @param level 服务端解析接口使用的音质标识
 * @param displayName 设置页展示的名称
 */
enum class MusicBridge(val level: String, val displayName: String) {
  MP3_128K("128kmp3", "128k"),
  MP3_192K("192kmp3", "192k"),
  MP3_320K("320kmp3", "320k"),
  FLAC_2000K("2000kflac", "无损 FLAC"),
  HIRES("hiresflac", "Hi-Res 24bit FLAC");

  companion object {

    /** 默认音质：最低的 128k */
    val DEFAULT: MusicBridge = MP3_128K

    @JvmStatic
    fun getBridgeForLevel(level: String): MusicBridge {
      for (bridge in entries) {
        if (bridge.level == level) {
          return bridge
        }
      }
      return DEFAULT
    }

    /** 设置页下拉列表展示用 */
    @JvmStatic
    fun displayNames(): Array<String> = entries.map { it.displayName }.toTypedArray()
  }
}
