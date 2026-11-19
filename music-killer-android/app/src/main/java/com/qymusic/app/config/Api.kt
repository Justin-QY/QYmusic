package com.qymusic.app.config

object Api {
  /** 服务端地址（部署时可改到自己的域名） */
  const val BASE_API: String = "https://api.qyder.com"

  const val HOME_DATA = "/home/data"

  /**
   * 歌单标签列表
   */
  const val PLAYLIST_INDEX_TAGS = "/playlist/getIndexPlayListTags"

  /**
   * 获取推荐歌单列表
   */
  const val PLAYLIST_RECOMMEND = "/playlist/recommend"

  /**
   * 通过标签获取歌单列表
   */
  const val PLAYLIST_BY_TAG = "/playlist/getPlayListByTag"

  /**
   * 获取首页的排行榜数据
   */
  const val RANK_INDEX = "/rank/index"

  /**
   * 排行榜菜单
   */
  const val RANK_MENU = "/rank/menu"

  /**
   * 排行榜音乐列表
   */
  const val RANK_MUSIC_LIST = "/rank/getMusicList"

  /**
   * 获取歌单标签列表
   */
  const val PLAYLIST_TAGS = "/playlist/getPlayListTags"

  /**
   * 获取歌单分页列表
   */
  const val PLAYLIST_PAGE = "/playlist/page"

  /**
   * 获取标签的歌单列表
   */
  const val TAG_PLAYLIST_PAGE = "/playlist/getTagPlaylist"

  /**
   * 歌单信息
   */
  const val PLAYLIST_INFO = "/playlist/info"

  /**
   * 获取播放信息
   */
  const val PLAY_INFO = "/play/info"

  /**
   * 歌手列表
   */
  const val ARTIST_LIST = "/artist/list"

  const val ARTIST_MUSIC = "/artist/music"

  const val ARTIST_INFO = "/artist/info"

  /**
   * 搜索建议
   */
  const val SEARCH_KEYWORD = "/search/key"

  /**
   * 搜索歌曲（默认网易云）
   */
  const val SEARCH = "/search"

  /** 网易云搜索 */
  const val SEARCH_NETEASE = "/search/netease"

  /** 酷狗概念版搜索 */
  const val SEARCH_KUGOU = "/kg/search"

  /** QQ 音乐搜索 */
  const val SEARCH_QQ = "/qq/search"

  const val SEARCH_ALBUM = "/search/album"

  const val SEARCH_PLAYLIST = "/search/playlist"

  const val SEARCH_ARTIST = "/search/artist"

  /**
   * 获取歌词
   */
  const val LYRIC = "/lyric"

  /** QQ 音乐歌词 */
  const val QQ_LYRIC = "/qq/lyric"

  /** 酷狗概念版歌词（sourceId 传酷狗 hash） */
  const val KG_LYRIC = "/kg/lyric"

  /**
   * 音源脚本状态
   */
  const val LX_STATUS = "/lx/status"

  /**
   * 音源脚本列表
   */
  const val LX_SCRIPTS = "/lx/scripts"

  /**
   * 重新加载音源脚本
   */
  const val LX_RELOAD = "/lx/reload"

  /**
   * 导入音源脚本
   */
  const val LX_IMPORT = "/lx/import"

  /**
   * 删除音源脚本
   */
  const val LX_DELETE = "/lx/delete"

  /**
   * 网易云账号（云端模块）
   */
  const val NETEASE_ACCOUNT = "/netease/user/account"
  const val NETEASE_PLAYLISTS = "/netease/user/playlists"
  const val NETEASE_ARTISTS = "/netease/user/artists"
  const val NETEASE_LIKED = "/netease/user/liked"

  /** QQ 音乐账号（云端） */
  const val QQ_ACCOUNT = "/qq/user/account"
  const val QQ_PLAYLISTS = "/qq/user/playlists"
  const val QQ_LIKED = "/qq/user/liked"

  /** 酷狗概念版账号（云端） */
  const val KUGOU_ACCOUNT = "/kg/user/account"
  const val KUGOU_PLAYLISTS = "/kg/user/playlists"
  const val KUGOU_LIKED = "/kg/user/liked"

  /** 酷狗签到领 VIP */
  const val KUGOU_VIP_STATUS = "/kg/vip/status"
  const val KUGOU_VIP_CHECKIN = "/kg/vip/checkin"

  /** 扫码登录 */
  const val QR_NETEASE = "/login/qr/netease"
  const val QR_NETEASE_POLL = "/login/qr/netease/poll"
  const val QR_QQ = "/login/qr/qq"
  const val QR_QQ_POLL = "/login/qr/qq/poll"
  const val QR_KUGOU = "/login/qr/kugou"
  const val QR_KUGOU_POLL = "/login/qr/kugou/poll"

  /**
   * 按当前曲库音源拼接接口路径。
   *
   * 设置页可以在「网易云 / 酷狗概念版 / QQ 音乐」之间切换：
   * 网易云走原路径（/rank/menu 等），酷狗走 /kg 前缀（/kg/rank/menu 等），
   * QQ 音乐走 /qq 前缀。歌曲 id 与音源一起用于播放和歌词。
   */
  fun catalog(path: String): String = when (SPConfig.catalogSource()) {
    SPConfig.SOURCE_KUGOU -> "/kg$path"
    SPConfig.SOURCE_QQ -> "/qq$path"
    else -> path
  }

}