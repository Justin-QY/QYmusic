package com.qymusic.app.ui.fragment.playlist

import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.drake.brv.utils.models
import com.drake.net.Get
import com.drake.tooltip.toast
import kotlinx.coroutines.launch
import com.qymusic.app.R
import com.qymusic.app.base.BaseMusicNavFragment
import com.qymusic.app.config.Api
import com.qymusic.app.databinding.FragmentPlaylistInfoBinding
import com.qymusic.app.model.PlayListModel
import com.qymusic.app.model.QueryPlayListParams
import com.qymusic.app.model.setupMusicRv
import com.qymusic.app.utils.FavoriteRepository
import com.qymusic.app.utils.addWithData
import com.qymusic.app.utils.onLoad
import com.qymusic.app.utils.query

/**
 * 歌单信息
 */
class PlayListInfoFragment :
  BaseMusicNavFragment<FragmentPlaylistInfoBinding>(R.layout.fragment_playlist_info) {

  override fun openMediaController() = true

  private var currentPlaylist: PlayListModel? = null

  override fun initData() {
    val args = PlayListInfoFragmentArgs.fromBundle(requireArguments())

    binding.page.onLoad(this) { page ->
      val playlist = Get<PlayListModel>(Api.catalog(Api.PLAYLIST_INFO)) {
        query(QueryPlayListParams(pageNo = page.index, pageSize = 30, pid = args.playlist.id))
      }.await()

      binding.m = playlist
      currentPlaylist = playlist
      refreshFavoriteState()

      page.addWithData(playlist.musicList) {
        playlist.total > modelCount
      }
    }
  }

  override fun getMusicModels() = binding.playlistRv.models

  override fun initView() {
    setAppbar(binding.toolbar, binding.collapsingToolbarLayout)

    binding.playlistRv.setupMusicRv(this)

    binding.btnPlay.setOnClickListener {
      addPlaylist((binding.playlistRv.models) ?: emptyList())
    }

    binding.btnFavorite.setOnClickListener {
      val playlist = currentPlaylist ?: return@setOnClickListener
      lifecycleScope.launch {
        val favorite = FavoriteRepository.togglePlaylist(playlist)
        applyFavoriteState(favorite)
        toast(if (favorite) "已收藏歌单" else "已取消收藏")
      }
    }
  }

  override fun onResume() {
    super.onResume()
    refreshFavoriteState()
  }

  override fun onStart() {
    super.onStart()
    lightStatusBar(false)
  }

  override fun onStop() {
    super.onStop()
    lightStatusBar(true)
  }

  private fun refreshFavoriteState() {
    val playlist = currentPlaylist ?: return
    lifecycleScope.launch {
      applyFavoriteState(FavoriteRepository.isPlaylistFavorite(playlist.id))
    }
  }

  private fun applyFavoriteState(favorite: Boolean) {
    binding.btnFavorite.apply {
      setIconResource(
        if (favorite) R.drawable.baseline_favorite_24 else R.drawable.baseline_favorite_border_24
      )
      text = if (favorite) "已收藏" else "收藏"
    }
  }

  private fun lightStatusBar(isLight: Boolean = true) {
    val insetsController = WindowCompat.getInsetsController(requireActivity().window, binding.root)
    insetsController.isAppearanceLightStatusBars = isLight
  }
}