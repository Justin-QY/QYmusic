package com.qymusic.app.ui.fragment

import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.core.view.MenuProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.drake.engine.adapter.FragmentAdapter
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.qymusic.app.R
import com.qymusic.app.base.BaseMusicAppbarFragment
import com.qymusic.app.databinding.FragmentHomeBinding
import com.qymusic.app.databinding.LayoutTabBinding
import com.qymusic.app.ui.fragment.play.PlayQueueDialogFragment
import com.qymusic.app.ui.fragment.artist.ArtistFragment
import com.qymusic.app.ui.fragment.playlist.PlayListFragment
import com.qymusic.app.ui.fragment.rank.RankFragment
import com.qymusic.app.ui.fragment.recommend.RecommendFragment
import com.qymusic.app.utils.SleepTimerDialog

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
    // 先只保留相邻页，避免冷启动瞬间同时建 4 个 Tab、发 4 路请求
    binding.vp.offscreenPageLimit = 1
    binding.vp.isSaveEnabled = true

    val titles = arrayOf("推荐", "歌单", "排行榜", "歌手")
    TabLayoutMediator(tab, binding.vp) { tab, position ->
      tab.text = titles[position]
    }.attach()

    preloadOtherTabs()
  }

  /**
   * 首屏绘制完成后再分两批把其余 Tab 预建好。
   *
   * ViewPager2 的 offscreenPageLimit 是"常驻页数"：等首批页面稳定后放开限制，
   * 四个 Tab 就会一直活着，之后切换不再重建、不再重新请求，做到秒开；
   * 而冷启动阶段仍然只有推荐页，不会和首屏抢主线程和网络。
   *
   * 用 viewLifecycleOwner 的协程，页面销毁时自动取消，不会留下延迟任务。
   */
  private fun preloadOtherTabs() {
    viewLifecycleOwner.lifecycleScope.launch {
      delay(350)
      // 先建相邻的「歌单」「排行榜」
      binding.vp.offscreenPageLimit = 2
      delay(1100)
      // 再建「歌手」，四个 Tab 从此全部常驻
      binding.vp.offscreenPageLimit = 5
    }
  }

  override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
    menuInflater.inflate(R.menu.menu_home, menu)
  }

  override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
    when (menuItem.itemId) {
      R.id.search -> {
        navController.navigate(HomeFragmentDirections.actionSearch())
      }

      R.id.sleep_timer -> {
        SleepTimerDialog.show(requireContext())
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