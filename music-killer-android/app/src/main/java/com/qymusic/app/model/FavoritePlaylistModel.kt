package com.qymusic.app.model

import androidx.databinding.PropertyChangeRegistry
import com.drake.engine.databinding.ObservableImpl
import org.litepal.annotation.Column
import org.litepal.crud.LitePalSupport

/**
 * 本地收藏的歌单。
 *
 * 和歌曲收藏分表存储，避免 musicId / playlistId 撞号时误删。
 */
data class FavoritePlaylistModel(
  /** 歌单 id */
  @Column(nullable = false, unique = true)
  val playlistId: Long = 0,

  val name: String = "",

  val cover: String = "",

  /** 歌单作者 */
  val author: String = "",

  val desc: String = "",

  /** 音源平台 */
  val source: String = "wy",

  val createAt: Long = System.currentTimeMillis(),
) : LitePalSupport(), ObservableImpl {

  override val registry: PropertyChangeRegistry = PropertyChangeRegistry()

  /** LitePal 自增主键 */
  val id: Long = 0
}
