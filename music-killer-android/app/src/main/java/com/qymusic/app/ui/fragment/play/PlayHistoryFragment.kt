package com.qymusic.app.ui.fragment.play

import com.drake.net.utils.withIO
import org.litepal.LitePal
import org.litepal.extension.find
import com.qymusic.app.R
import com.qymusic.app.base.BaseMusicAppbarFragment
import com.qymusic.app.databinding.FragmentPlayHistoryBinding
import com.qymusic.app.model.PlayHistory
import com.qymusic.app.model.setupMusicRv
import com.qymusic.app.utils.addWithData
import com.qymusic.app.utils.onLoad

class PlayHistoryFragment :
  BaseMusicAppbarFragment<FragmentPlayHistoryBinding>(R.layout.fragment_play_history) {

  override fun openMediaController(): Boolean {
    return true
  }

  override fun isAddScrollView(): Boolean {
    return false
  }

  override fun initView() {
    binding.rvPlay.setupMusicRv(this)

    binding.page.onLoad(this) { page ->

      val data = withIO {
        LitePal.order("updateTime desc")
          .offset((page.index - 1) * 20)
          .limit(20)
          .find<PlayHistory>().map { history ->
            history.toMusicModel()
          }
      }

      page.addWithData(data)
    }
  }

  override fun initData() {

  }
}