package com.qymusic.app.ui.fragment.favorite

import com.drake.engine.adapter.FragmentAdapter
import com.google.android.material.tabs.TabLayoutMediator
import com.qymusic.app.R
import com.qymusic.app.base.BaseMusicAppbarFragment
import com.qymusic.app.databinding.FragmentFavoriteAllBinding

/**
 * 全部收藏：歌曲 / 歌单 / 歌手 三个分页。
 *
 * 入口在「我的收藏」页右上角，点击后进入这个独立页面。
 */
class FavoriteAllFragment :
  BaseMusicAppbarFragment<FragmentFavoriteAllBinding>(R.layout.fragment_favorite_all) {

  private val titles = arrayOf("歌曲", "歌单", "歌手")

  override fun isAddScrollView(): Boolean = false

  override fun initView() {
    binding.vp.offscreenPageLimit = 2
    binding.vp.adapter = FragmentAdapter(
      listOf(
        FavoriteSongsFragment(),
        FavoritePlaylistsFragment(),
        FavoriteArtistsFragment(),
      )
    )
    TabLayoutMediator(binding.tab.tabLayout, binding.vp) { tab, position ->
      tab.text = titles.getOrNull(position) ?: ""
    }.attach()
  }

  override fun initData() {
  }
}
