package com.qymusic.app.utils

import com.drake.net.utils.withIO
import com.qymusic.app.config.SPConfig
import com.qymusic.app.model.FavoriteArtistModel
import com.qymusic.app.model.FavoriteModel
import com.qymusic.app.model.FavoritePlaylistModel
import com.qymusic.app.model.PlayListModel
import com.qymusic.app.model.ArtistModel
import org.litepal.LitePal
import org.litepal.extension.deleteAll
import org.litepal.extension.find
import org.litepal.extension.findAll
import org.litepal.extension.findFirst

/**
 * 本地收藏读写：歌曲 / 歌单 / 歌手。
 *
 * 歌曲沿用 [FavoriteModel]（type = TYPE_SONG）；歌单、歌手各自独立建表，
 * 避免三类 id 撞号时互相覆盖。
 */
object FavoriteRepository {

  suspend fun songs(): List<FavoriteModel> = withIO {
    LitePal.where("type = ?", FavoriteModel.TYPE_SONG.toString()).find<FavoriteModel>()
  }

  suspend fun playlists(): List<FavoritePlaylistModel> = withIO {
    runCatching { LitePal.findAll<FavoritePlaylistModel>() }.getOrDefault(emptyList())
  }

  suspend fun artists(): List<FavoriteArtistModel> = withIO {
    runCatching { LitePal.findAll<FavoriteArtistModel>() }.getOrDefault(emptyList())
  }

  // ------------------------------------------------------------------ 歌单

  suspend fun isPlaylistFavorite(playlistId: Long): Boolean = withIO {
    if (playlistId <= 0L) {
      return@withIO false
    }
    runCatching {
      LitePal.where("playlistId = ?", playlistId.toString())
        .findFirst<FavoritePlaylistModel>() != null
    }.getOrDefault(false)
  }

  /** @return 切换后是否已收藏 */
  suspend fun togglePlaylist(model: PlayListModel): Boolean = withIO {
    if (model.id <= 0L) {
      return@withIO false
    }
    runCatching {
    val existing = LitePal.where("playlistId = ?", model.id.toString())
      .findFirst<FavoritePlaylistModel>()
    if (existing != null) {
      LitePal.deleteAll<FavoritePlaylistModel>("playlistId = ?", model.id.toString())
      false
    } else {
      FavoritePlaylistModel(
        playlistId = model.id,
        name = model.name,
        cover = model.img.ifBlank { model.img700 },
        author = model.uname.ifBlank { model.userName },
        desc = model.desc,
        source = SPConfig.catalogSource(),
      ).save()
      true
    }
    }.getOrDefault(false)
  }

  // ------------------------------------------------------------------ 歌手

  suspend fun isArtistFavorite(artistId: Long): Boolean = withIO {
    if (artistId <= 0L) {
      return@withIO false
    }
    runCatching {
      LitePal.where("artistId = ?", artistId.toString())
        .findFirst<FavoriteArtistModel>() != null
    }.getOrDefault(false)
  }

  /** @return 切换后是否已收藏 */
  suspend fun toggleArtist(model: ArtistModel): Boolean = withIO {
    if (model.id <= 0L) {
      return@withIO false
    }
    runCatching {
    val existing = LitePal.where("artistId = ?", model.id.toString())
      .findFirst<FavoriteArtistModel>()
    if (existing != null) {
      LitePal.deleteAll<FavoriteArtistModel>("artistId = ?", model.id.toString())
      false
    } else {
      FavoriteArtistModel(
        artistId = model.id,
        name = model.name,
        cover = model.pic300.ifBlank { model.pic },
        source = SPConfig.catalogSource(),
      ).save()
      true
    }
    }.getOrDefault(false)
  }
}
