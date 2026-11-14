package com.qymusic.app.ui.fragment.rank

import androidx.navigation.fragment.navArgs
import com.drake.brv.utils.models
import com.drake.net.Get
import com.qymusic.app.R
import com.qymusic.app.base.BaseMusicAppbarFragment
import com.qymusic.app.config.Api
import com.qymusic.app.databinding.FragmentRankInfoBinding
import com.qymusic.app.model.MusicRankModel
import com.qymusic.app.model.setupMusicRv
import com.qymusic.app.utils.addWithData
import com.qymusic.app.utils.onLoad

class RankInfoFragment: BaseMusicAppbarFragment<FragmentRankInfoBinding>(R.layout.fragment_rank_info) {

  private val args by navArgs<RankInfoFragmentArgs>()

  override fun isAddScrollView(): Boolean {
    return false
  }

  override fun openMediaController(): Boolean {
    return true
  }

  override fun getMusicModels(): List<Any?>? {
    return binding.rvRankMusic.models
  }

  override fun initView() {
    setTitle(args.rankItem.name)
    setSubTitle(args.rankItem.pub)

    binding.rvRankMusic.setupMusicRv(this)

    binding.page.onLoad(this) { page ->
      val result = Get<MusicRankModel>(Api.catalog(Api.RANK_MUSIC_LIST)) {
        addQuery("rankId", args.rankItem.sourceId)
        addQuery("pageNo", page.index)
        addQuery("pageSize", "20")
      }.await()
      page.addWithData(result.musicList) {
        result.num > modelCount
      }
    }
  }

  override fun initData() {

  }
}