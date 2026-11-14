package com.qymusic.app.ui.fragment.artist

import com.drake.brv.utils.models
import com.drake.net.Get
import com.qymusic.app.R
import com.qymusic.app.base.BaseMusicNavFragment
import com.qymusic.app.config.Api
import com.qymusic.app.databinding.FragmentArtistMusicBinding
import com.qymusic.app.model.MusicModel
import com.qymusic.app.model.Page
import com.qymusic.app.model.setupMusicRv
import com.qymusic.app.utils.addWithData
import com.qymusic.app.utils.onLoad

class ArtistMusicFragment :
  BaseMusicNavFragment<FragmentArtistMusicBinding>(R.layout.fragment_artist_music) {

  private val args get() = (requireParentFragment() as ArtistInfoFragment).args

  override fun openMediaController(): Boolean {
    return true
  }

  override fun getMusicModels(): List<Any?>? {
    return binding.rvArtistMusic.models
  }

  override fun initView() {
    /*binding.page.onRefresh {
      scope {
        val result = Get<Page<MusicModel>>(Api.catalog(Api.ARTIST_MUSIC)) {
          addQuery("artistId", args.artist.id)
          addQuery("pageNo", index)
          addQuery("pageSize", 20)
        }.await()

        addData(result.data.onEach {
          mainViewModel.getMusicModelState(it)
        }) {
          result.total > modelCount
        }
      }
    }.showLoading()*/

    binding.rvArtistMusic.setupMusicRv(this)

    binding.page.onLoad(this) { page ->
      val result = Get<Page<MusicModel>>(Api.catalog(Api.ARTIST_MUSIC)) {
        addQuery("artistId", args.artist.id)
        addQuery("pageNo", page.index)
        addQuery("pageSize", 20)
      }.await()
      page.addWithData(result.data) {
        result.total > modelCount
      }
    }
  }

  override fun initData() {

  }
}