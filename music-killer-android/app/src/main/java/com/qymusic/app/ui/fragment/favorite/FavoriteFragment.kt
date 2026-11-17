package com.qymusic.app.ui.fragment.favorite

import com.drake.net.utils.withIO
import org.litepal.LitePal
import org.litepal.extension.findAll
import com.qymusic.app.R
import com.qymusic.app.base.BaseMusicAppbarFragment
import com.qymusic.app.databinding.FragmentFavoriteBinding
import com.qymusic.app.model.FavoriteModel
import com.qymusic.app.model.MusicModel
import com.qymusic.app.model.setupMusicRv
import com.qymusic.app.utils.onLoad

class FavoriteFragment :
  BaseMusicAppbarFragment<FragmentFavoriteBinding>(R.layout.fragment_favorite) {

  override fun openMediaController(): Boolean {
    return true
  }

  override fun isAddScrollView(): Boolean {
    return false
  }

  override fun initView() {
    binding.rvFavorite.setupMusicRv(this)

    binding.page.onLoad(this) { page ->
      withIO {
        LitePal.findAll<FavoriteModel>().map {
          MusicModel(
            name = it.name,
            pic = it.cover,
            hasLossless = it.hasLossless,
            id = it.musicId,
            artist = it.author,
            artistId = it.authorId,
            duration = it.duration,
            source = it.source ?: "wy"
          )
        }
      }.also {
        page.addData(it)
      }
    }
  }

  override fun initData() {

  }
}