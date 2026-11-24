package com.qymusic.app.ui.fragment.favorite

import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.drake.brv.utils.models
import kotlinx.coroutines.launch
import com.qymusic.app.R
import com.qymusic.app.base.BaseMusicNavFragment
import com.qymusic.app.databinding.FragmentFavoriteSongsBinding
import com.qymusic.app.model.MusicModel
import com.qymusic.app.model.setupMusicRv
import com.qymusic.app.utils.FavoriteRepository
import com.qymusic.app.utils.preserveScrollState

/**
 * 收藏歌曲分页：数据来自本地 FavoriteModel（type = 歌曲）。
 */
class FavoriteSongsFragment :
  BaseMusicNavFragment<FragmentFavoriteSongsBinding>(R.layout.fragment_favorite_songs) {

  override fun openMediaController(): Boolean = true

  override fun initView() {
    binding.rv.setupMusicRv(this)
    binding.rv.preserveScrollState("favorite_songs")
  }

  override fun initData() {
  }

  override fun onResume() {
    super.onResume()
    viewLifecycleOwner.lifecycleScope.launch {
      val songs = FavoriteRepository.songs().map {
        MusicModel(
          name = it.name,
          pic = it.cover,
          hasLossless = it.hasLossless,
          id = it.musicId,
          artist = it.author,
          artistId = it.authorId,
          duration = it.duration,
          source = it.source ?: "wy",
        )
      }
      // 本页全部是已收藏歌曲，只需回填「正在播放」状态，不再逐条查库
      val current = mainViewModel.musicModel.value
      songs.forEach {
        it.isSelected = it.id == current.id && it.source == current.source
        it.isFavorite = true
      }
      binding.rv.models = songs.toMutableList()
      binding.tvEmpty.isVisible = songs.isEmpty()
    }
  }
}
