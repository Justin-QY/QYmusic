package com.qymusic.app.ui.fragment.playlist

import androidx.core.view.WindowCompat
import com.drake.brv.utils.models
import com.drake.net.Get
import com.drake.tooltip.toast
import com.qymusic.app.R
import com.qymusic.app.base.BaseMusicNavFragment
import com.qymusic.app.config.Api
import com.qymusic.app.databinding.FragmentPlaylistInfoBinding
import com.qymusic.app.model.PlayListModel
import com.qymusic.app.model.QueryPlayListParams
import com.qymusic.app.model.setupMusicRv
import com.qymusic.app.utils.addWithData
import com.qymusic.app.utils.onLoad
import com.qymusic.app.utils.query

/**
 * 歌单信息
 */
class PlayListInfoFragment :
  BaseMusicNavFragment<FragmentPlaylistInfoBinding>(R.layout.fragment_playlist_info) {

  override fun openMediaController() = true

  override fun initData() {
    val args = PlayListInfoFragmentArgs.fromBundle(requireArguments())

    binding.page.onLoad(this) { page ->
      val playlist = Get<PlayListModel>(Api.catalog(Api.PLAYLIST_INFO)) {
        query(QueryPlayListParams(pageNo = page.index, pageSize = 30, pid = args.playlist.id))
      }.await()

      binding.m = playlist

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
      toast("还没做")
    }
  }

  override fun onStart() {
    super.onStart()
    lightStatusBar(false)
  }

  override fun onStop() {
    super.onStop()
    lightStatusBar(true)
  }

  private fun lightStatusBar(isLight: Boolean = true) {
    val insetsController = WindowCompat.getInsetsController(requireActivity().window, binding.root)
    insetsController.isAppearanceLightStatusBars = isLight
  }
}