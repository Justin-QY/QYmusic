package com.qymusic.app.ui.fragment.download

import com.drake.brv.utils.models
import com.drake.net.utils.withIO
import org.litepal.LitePal
import org.litepal.extension.find
import com.qymusic.app.R
import com.qymusic.app.base.BaseMusicNavFragment
import com.qymusic.app.databinding.FragmentDownloadedBinding
import com.qymusic.app.model.MusicModel
import com.qymusic.app.model.download.DownloadModel
import com.qymusic.app.model.setupMusicRv
import com.qymusic.app.utils.addWithData
import com.qymusic.app.utils.onLoad

class DownloadedFragment :
  BaseMusicNavFragment<FragmentDownloadedBinding>(R.layout.fragment_downloaded) {

  override fun openMediaController(): Boolean {
    return true
  }

  override fun onResume() {
    if (!isFirstResume) {
      binding.page.refresh()
    }
    super.onResume()
  }

  override fun initView() {
    binding.rvDownloaded.setupMusicRv(this)

    binding.page.onLoad(this) { page ->
      page.addWithData(withIO {
        LitePal.order("updateAt desc")
          .where("status = ?", DownloadModel.STATUS_COMPLETED.toString())
          .find<DownloadModel>()
          .map {
            MusicModel(
              pic = it.cover,
              name = it.name,
              id = it.musicId,
              artist = it.artist,
              localPath = it.localPath,
            )
          }
      })
    }.setEnableLoadMore(false)
  }

  override fun getMusicModels() = binding.rvDownloaded.models

  override fun initData() {

  }

}