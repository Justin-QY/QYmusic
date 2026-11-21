package com.qymusic.app.model

import androidx.databinding.PropertyChangeRegistry
import com.drake.engine.databinding.ObservableImpl
import org.litepal.annotation.Column
import org.litepal.crud.LitePalSupport

/**
 * 本地收藏的歌手。
 */
data class FavoriteArtistModel(
  /** 歌手 id */
  @Column(nullable = false, unique = true)
  val artistId: Long = 0,

  val name: String = "",

  val cover: String = "",

  /** 音源平台 */
  val source: String = "wy",

  val createAt: Long = System.currentTimeMillis(),
) : LitePalSupport(), ObservableImpl {

  override val registry: PropertyChangeRegistry = PropertyChangeRegistry()

  /** LitePal 自增主键 */
  val id: Long = 0
}
