package com.qymusic.app.ui.fragment.artist

import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.drake.brv.annotaion.DividerOrientation
import com.drake.brv.utils.divider
import com.drake.brv.utils.models
import com.drake.brv.utils.setup
import com.drake.engine.utils.dp
import com.drake.net.Get
import com.drake.net.utils.scope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.qymusic.app.R
import com.qymusic.app.base.BaseMusicNavFragment
import com.qymusic.app.config.Api
import com.qymusic.app.databinding.FragmentArtistBinding
import com.qymusic.app.model.ArtistModel
import com.qymusic.app.model.Page
import com.qymusic.app.ui.fragment.HomeFragmentDirections

class ArtistFragment: BaseMusicNavFragment<FragmentArtistBinding>(R.layout.fragment_artist) {

  private val viewModel by viewModels<ArtistViewModel>()

  override fun onFirstResume() {
    super.onFirstResume()

    if (viewModel.uiState.value.isEmpty()) {
      binding.page.refresh()
    }
  }

  override fun initView() {
    binding.page.onRefresh {
      scope {

        if (index == 1) {
          viewModel.clearState()
        }

        var result = Get<Page<ArtistModel>>(Api.catalog(Api.ARTIST_LIST)) {
          addQuery("pageNo", index)
          addQuery("pageSize", 20)
          addQuery("category", 0)
        }.await()

        if (result.data.isEmpty()) {
          // 上游偶发空数据，稍等重试一次
          delay(700)
          result = Get<Page<ArtistModel>>(Api.catalog(Api.ARTIST_LIST)) {
            addQuery("pageNo", index)
            addQuery("pageSize", 20)
            addQuery("category", 0)
          }.await()
        }

        addData(result.data) {
          result.total > modelCount
        }

        result.page = index
        viewModel.addData(result)
      }
    }

    binding.rvArtist.divider {
      setDivider(20, true)
      includeVisible = true
      orientation = DividerOrientation.GRID
    }.setup {
      addType<ArtistModel>(R.layout.item_grid_artist)

      R.id.item_artist.onClick {
        navController.navigate(HomeFragmentDirections.actionArtistInfo(getModel()))
      }
    }

    if (viewModel.uiState.value.isNotEmpty()) {
      binding.page.index = viewModel.pageNo
      binding.rvArtist.models = viewModel.uiState.value.toMutableList()
    }
  }

  override fun initData() {

  }
}