package com.qymusic.app.utils

import android.content.Context
import android.content.SharedPreferences
import com.drake.net.utils.withIO
import com.qymusic.app.QYMusicApplication
import com.qymusic.app.config.SPConfig
import com.qymusic.app.model.ArtistModel
import com.qymusic.app.model.FavoriteArtistModel
import com.qymusic.app.model.FavoriteModel
import com.qymusic.app.model.FavoritePlaylistModel
import com.qymusic.app.model.PlayListModel
import kotlinx.serialization.Serializable
import org.litepal.LitePal
import org.litepal.extension.findAll
import org.litepal.extension.find
import org.litepal.extension.findFirst

/**
 * 本地收藏读写：歌曲 / 歌单 / 歌手。
 *
 * - 歌曲仍沿用 LitePal 的 FavoriteModel（历史数据兼容）；
 * - 歌单 / 歌手改用 SharedPreferences + JSON 存储：
 *   数据量很小，不依赖数据库建表，升级后不会因为表结构差异导致「收藏了但列表为空」。
 */
object FavoriteRepository {

  private const val PREFS_NAME = "favorite_extra"
  private const val KEY_PLAYLISTS = "favorite_playlists"
  private const val KEY_ARTISTS = "favorite_artists"

  @Serializable
  data class PlaylistFavorite(
    val id: Long = 0,
    val name: String = "",
    val cover: String = "",
    val author: String = "",
    val desc: String = "",
    val source: String = "wy",
  )

  @Serializable
  data class ArtistFavorite(
    val id: Long = 0,
    val name: String = "",
    val cover: String = "",
    val source: String = "wy",
  )

  private fun prefs(): SharedPreferences =
    QYMusicApplication.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  // ------------------------------------------------------------------ 歌曲

  suspend fun songs(): List<FavoriteModel> = withIO {
    LitePal.where("type = ?", FavoriteModel.TYPE_SONG.toString()).find<FavoriteModel>()
  }

  // ------------------------------------------------------------------ 歌单

  suspend fun playlists(): List<PlaylistFavorite> = withIO {
    loadPlaylists()
  }

  private fun loadPlaylists(): List<PlaylistFavorite> {
    val store = prefs()
    val raw = store.getString(KEY_PLAYLISTS, null)
    if (raw == null) {
      // 从 1.0.11 的数据库表迁移一次（存在就迁移，失败也忽略）
      val migrated = runCatching {
        LitePal.findAll<FavoritePlaylistModel>().map {
          PlaylistFavorite(
            id = it.playlistId,
            name = it.name,
            cover = it.cover,
            author = it.author,
            desc = it.desc,
            source = it.source,
          )
        }
      }.getOrDefault(emptyList())
      savePlaylists(migrated)
      return migrated
    }
    return decode(raw, emptyList())
  }

  private fun savePlaylists(list: List<PlaylistFavorite>) {
    prefs().edit()
      .putString(KEY_PLAYLISTS, json.encodeToString(list))
      .apply()
  }

  suspend fun isPlaylistFavorite(playlistId: Long): Boolean = withIO {
    if (playlistId <= 0L) {
      return@withIO false
    }
    loadPlaylists().any { it.id == playlistId }
  }

  /** @return 切换后是否已收藏 */
  suspend fun togglePlaylist(model: PlayListModel): Boolean = withIO {
    if (model.id <= 0L) {
      return@withIO false
    }
    val list = loadPlaylists().toMutableList()
    val index = list.indexOfFirst { it.id == model.id }
    val favorite = if (index >= 0) {
      list.removeAt(index)
      false
    } else {
      list.add(
        PlaylistFavorite(
          id = model.id,
          name = model.name,
          cover = model.img.ifBlank { model.img700 },
          author = model.uname.ifBlank { model.userName },
          desc = model.desc,
          source = SPConfig.catalogSource(),
        )
      )
      true
    }
    savePlaylists(list)
    favorite
  }

  // ------------------------------------------------------------------ 歌手

  suspend fun artists(): List<ArtistFavorite> = withIO {
    loadArtists()
  }

  private fun loadArtists(): List<ArtistFavorite> {
    val store = prefs()
    val raw = store.getString(KEY_ARTISTS, null)
    if (raw == null) {
      val migrated = runCatching {
        LitePal.findAll<FavoriteArtistModel>().map {
          ArtistFavorite(
            id = it.artistId,
            name = it.name,
            cover = it.cover,
            source = it.source,
          )
        }
      }.getOrDefault(emptyList())
      saveArtists(migrated)
      return migrated
    }
    return decode(raw, emptyList())
  }

  private fun saveArtists(list: List<ArtistFavorite>) {
    prefs().edit()
      .putString(KEY_ARTISTS, json.encodeToString(list))
      .apply()
  }

  suspend fun isArtistFavorite(artistId: Long): Boolean = withIO {
    if (artistId <= 0L) {
      return@withIO false
    }
    loadArtists().any { it.id == artistId }
  }

  /** @return 切换后是否已收藏 */
  suspend fun toggleArtist(model: ArtistModel): Boolean = withIO {
    if (model.id <= 0L) {
      return@withIO false
    }
    val list = loadArtists().toMutableList()
    val index = list.indexOfFirst { it.id == model.id }
    val favorite = if (index >= 0) {
      list.removeAt(index)
      false
    } else {
      list.add(
        ArtistFavorite(
          id = model.id,
          name = model.name,
          cover = model.pic300.ifBlank { model.pic },
          source = SPConfig.catalogSource(),
        )
      )
      true
    }
    saveArtists(list)
    favorite
  }

  private inline fun <reified T> decode(raw: String, fallback: T): T =
    runCatching { json.decodeFromString<T>(raw) }.getOrDefault(fallback)
}
