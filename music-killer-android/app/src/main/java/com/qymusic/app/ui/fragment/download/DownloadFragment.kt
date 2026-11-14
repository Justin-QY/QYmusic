package com.qymusic.app.ui.fragment.download

import android.view.LayoutInflater
import android.view.View
import androidx.fragment.app.Fragment
import com.drake.engine.adapter.FragmentAdapter
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.qymusic.app.R
import com.qymusic.app.base.BaseMusicAppbarFragment
import com.qymusic.app.databinding.FragmentDownloadBinding
import com.qymusic.app.databinding.LayoutTabBinding
import com.qymusic.app.utils.DownloadHelper

class DownloadFragment :
  BaseMusicAppbarFragment<FragmentDownloadBinding>(R.layout.fragment_download) {

  private lateinit var tab: TabLayout

  override fun getAppbarContent(inflater: LayoutInflater): View? {
    tab = LayoutTabBinding.inflate(inflater).tabLayout
    return tab
  }

  override fun isAddScrollView(): Boolean {
    return false
  }

  override fun initView() {
    val titles = arrayOf("全部", "已下载")
    binding.vp.offscreenPageLimit = 2
    binding.vp.adapter = FragmentAdapter(
      listOf<Fragment>(
        DownloadListFragment.newInstance(
          DownloadListFragment.TYPE_ALL
        ),
        DownloadedFragment(),
      )
    )

    TabLayoutMediator(tab, binding.vp) {tab, position ->
      tab.text = titles[position]
    }.attach()

    binding.tvPath.text = "文件保存在: " + DownloadHelper.getDownloadDirectory(requireContext()).path
  }

  override fun initData() {

  }


}