package com.qymusic.app.ui.fragment.artist

import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.navArgs
import com.drake.engine.adapter.FragmentAdapter
import com.drake.tooltip.toast
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.launch
import com.qymusic.app.R
import com.qymusic.app.base.BaseMusicNavFragment
import com.qymusic.app.databinding.FragmentArtistInfoBinding
import com.qymusic.app.utils.FavoriteRepository
import kotlin.math.abs

class ArtistInfoFragment : BaseMusicNavFragment<FragmentArtistInfoBinding>(R.layout.fragment_artist_info) {

  val args by navArgs<ArtistInfoFragmentArgs>()

  override fun initView() {
    setAppbar(binding.toolbar, binding.toolbarLayout)

    binding.vp.adapter = FragmentAdapter(listOf(ArtistMusicFragment()))
    TabLayoutMediator(binding.tab.tabLayout, binding.vp) { tab, position ->
      tab.text = "单曲(${args.artist.musicNum})"
    }.attach()

    var isExpanded = true
    binding.appbar.addOnOffsetChangedListener { appbar, offset ->
      if (abs(offset) >= appbar.totalScrollRange - 50) {
        if (isExpanded) {
          // 折叠
          isExpanded = false
          binding.toolbar.title = args.artist.name
        }
      } else {
        if (!isExpanded) {
          isExpanded = true
          binding.toolbar.title = " "
        }
      }
    }

    setupFavoriteMenu()
  }

  override fun initData() {
    binding.m = args.artist
    binding.lifecycleOwner = this
  }

  override fun onResume() {
    super.onResume()
    refreshFavoriteState()
  }

  // ------------------------------------------------------------------ 收藏歌手

  private fun setupFavoriteMenu() {
    binding.toolbar.inflateMenu(R.menu.menu_artist_favorite)
    binding.toolbar.setOnMenuItemClickListener { item ->
      if (item.itemId != R.id.action_artist_favorite) {
        return@setOnMenuItemClickListener false
      }
      viewLifecycleOwner.lifecycleScope.launch {
        val favorite = FavoriteRepository.toggleArtist(args.artist)
        applyFavoriteState(favorite)
        toast(if (favorite) "已收藏歌手" else "已取消收藏")
      }
      true
    }
  }

  private fun refreshFavoriteState() {
    viewLifecycleOwner.lifecycleScope.launch {
      applyFavoriteState(FavoriteRepository.isArtistFavorite(args.artist.id))
    }
  }

  private fun applyFavoriteState(favorite: Boolean) {
    binding.toolbar.menu.findItem(R.id.action_artist_favorite)?.apply {
      setIcon(
        if (favorite) R.drawable.baseline_favorite_24 else R.drawable.baseline_favorite_border_24
      )
      title = if (favorite) "取消收藏" else "收藏歌手"
    }
  }
}
