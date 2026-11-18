package com.qymusic.app.ui.fragment

import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.core.view.MenuProvider
import androidx.lifecycle.Lifecycle
import com.drake.engine.adapter.FragmentAdapter
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.qymusic.app.R
import com.qymusic.app.base.BaseMusicAppbarFragment
import com.qymusic.app.databinding.FragmentHomeBinding
import com.qymusic.app.databinding.LayoutTabBinding
import com.qymusic.app.ui.fragment.play.PlayQueueDialogFragment
import com.qymusic.app.ui.fragment.artist.ArtistFragment
import com.qymusic.app.ui.fragment.playlist.PlayListFragment
import com.qymusic.app.ui.fragment.rank.RankFragment
import com.qymusic.app.ui.fragment.recommend.RecommendFragment

class HomeFragment : BaseMusicAppbarFragment<FragmentHomeBinding>(R.layout.fragment_home),
  MenuProvider {

  private lateinit var tab: TabLayout

  override fun isAddScrollView(): Boolean {
    return false
  }

  override fun getAppbarContent(inflater: LayoutInflater): View? {
    tab = LayoutTabBinding.inflate(inflater).tabLayout
    return tab
  }

  override fun initData() {

  }

  override fun initView() {
    requireActivity().addMenuProvider(this, viewLifecycleOwner, Lifecycle.State.RESUMED)

    binding.vp.adapter = FragmentAdapter(listOf(
      RecommendFragment(), PlayListFragment(), RankFragment(), ArtistFragment(),
    ))
    binding.vp.offscreenPageLimit = 5
    binding.vp.isSaveEnabled = true

    val titles = arrayOf("推荐", "歌单", "排行榜", "歌手")
    TabLayoutMediator(tab, binding.vp) { tab, position ->
      tab.text = titles[position]
    }.attach()
  }

  override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
    menuInflater.inflate(R.menu.menu_home, menu)
  }

  override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
    when (menuItem.itemId) {
      R.id.search -> {
        navController.navigate(HomeFragmentDirections.actionSearch())
      }

      R.id.play_queue -> {
        showPlayQueue()
      }

      R.id.setting -> {
        navController.navigate(HomeFragmentDirections.actionSetting())
      }
    }
    return true
  }

  /**
   * 打开播放队列。队列弹窗是已有的 PlayQueueDialogFragment，这里只负责展示，
   * 不重复实现一套队列页面。
   */
  private fun showPlayQueue() {
    val fragmentManager = requireActivity().supportFragmentManager
    if (fragmentManager.findFragmentByTag(PLAY_QUEUE_TAG) != null) {
      return
    }
    PlayQueueDialogFragment().show(fragmentManager, PLAY_QUEUE_TAG)
  }

  private companion object {
    const val PLAY_QUEUE_TAG = "play_queue_dialog"
  }
}