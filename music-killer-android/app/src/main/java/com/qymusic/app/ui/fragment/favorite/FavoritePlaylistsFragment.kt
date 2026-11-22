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
import com.qymusic.app.databinding.FragmentFavoritePlaylistsBinding
import com.qymusic.app.model.PlayListModel
import com.qymusic.app.utils.FavoriteRepository
import com.qymusic.app.utils.preserveScrollState

/**
 * 收藏歌单分页：点击进入详情，长按可取消收藏或把歌单歌曲加入播放列表。
 */
class FavoritePlaylistsFragment :
  BaseMusicNavFragment<FragmentFavoritePlaylistsBinding>(R.layout.fragment_favorite_playlists) {

  override fun openMediaController(): Boolean = true

  override fun initView() {
    binding.rv.preserveScrollState("favorite_playlists")

    binding.rv.setup {
      addType<PlayListModel>(R.layout.item_playlist)

      R.id.item_playlist.onClick {
        val model = getModel<PlayListModel>()
        findNavController().navigate(
          R.id.playlistInfoFragment,
          bundleOf("playlist" to model),
        )
      }

      R.id.item_playlist.onLongClick {
        showActions(getModel<PlayListModel>())
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
      val playlists = FavoriteRepository.playlists().map {
        PlayListModel(
          id = it.id,
          name = it.name,
          img = it.cover,
          img700 = it.cover,
          uname = it.author,
          userName = it.author,
          desc = it.desc,
        )
      }
      binding.rv.models = playlists.toMutableList()
      binding.tvEmpty.isVisible = playlists.isEmpty()
    }
  }

  private fun showActions(model: PlayListModel) {
    MaterialAlertDialogBuilder(requireContext())
      .setTitle(model.name.ifBlank { "歌单" })
      .setItems(arrayOf("取消收藏", "加入播放列表")) { _, which ->
        when (which) {
          0 -> removeFavorite(model)
          1 -> addToQueue(model)
        }
      }
      .setNegativeButton("取消", null)
      .show()
  }

  private fun removeFavorite(model: PlayListModel) {
    viewLifecycleOwner.lifecycleScope.launch {
      FavoriteRepository.togglePlaylist(model)
      toast("已取消收藏")
      reload()
    }
  }

  private fun addToQueue(model: PlayListModel) {
    viewLifecycleOwner.lifecycleScope.launch {
      val songs = runCatching {
        Get<PlayListModel>(Api.catalog(Api.PLAYLIST_INFO)) {
          addQuery("pid", model.id)
          addQuery("pageNo", 1)
          addQuery("pageSize", 200)
        }.await().musicList
      }.getOrNull().orEmpty()

      if (songs.isEmpty()) {
        toast("歌单暂无可用歌曲")
        return@launch
      }
      addPlaylist(songs, clear = false)
      toast("已加入播放列表")
    }
  }
}
