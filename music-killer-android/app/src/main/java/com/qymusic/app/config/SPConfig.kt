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
  const val SOURCE_QQ = "qq"

  // ------------------------------------------------------------------ QQ / 酷我账号

  const val QQ_COOKIE = "qq_cookie"
  const val QQ_UIN = "qq_uin"
  const val QQ_NICKNAME = "qq_nickname"
  const val QQ_AVATAR = "qq_avatar"

  const val KUWO_COOKIE = "kuwo_cookie"
  const val KUWO_UID = "kuwo_uid"
  const val KUWO_NICKNAME = "kuwo_nickname"
  const val KUWO_AVATAR = "kuwo_avatar"

  @JvmStatic
  fun qqCookie(): String = QQ_COOKIE.getRequired("")

  @JvmStatic
  fun qqUin(): String = QQ_UIN.getRequired("")

  fun qqNickname(): String = QQ_NICKNAME.getRequired("")

  fun qqAvatar(): String = QQ_AVATAR.getRequired("")

  fun isQqLoggedIn(): Boolean = qqCookie().isNotBlank()

  fun saveQqLogin(cookie: String, uin: String, nickname: String, avatar: String) {
    QQ_COOKIE put cookie
    QQ_UIN put uin
    QQ_NICKNAME put nickname
    QQ_AVATAR put avatar
  }

  fun clearQqLogin() {
    QQ_COOKIE put ""
    QQ_UIN put ""
    QQ_NICKNAME put ""
    QQ_AVATAR put ""
  }

  fun kuwoCookie(): String = KUWO_COOKIE.getRequired("")

  fun kuwoUid(): Long = KUWO_UID.getRequired(0L)

  fun kuwoNickname(): String = KUWO_NICKNAME.getRequired("")

  fun kuwoAvatar(): String = KUWO_AVATAR.getRequired("")

  fun isKuwoLoggedIn(): Boolean = kuwoCookie().isNotBlank() && kuwoUid() > 0

  fun saveKuwoLogin(cookie: String, uid: Long, nickname: String, avatar: String) {
    KUWO_COOKIE put cookie
    KUWO_UID put uid
    KUWO_NICKNAME put nickname
    KUWO_AVATAR put avatar
  }

  fun clearKuwoLogin() {
    KUWO_COOKIE put ""
    KUWO_UID put 0L
    KUWO_NICKNAME put ""
    KUWO_AVATAR put ""
  }

  // ------------------------------------------------------------------ 底部导航

  const val NAV_HIDDEN = "nav_hidden"

  fun hiddenNavKeys(): Set<String> =
    NAV_HIDDEN.getRequired("").split(',').filter { it.isNotBlank() }.toSet()

  fun isNavVisible(key: String): Boolean = key !in hiddenNavKeys()

  fun setNavVisible(key: String, visible: Boolean) {
    val keys = hiddenNavKeys().toMutableSet()
    if (visible) keys.remove(key) else keys.add(key)
    NAV_HIDDEN put keys.joinToString(",")
  }

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

  // ------------------------------------------------------------------ 网易云账号

  const val NETEASE_COOKIE = "netease_cookie"
  const val NETEASE_UID = "netease_uid"
  const val NETEASE_NICKNAME = "netease_nickname"
  const val NETEASE_AVATAR = "netease_avatar"

  fun neteaseCookie(): String = NETEASE_COOKIE.getRequired("")

  fun neteaseUid(): Long = NETEASE_UID.getRequired(0L)

  fun neteaseNickname(): String = NETEASE_NICKNAME.getRequired("")

  fun neteaseAvatar(): String = NETEASE_AVATAR.getRequired("")

  fun isNeteaseLoggedIn(): Boolean = neteaseCookie().isNotBlank() && neteaseUid() > 0

  fun saveNeteaseLogin(cookie: String, uid: Long, nickname: String, avatar: String) {
    NETEASE_COOKIE put cookie
    NETEASE_UID put uid
    NETEASE_NICKNAME put nickname
    NETEASE_AVATAR put avatar
  }

  fun clearNeteaseLogin() {
    NETEASE_COOKIE put ""
    NETEASE_UID put 0L
    NETEASE_NICKNAME put ""
    NETEASE_AVATAR put ""
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
