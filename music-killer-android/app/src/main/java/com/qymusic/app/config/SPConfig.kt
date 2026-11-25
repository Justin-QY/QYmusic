package com.qymusic.app.config

import com.qymusic.app.BuildConfig
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
   * 曲库音源：wy=网易云，kg=酷狗概念版，qq=QQ 音乐
   */
  const val CATALOG_SOURCE = "catalog_source"

  const val SOURCE_NETEASE = "wy"
  const val SOURCE_KUGOU = "kg"
  const val SOURCE_QQ = "qq"

  // ------------------------------------------------------------------ QQ / 酷狗账号

  const val QQ_COOKIE = "qq_cookie"
  const val QQ_UIN = "qq_uin"
  const val QQ_NICKNAME = "qq_nickname"
  const val QQ_AVATAR = "qq_avatar"

  const val KUGOU_COOKIE = "kugou_cookie"
  const val KUGOU_UID = "kugou_uid"
  const val KUGOU_NICKNAME = "kugou_nickname"
  const val KUGOU_AVATAR = "kugou_avatar"

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

  fun kugouCookie(): String = KUGOU_COOKIE.getRequired("")

  fun kugouUid(): Long = KUGOU_UID.getRequired(0L)

  fun kugouNickname(): String = KUGOU_NICKNAME.getRequired("")

  fun kugouAvatar(): String = KUGOU_AVATAR.getRequired("")

  fun isKugouLoggedIn(): Boolean = kugouCookie().isNotBlank() && kugouUid() > 0

  fun saveKugouLogin(cookie: String, uid: Long, nickname: String, avatar: String) {
    KUGOU_COOKIE put cookie
    KUGOU_UID put uid
    KUGOU_NICKNAME put nickname
    KUGOU_AVATAR put avatar
  }

  fun clearKugouLogin() {
    KUGOU_COOKIE put ""
    KUGOU_UID put 0L
    KUGOU_NICKNAME put ""
    KUGOU_AVATAR put ""
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
   * 底部导航排序（CSV，默认 home,cloud）。
   * 未保存过、内容损坏或缺少模块时用 fallback 补齐，保证不丢模块。
   */
  const val NAV_ORDER = "nav_order"

  fun navOrder(fallback: List<String>): List<String> {
    val raw = NAV_ORDER.getRequired(fallback.joinToString(","))
    val saved = raw.split(',')
      .map { it.trim() }
      .filter { it in fallback }
      .distinct()
    return saved + fallback.filter { it !in saved }
  }

  fun setNavOrder(order: List<String>) {
    NAV_ORDER put order.filter { it.isNotBlank() }.distinct().joinToString(",")
  }

  /**
   * 播放音源顺序：官方 → 落雪脚本 → 第三方 API。
   * 三个来源各自独立开关，第三方默认关闭。
   */
  const val OFFICIAL_SOURCE_ENABLED = "official_source_enabled"
  const val LX_SOURCE_ENABLED = "lx_source_enabled"
  const val THIRD_PARTY_ENABLED = "third_party_enabled"
  const val THIRD_PARTY_KEY = "third_party_key"

  /** 目前唯一接入的第三方音源：ChKSz API */
  const val THIRD_PARTY_BASE = "https://api.chksz.com"

  /** 内置 ChKSz 密钥：构建时从本机 local.properties 注入，不入库 */
  private val BUILTIN_THIRD_PARTY_KEY: String = BuildConfig.CHKSZ_API_KEY

  /** 官方原生音源，默认开启 */
  @JvmStatic
  fun isOfficialSourceEnabled(): Boolean = OFFICIAL_SOURCE_ENABLED.getRequired(true)

  @JvmStatic
  fun setOfficialSourceEnabled(enabled: Boolean) {
    OFFICIAL_SOURCE_ENABLED put enabled
  }

  /** 落雪 LX 脚本，默认开启 */
  @JvmStatic
  fun isLxSourceEnabled(): Boolean = LX_SOURCE_ENABLED.getRequired(true)

  @JvmStatic
  fun setLxSourceEnabled(enabled: Boolean) {
    LX_SOURCE_ENABLED put enabled
  }

  /** 第三方 API，默认关闭 */
  @JvmStatic
  fun isThirdPartyEnabled(): Boolean = THIRD_PARTY_ENABLED.getRequired(false)

  @JvmStatic
  fun setThirdPartyEnabled(enabled: Boolean) {
    THIRD_PARTY_ENABLED put enabled
  }

  @JvmStatic
  fun thirdPartyKey(): String {
    val saved = THIRD_PARTY_KEY.getRequired("")
    return if (saved.isBlank()) BUILTIN_THIRD_PARTY_KEY else saved
  }

  /** 用户自己填写的密钥；为空时使用内置密钥 */
  @JvmStatic
  fun customThirdPartyKey(): String = THIRD_PARTY_KEY.getRequired("")

  @JvmStatic
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
   * 歌词显示翻译（旧版开关，key 保持不变）
   */
  const val SHOW_TRANSLATION = "show_translation"

  fun isShowTranslation(): Boolean = SHOW_TRANSLATION.getRequired(false)

  fun setShowTranslation(enabled: Boolean) {
    SHOW_TRANSLATION put enabled
  }

  // ------------------------------------------------------------------ 歌词设置

  /**
   * 歌词字号（sp）。
   * 预设档位：小 16 / 标准 20 / 大 24 / 特大 28，写入时统一夹在 12–28 之间，
   * 老用户没有这个 key 时回到标准档，表现与旧版一致。
   */
  const val LYRIC_FONT_SIZE = "lyric_font_size"

  const val LYRIC_FONT_SIZE_SMALL = 16
  const val LYRIC_FONT_SIZE_NORMAL = 20
  const val LYRIC_FONT_SIZE_LARGE = 24
  const val LYRIC_FONT_SIZE_XLARGE = 28

  const val LYRIC_FONT_SIZE_MIN = 12
  const val LYRIC_FONT_SIZE_MAX = 28

  /** 歌词设置页展示顺序 */
  val LYRIC_FONT_SIZE_PRESETS = listOf(
    LYRIC_FONT_SIZE_SMALL,
    LYRIC_FONT_SIZE_NORMAL,
    LYRIC_FONT_SIZE_LARGE,
    LYRIC_FONT_SIZE_XLARGE,
  )

  fun lyricFontSize(): Int =
    LYRIC_FONT_SIZE.getRequired(LYRIC_FONT_SIZE_NORMAL)
      .coerceIn(LYRIC_FONT_SIZE_MIN, LYRIC_FONT_SIZE_MAX)

  fun setLyricFontSize(sizeSp: Int) {
    LYRIC_FONT_SIZE put sizeSp.coerceIn(LYRIC_FONT_SIZE_MIN, LYRIC_FONT_SIZE_MAX)
  }

  /**
   * 歌词页翻译开关。
   * 与旧版「显示歌曲翻译」共用同一个 key，老用户设置不会丢，
   * 设置页与歌词设置页读写的是同一份数据，两个入口天然同步。
   */
  const val LYRIC_TRANSLATION = SHOW_TRANSLATION

  fun isLyricTranslationEnabled(): Boolean = SHOW_TRANSLATION.getRequired(false)

  fun setLyricTranslationEnabled(enabled: Boolean) {
    SHOW_TRANSLATION put enabled
  }

  /**
   * 澎湃 OS（HyperOS）超级岛歌词开关，默认关闭。
   * 关闭时不做任何额外通知，保持旧版行为。
   */
  const val LYRIC_SUPER_ISLAND = "lyric_super_island"

  fun isLyricSuperIslandEnabled(): Boolean = LYRIC_SUPER_ISLAND.getRequired(true)

  fun setLyricSuperIslandEnabled(enabled: Boolean) {
    LYRIC_SUPER_ISLAND put enabled
  }

  /**
   * 主题：-1 跟随系统，1 浅色，2 深色（对应 AppCompatDelegate 常量）
   */
  const val THEME_MODE = "theme_mode"

  fun themeMode(): Int = THEME_MODE.getRequired(-1)

  fun setThemeMode(mode: Int) {
    THEME_MODE put mode
  }

  // ------------------------------------------------------------------ 通用设置

  /**
   * 默认启动页：保存 NavModules 的稳定 key（home / cloud），默认首页。
   * 实际跳转时由 NavModules.startPage() 过滤被隐藏的模块。
   */
  /** 是否已经提示过后台保活设置 */
  const val KEEP_ALIVE_PROMPTED = "keep_alive_prompted"

  fun isKeepAlivePrompted(): Boolean = KEEP_ALIVE_PROMPTED.getRequired(false)

  fun setKeepAlivePrompted(prompted: Boolean) {
    KEEP_ALIVE_PROMPTED put prompted
  }

  const val START_PAGE = "start_page"

  const val START_PAGE_DEFAULT = "home"

  fun startPageKey(): String = START_PAGE.getRequired(START_PAGE_DEFAULT)

  fun setStartPageKey(key: String) {
    START_PAGE put key
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
   * 当前曲库音源（默认网易云）。
   * 老版本保存过 "kw"，该音源已下线，读到后自动回到网易云。
   */
  fun catalogSource(): String {
    val source = CATALOG_SOURCE.getRequired(SOURCE_NETEASE)
    return if (source == "kw") SOURCE_NETEASE else source
  }

  fun setCatalogSource(source: String) {
    CATALOG_SOURCE put source
  }

  /** 曲库展示名，避免各处重复写映射：wy=网易云，qq=QQ音乐，kg=酷狗 */
  fun platformLabel(source: String): String = when (source) {
    SOURCE_QQ -> "QQ音乐"
    SOURCE_KUGOU -> "酷狗"
    else -> "网易云"
  }

  // ------------------------------------------------------------------ 云端平台

  /** 云端平台开关：逗号分隔，默认三个全开 */
  const val CLOUD_PLATFORMS = "cloud_platforms"

  /** 云端平台排序：逗号分隔的 key，默认 netease,qq,kugou */
  const val CLOUD_PLATFORM_ORDER = "cloud_platform_order"

  const val CLOUD_NETEASE = "netease"
  const val CLOUD_QQ = "qq"
  const val CLOUD_KUGOU = "kugou"

  /** 云端平台的固定顺序，界面展示与兜底都按这个顺序 */
  val ALL_CLOUD_PLATFORMS = listOf(CLOUD_NETEASE, CLOUD_QQ, CLOUD_KUGOU)

  /**
   * 已保存的平台顺序（始终包含全部平台，缺少的按默认顺序补齐）。
   * 每次读取都补齐，避免旧版本或异常写入导致某个平台从界面消失。
   */
  fun cloudPlatformOrder(): List<String> {
    val raw = CLOUD_PLATFORM_ORDER.getRequired(ALL_CLOUD_PLATFORMS.joinToString(","))
    val saved = raw.split(',')
      .map { it.trim() }
      .filter { it in ALL_CLOUD_PLATFORMS }
      .distinct()
    return saved + ALL_CLOUD_PLATFORMS.filter { it !in saved }
  }

  fun setCloudPlatformOrder(order: List<String>) {
    val keys = order.filter { it in ALL_CLOUD_PLATFORMS }.distinct()
    val full = keys + ALL_CLOUD_PLATFORMS.filter { it !in keys }
    CLOUD_PLATFORM_ORDER put full.joinToString(",")
  }

  /**
   * 按「已启用平台的新顺序」重排：未启用平台保持原有相对位置。
   * 传入的 key 与当前启用集合不一致时直接忽略，避免写坏配置。
   */
  fun applyCloudPlatformOrder(enabledOrder: List<String>) {
    val newOrder = enabledOrder.filter { it in ALL_CLOUD_PLATFORMS }.distinct()
    val full = cloudPlatformOrder()
    if (newOrder.isEmpty() || newOrder.toSet() != enabledCloudPlatforms()) {
      return
    }
    var index = 0
    val merged = full.map { key -> if (key in newOrder) newOrder[index++] else key }
    setCloudPlatformOrder(merged)
  }

  /**
   * 已启用的云端平台（至少一个），按保存的平台顺序返回。
   * 存储内容损坏或全部关闭时回落到三个平台全开，避免云端页变成空白。
   */
  fun enabledCloudPlatforms(): Set<String> {
    val raw = CLOUD_PLATFORMS.getRequired(ALL_CLOUD_PLATFORMS.joinToString(","))
    val enabled = raw.split(',')
      .map { it.trim() }
      .filter { it in ALL_CLOUD_PLATFORMS }
      .toSet()
    if (enabled.isEmpty()) {
      return ALL_CLOUD_PLATFORMS.toSet()
    }
    // 按保存顺序返回，保证「第一个启用平台」与云端页芯片顺序一致
    return cloudPlatformOrder().filter { it in enabled }.toCollection(LinkedHashSet())
  }

  /**
   * 开关某个云端平台。至少保留一个，关闭最后一个时返回 false 且不写入。
   */
  fun setCloudPlatformEnabled(platform: String, enabled: Boolean): Boolean {
    if (platform !in ALL_CLOUD_PLATFORMS) {
      return false
    }
    val current = enabledCloudPlatforms().toMutableSet()
    if (enabled) {
      current.add(platform)
    } else {
      current.remove(platform)
      if (current.isEmpty()) {
        return false
      }
    }
    CLOUD_PLATFORMS put ALL_CLOUD_PLATFORMS.filter { it in current }.joinToString(",")
    return true
  }

  fun isCloudPlatformEnabled(platform: String): Boolean =
    platform in enabledCloudPlatforms()

  // ------------------------------------------------------------------ 曲库搜索

  /** 搜索时参与的平台，逗号分隔，默认三个全开 */
  const val SEARCH_PLATFORMS = "search_platforms"

  /** 搜索平台排序，逗号分隔，默认 wy,qq,kg */
  const val SEARCH_PLATFORM_ORDER = "search_platform_order"

  /** 聚合搜索：多个平台并发搜索并合并结果，默认关闭 */
  const val SEARCH_AGGREGATE = "search_aggregate"

  /** 搜索平台的固定顺序 */
  val ALL_SEARCH_PLATFORMS = listOf(SOURCE_NETEASE, SOURCE_QQ, SOURCE_KUGOU)

  /** 已保存的搜索平台顺序（始终包含全部平台） */
  fun searchPlatformOrder(): List<String> {
    val raw = SEARCH_PLATFORM_ORDER.getRequired(ALL_SEARCH_PLATFORMS.joinToString(","))
    val saved = raw.split(',')
      .map { it.trim() }
      .filter { it in ALL_SEARCH_PLATFORMS }
      .distinct()
    return saved + ALL_SEARCH_PLATFORMS.filter { it !in saved }
  }

  fun setSearchPlatformOrder(order: List<String>) {
    val keys = order.filter { it in ALL_SEARCH_PLATFORMS }.distinct()
    val full = keys + ALL_SEARCH_PLATFORMS.filter { it !in keys }
    SEARCH_PLATFORM_ORDER put full.joinToString(",")
  }

  /** 按已启用平台的新顺序重排，未启用平台保持相对位置 */
  fun applySearchPlatformOrder(enabledOrder: List<String>) {
    val newOrder = enabledOrder.filter { it in ALL_SEARCH_PLATFORMS }.distinct()
    val full = searchPlatformOrder()
    if (newOrder.isEmpty() || newOrder.toSet() != enabledSearchPlatforms()) {
      return
    }
    var index = 0
    val merged = full.map { key -> if (key in newOrder) newOrder[index++] else key }
    setSearchPlatformOrder(merged)
  }

  /** 已启用的搜索平台（至少一个），按保存顺序返回 */
  fun enabledSearchPlatforms(): Set<String> {
    val raw = SEARCH_PLATFORMS.getRequired(ALL_SEARCH_PLATFORMS.joinToString(","))
    val enabled = raw.split(',')
      .map { it.trim() }
      .filter { it in ALL_SEARCH_PLATFORMS }
      .toSet()
    if (enabled.isEmpty()) {
      return ALL_SEARCH_PLATFORMS.toSet()
    }
    return searchPlatformOrder().filter { it in enabled }.toCollection(LinkedHashSet())
  }

  /** 开关搜索平台，至少保留一个 */
  fun setSearchPlatformEnabled(platform: String, enabled: Boolean): Boolean {
    if (platform !in ALL_SEARCH_PLATFORMS) {
      return false
    }
    val current = enabledSearchPlatforms().toMutableSet()
    if (enabled) {
      current.add(platform)
    } else {
      current.remove(platform)
      if (current.isEmpty()) {
        return false
      }
    }
    SEARCH_PLATFORMS put ALL_SEARCH_PLATFORMS.filter { it in current }.joinToString(",")
    return true
  }

  fun isSearchPlatformEnabled(platform: String): Boolean =
    platform in enabledSearchPlatforms()

  /** 聚合搜索是否开启，默认关闭（只搜当前单个平台） */
  fun isAggregateSearch(): Boolean = SEARCH_AGGREGATE.getRequired(false)

  fun setAggregateSearch(enabled: Boolean) {
    SEARCH_AGGREGATE put enabled
  }

  fun isTermsAgreed(): Boolean = TERMS_AGREED.getRequired(false)

  fun setTermsAgreed(agreed: Boolean) {
    TERMS_AGREED put agreed
  }
}
