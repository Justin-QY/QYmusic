package com.qymusic.app.ui.fragment.favorite

import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.drake.brv.utils.models
import com.drake.brv.utils.setup
import com.drake.net.Get
import com.drake.tooltip.toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import com.qymusic.app.R
import com.qymusic.app.base.BaseMusicNavFragment
import com.qymusic.app.config.Api
import com.qymusic.app.databinding.FragmentFavoriteArtistsBinding
import com.qymusic.app.model.ArtistModel
import com.qymusic.app.model.MusicModel
import com.qymusic.app.model.Page
import com.qymusic.app.utils.FavoriteRepository
import com.qymusic.app.utils.preserveScrollState

/**
 * 收藏歌手分页：点击进入详情，长按可取消收藏或把歌手歌曲加入播放列表。
 */
class FavoriteArtistsFragment :
  BaseMusicNavFragment<FragmentFavoriteArtistsBinding>(R.layout.fragment_favorite_artists) {

  override fun openMediaController(): Boolean = true

  override fun initView() {
    binding.rv.preserveScrollState("favorite_artists")

    binding.rv.setup {
      addType<ArtistModel>(R.layout.item_grid_artist)

      R.id.item_artist.onClick {
        val model = getModel<ArtistModel>()
        findNavController().navigate(
          R.id.artistInfoFragment,
          bundleOf("artist" to model),
        )
      }

      R.id.item_artist.onLongClick {
        showActions(getModel<ArtistModel>())
      }
    }
  }

  override fun initData() {
  }

  override fun onResume() {
    super.onResume()
    reload()
  }

  private fun reload() {
    viewLifecycleOwner.lifecycleScope.launch {
      val artists = FavoriteRepository.artists().map {
        ArtistModel(
          id = it.id,
          name = it.name,
          pic = it.cover,
          pic120 = it.cover,
          pic300 = it.cover,
          pic70 = it.cover,
        )
      }
      binding.rv.models = artists.toMutableList()
      binding.tvEmpty.isVisible = artists.isEmpty()
    }
  }

  private fun showActions(model: ArtistModel) {
    MaterialAlertDialogBuilder(requireContext())
      .setTitle(model.name.ifBlank { "歌手" })
      .setItems(arrayOf("取消收藏", "加入播放列表")) { _, which ->
        when (which) {
          0 -> removeFavorite(model)
          1 -> addToQueue(model)
        }
      }
      .setNegativeButton("取消", null)
      .show()
  }

  private fun removeFavorite(model: ArtistModel) {
    viewLifecycleOwner.lifecycleScope.launch {
      FavoriteRepository.toggleArtist(model)
      toast("已取消收藏")
      reload()
    }
  }

  private fun addToQueue(model: ArtistModel) {
    viewLifecycleOwner.lifecycleScope.launch {
      val songs = runCatching {
        Get<Page<MusicModel>>(Api.catalog(Api.ARTIST_MUSIC)) {
          addQuery("artistId", model.id)
          addQuery("pageNo", 1)
          addQuery("pageSize", 100)
        }.await().data
      }.getOrNull().orEmpty()

      if (songs.isEmpty()) {
        toast("歌手暂无可用歌曲")
        return@launch
      }
      if (_mediaController == null) {
        toast("播放器未就绪，请稍后再试")
        return@launch
      }
      addPlaylist(songs, clear = false)
      toast("已加入播放列表")
    }
  }
}
