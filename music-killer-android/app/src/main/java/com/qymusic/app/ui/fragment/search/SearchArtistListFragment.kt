package com.qymusic.app.ui.fragment.search

import com.drake.brv.annotaion.DividerOrientation
import com.drake.brv.utils.divider
import com.drake.brv.utils.models
import com.drake.brv.utils.setup
import com.drake.net.Get
import com.drake.net.utils.scope
import com.qymusic.app.R
import com.qymusic.app.config.Api
import com.qymusic.app.databinding.FragmentSearchArtistBinding
import com.qymusic.app.model.ArtistModel
import com.qymusic.app.model.Page

class SearchArtistListFragment: BaseSearchListFragment<FragmentSearchArtistBinding>(R.layout.fragment_search_artist) {

  override fun onSearch() {
    binding.page.showLoading()
  }

  override fun initView() {
    binding.page.onRefresh {
      scope {
        val result = Get<Page<ArtistModel>>(Api.catalog(Api.SEARCH_ARTIST)) {
          addQuery("keyword", keyword)
          addQuery("pageNo", index)
          addQuery("pageSize", 20)
        }.await()

        addData(result.data) {
          result.total > modelCount
        }

        result.page = index
        addPage(result)
      }
    }

    binding.rvArtist.divider {
      setDivider(16, true)
      includeVisible = true
      orientation = DividerOrientation.GRID
    }.setup {
      addType<ArtistModel>(R.layout.item_grid_artist)

      R.id.item_artist.onClick {
        navController.navigate(SearchMusicFragmentDirections.actionArtistInfo(getModel()))
      }
    }

    if (mData.isNotEmpty()) {
      binding.page.index = pageNo
      binding.rvArtist.models = mData.toMutableList()
    }
  }
}