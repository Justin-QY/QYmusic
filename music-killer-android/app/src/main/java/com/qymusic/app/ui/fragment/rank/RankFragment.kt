package com.qymusic.app.ui.fragment.rank

import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.drake.brv.utils.bindingAdapter
import com.drake.brv.utils.dividerSpace
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
import com.qymusic.app.databinding.FragmentRankBinding
import com.qymusic.app.databinding.ItemRankMenuBinding
import com.qymusic.app.model.MusicRankMenuItemModel
import com.qymusic.app.model.MusicRankMenuModel
import com.qymusic.app.ui.fragment.HomeFragmentDirections
import com.qymusic.app.utils.removeAllItemDecorator

/**
 * 排行榜
 */
class RankFragment : BaseMusicNavFragment<FragmentRankBinding>(R.layout.fragment_rank) {

  private val viewModel by viewModels<RankViewModel>()

  override fun onFirstResume() {
    super.onFirstResume()

    if (viewModel.uiState.value.isEmpty()) {
      binding.page.refresh()
    }
  }

  override fun initView() {
    binding.page.onRefresh {
      setEnableLoadMore(false)
      scope {
        var list = Get<List<MusicRankMenuModel>>(Api.catalog(Api.RANK_MENU)).await()
        if (list.isEmpty()) {
          // 服务端偶发返回空数据，稍等重试一次，避免页面直接显示“空空如也”
          delay(700)
          list = Get<List<MusicRankMenuModel>>(Api.catalog(Api.RANK_MENU)).await()
        }
        viewModel.updateState(list)
      }
    }

    binding.rvRank
      .setup {
        addType<MusicRankMenuModel>(R.layout.item_rank_menu)

        onCreate {
          getBinding<ItemRankMenuBinding>().rvMenu
            .setup {
              addType<MusicRankMenuItemModel>(R.layout.item_grid_rank_menu)

              R.id.item_rank.onClick {
                navController.navigate(HomeFragmentDirections.actionRankInfo(getModel()))
              }
            }
        }

        onBind {
          getBinding<ItemRankMenuBinding>().rvMenu.models = getModel<MusicRankMenuModel>().list
        }
      }
  }

  override fun initData() {
    viewLifecycleOwner.lifecycleScope.launch {
      viewModel.uiState.collect {
        binding.page.addData(it)
      }
    }
  }
}