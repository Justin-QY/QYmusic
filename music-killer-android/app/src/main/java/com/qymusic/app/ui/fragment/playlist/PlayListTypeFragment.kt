package com.qymusic.app.ui.fragment.playlist

import android.os.Bundle
import androidx.navigation.fragment.navArgs
import com.drake.brv.annotaion.DividerOrientation
import com.drake.brv.utils.divider
import com.drake.brv.utils.setup
import com.drake.net.Get
import com.qymusic.app.R
import com.qymusic.app.base.BaseMusicAppbarFragment
import com.qymusic.app.config.Api
import com.qymusic.app.databinding.FragmentPlaylistTypeBinding
import com.qymusic.app.model.Page
import com.qymusic.app.model.PlayListModel
import com.qymusic.app.model.QueryPlayListParams
import com.qymusic.app.ui.fragment.HomeFragmentDirections
import com.qymusic.app.utils.addWithData
import com.qymusic.app.utils.onLoad
import com.qymusic.app.utils.query
import com.qymusic.app.utils.removeAllItemDecorator

class PlayListTypeFragment :
  BaseMusicAppbarFragment<FragmentPlaylistTypeBinding>(R.layout.fragment_playlist_type) {

  override fun isAddScrollView(): Boolean {
    return false
  }

  private val args by navArgs<PlayListTypeFragmentArgs>()

  // private val mData = mutableListOf<PlayListModel>()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    navController.currentDestination?.label = args.playListTag.name
  }

  override fun initView() {
    /*binding.page.onRefresh {
      scope {
        val result = Get<Page<PlayListModel>>(Api.catalog(Api.TAG_PLAYLIST_PAGE)) {
          query(QueryPlayListParams(id = args.playListTag.id, pageNo = index, pageSize = 21, order = "new"))
        }.await()
        if (index == 1) {
          mData.clear()
        }
        pageNo = index
        mData.addAll(result.data)
        addData(result.data) {
          result.total > modelCount
        }
      }
    }*/

    binding.rvPlaylist.removeAllItemDecorator()
      .divider {
        setDivider(16, true)
        includeVisible = true
        orientation = DividerOrientation.GRID
      }
      .setup {
        addType<PlayListModel>(R.layout.item_playlist)

        R.id.item_playlist.onClick {
          navController.navigate(HomeFragmentDirections.actionPlaylistInfo(getModel()))
        }
      }

    binding.page.onLoad(this) { page ->
      val result = Get<Page<PlayListModel>>(Api.catalog(Api.TAG_PLAYLIST_PAGE)) {
        query(
          QueryPlayListParams(
            id = args.playListTag.id,
            pageNo = page.index,
            pageSize = 21,
            order = "new"
          )
        )
      }.await()

      page.addWithData(result.data) {
        result.total > modelCount
      }
    }
  }

  override fun initData() {
    /*if (mData.isEmpty()) {
      binding.page.showLoading()
    } else {
      binding.page.index = pageNo
      binding.page.addData(mData.toMutableList())
    }*/
  }
}