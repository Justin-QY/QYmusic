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
import com.qymusic.app.databinding.FragmentFavoriteArtistsBinding
import com.qymusic.app.model.ArtistModel
import com.qymusic.app.utils.FavoriteRepository
import com.qymusic.app.utils.preserveScrollState

/**
 * 收藏歌手分页：点击进入歌手详情。
 */
class FavoriteArtistsFragment : Fragment(R.layout.fragment_favorite_artists) {

  private val binding get() = FragmentFavoriteArtistsBinding.bind(requireView())

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)

    binding.rv.setup {
      addType<ArtistModel>(R.layout.item_grid_artist)

      R.id.item_artist.onClick {
        val model = getModel<ArtistModel>()
        findNavController().navigate(
          R.id.artistInfoFragment,
          bundleOf("artist" to model),
        )
      }
    }

    binding.rv.preserveScrollState("favorite_artists")
  }

  override fun onResume() {
    super.onResume()
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
}
