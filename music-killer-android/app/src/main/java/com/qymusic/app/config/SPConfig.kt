package com.qymusic.app.config

import com.qymusic.app.utils.SpUtils.getRequired
import com.qymusic.app.utils.SpUtils.put

object SPConfig {

  /**
   * 当前音质等级
   */
  const val CURRENT_BRIDGE = "current_bridge"

  /**
   * 是否已同意「用户协议与隐私政策」
   */
  const val TERMS_AGREED = "terms_agreed"

  /**
   * 曲库音源：wy=网易云，kw=酷我
   */
  const val CATALOG_SOURCE = "catalog_source"

  const val SOURCE_NETEASE = "wy"
  const val SOURCE_KUWO = "kw"

  /**
   * 第三方音源（默认关闭）
   */
  const val THIRD_PARTY_ENABLED = "third_party_enabled"
  const val THIRD_PARTY_KEY = "third_party_key"

  /** 目前唯一接入的第三方音源：ChKSz API */
  const val THIRD_PARTY_BASE = "https://api.chksz.com"

  @JvmStatic
  fun isThirdPartyEnabled(): Boolean = THIRD_PARTY_ENABLED.getRequired(false)

  @JvmStatic
  fun setThirdPartyEnabled(enabled: Boolean) {
    THIRD_PARTY_ENABLED put enabled
  }

  @JvmStatic
  fun thirdPartyKey(): String = THIRD_PARTY_KEY.getRequired("")

  fun setThirdPartyKey(key: String) {
    THIRD_PARTY_KEY put key
  }

  /**
   * 移动网络省流量：开启后使用流量时强制 128k
   */
  const val DATA_SAVER = "data_saver"

  fun isDataSaver(): Boolean = DATA_SAVER.getRequired(true)

  fun setDataSaver(enabled: Boolean) {
    DATA_SAVER put enabled
  }

  /**
   * 歌词显示翻译
   */
  const val SHOW_TRANSLATION = "show_translation"

  fun isShowTranslation(): Boolean = SHOW_TRANSLATION.getRequired(false)

  fun setShowTranslation(enabled: Boolean) {
    SHOW_TRANSLATION put enabled
  }

  /**
   * 主题：-1 跟随系统，1 浅色，2 深色（对应 AppCompatDelegate 常量）
   */
  const val THEME_MODE = "theme_mode"

  fun themeMode(): Int = THEME_MODE.getRequired(-1)

  fun setThemeMode(mode: Int) {
    THEME_MODE put mode
  }

  /**
   * 当前播放音质（默认最低的 128k）
   */
  fun currentBridge(): String =
    CURRENT_BRIDGE.getRequired(com.qymusic.app.enums.MusicBridge.DEFAULT.level)

  fun setCurrentBridge(level: String) {
    CURRENT_BRIDGE put level
  }

  /**
   * 当前曲库音源（默认网易云）
   */
  fun catalogSource(): String = CATALOG_SOURCE.getRequired(SOURCE_NETEASE)

  fun setCatalogSource(source: String) {
    CATALOG_SOURCE put source
  }

  fun isTermsAgreed(): Boolean = TERMS_AGREED.getRequired(false)

  fun setTermsAgreed(agreed: Boolean) {
    TERMS_AGREED put agreed
  }
}
