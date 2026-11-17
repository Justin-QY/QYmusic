package com.qymusic.app.ui.fragment.search

import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.drake.brv.utils.models
import com.drake.brv.utils.setup
import com.drake.net.Get
import com.drake.net.utils.scope
import kotlinx.coroutines.launch
import com.qymusic.app.R
import com.qymusic.app.base.BaseMusicNavFragment
import com.qymusic.app.config.Api
import com.qymusic.app.config.SPConfig
import com.qymusic.app.databinding.FragmentSearchMusicListBinding
import com.qymusic.app.model.MusicModel
import com.qymusic.app.model.Page
import com.qymusic.app.model.setupMusicRv

/**
 * 歌曲搜索结果列表，支持在酷狗 / 网易云 / QQ 音乐之间切换
 */
class SearchMusicListFragment :
        BaseSearchListFragment<FragmentSearchMusicListBinding>(R.layout.fragment_search_music_list) {

  /** 当前搜索音源：wy=网易云，qq=QQ音乐，kg=酷狗 */
  private var searchSource = when (SPConfig.catalogSource()) {
    SPConfig.SOURCE_KUGOU -> SPConfig.SOURCE_KUGOU
    SPConfig.SOURCE_QQ -> SPConfig.SOURCE_QQ
    else -> SPConfig.SOURCE_NETEASE
  }

  override fun getMusicModels(): List<Any?>? {
    return binding.rvMusic.models
  }

  override fun initView() {
    // 默认跟随设置页的「曲库音源」
    binding.toggleSource.check(
      when (searchSource) {
        SPConfig.SOURCE_KUGOU -> R.id.btn_source_kw
        SPConfig.SOURCE_QQ -> R.id.btn_source_qq
        else -> R.id.btn_source_wy
      }
    )

    binding.toggleSource.addOnButtonCheckedListener { _, checkedId, isChecked ->
      if (!isChecked) {
        return@addOnButtonCheckedListener
      }
      val source = when (checkedId) {
        R.id.btn_source_kw -> SPConfig.SOURCE_KUGOU
        R.id.btn_source_qq -> SPConfig.SOURCE_QQ
        else -> SPConfig.SOURCE_NETEASE
      }
      if (source == searchSource) {
        return@addOnButtonCheckedListener
      }
      searchSource = source
      // 搜索页切换后同步到设置页，下次进搜索默认还是这个音源
      SPConfig.setCatalogSource(source)
      mData.clear()
      pageNo = 1
      binding.page.index = 1
      binding.page.refresh()
    }

    binding.page.onRefresh {
      scope {
        // 三条曲库的歌曲搜索接口路径并不统一，QQ 走 /qq/search
        val url = when (searchSource) {
          SPConfig.SOURCE_KUGOU -> Api.SEARCH_KUGOU
          SPConfig.SOURCE_QQ -> Api.SEARCH_QQ
          else -> Api.SEARCH_NETEASE
        }
        val result = Get<Page<MusicModel>>(url) {
          addQuery("keyword", keyword)
          addQuery("pageNo", index)
          addQuery("pageSize", 20)
        }.await()
        addData(result.data.onEach {
          mainViewModel.getMusicModelState(it)
        }) {
          result.total > modelCount
        }
        result.page = index
        addPage(result)
      }
    }

    binding.rvMusic.setupMusicRv(this)

    if (mData.isNotEmpty()) {
      binding.page.index = pageNo
      binding.rvMusic.models = mData.toMutableList()
    }
  }

  override fun onSearch() {
    binding.page.showLoading()
  }
}
