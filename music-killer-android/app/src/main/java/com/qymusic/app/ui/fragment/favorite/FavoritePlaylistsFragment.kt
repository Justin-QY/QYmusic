package com.qymusic.app.ui.fragment.favorite

import android.os.Bundle
import android.view.View
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.drake.brv.utils.models
import com.drake.brv.utils.setup
import kotlinx.coroutines.launch
import com.qymusic.app.R
import com.qymusic.app.databinding.FragmentFavoritePlaylistsBinding
import com.qymusic.app.model.PlayListModel
import com.qymusic.app.utils.FavoriteRepository
import com.qymusic.app.utils.preserveScrollState

/**
 * 收藏歌单分页：点击进入歌单详情。
 */
class FavoritePlaylistsFragment : Fragment(R.layout.fragment_favorite_playlists) {

  private val binding get() = FragmentFavoritePlaylistsBinding.bind(requireView())

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)

    binding.rv.setup {
      addType<PlayListModel>(R.layout.item_playlist)

      R.id.item_playlist.onClick {
        val model = getModel<PlayListModel>()
        findNavController().navigate(
          R.id.playlistInfoFragment,
          bundleOf("playlist" to model),
        )
      }
    }

    binding.rv.preserveScrollState("favorite_playlists")
  }

  override fun onResume() {
    super.onResume()
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
}
