package com.qymusic.app.ui.fragment.setting

import android.graphics.Color
import androidx.navigation.fragment.findNavController
import com.drake.tooltip.toast
import com.qymusic.app.R
import com.qymusic.app.base.BaseMusicAppbarFragment
import com.qymusic.app.config.SPConfig
import com.qymusic.app.databinding.FragmentInterfaceSettingsBinding

/**
 * 界面设置（设置 → 界面设置）。
 *
 * - 首页曲库：写入 [SPConfig.CATALOG_SOURCE] 后回到首页并强制重建，
 *   首页、排行榜、歌手、歌单、搜索与歌词统一按新曲库取数，播放不受影响；
 * - 曲库搜索：进入 [SearchSourceFragment] 配置聚合搜索与启用平台。
 *
 * 页面不持有状态，onResume 重新同步一次。
 */
class InterfaceSettingsFragment :
  BaseMusicAppbarFragment<FragmentInterfaceSettingsBinding>(R.layout.fragment_interface_settings) {

  companion object {

    /** 首页曲库名称：网易云 / QQ音乐 / 酷狗 */
    fun catalogLabel(): String = SPConfig.platformLabel(SPConfig.catalogSource())

    /** 聚合搜索开关标签 */
    fun aggregateSearchLabel(): String =
      if (SPConfig.isAggregateSearch()) "聚合搜索开" else "聚合搜索关"
  }

  override fun initView() {
    // 与设置页、通用设置一致的磨砂玻璃背景 + 透明 AppBar
    baseContentBinding.root.setBackgroundResource(R.drawable.bg_settings_glass)
    baseContentBinding.appbar.setBackgroundColor(Color.TRANSPARENT)
    baseContentBinding.toolbar.setBackgroundColor(Color.TRANSPARENT)

    initCatalogToggle()

    binding.itemSearchSource.setOnClickListener {
      findNavController().navigate(R.id.action_interface_search_source)
    }
  }

  override fun initData() {
  }

  override fun onResume() {
    super.onResume()
    updateCatalogButtons()
    binding.tvCatalogHint.text = "切换后首页、榜单、歌单、歌手与搜索一并换源，不影响播放。"
    binding.tvSearchSourceState.text =
      "${aggregateSearchLabel()} · ${SPConfig.enabledSearchPlatforms().size} 个平台"
  }

  // ------------------------------------------------------------------ 曲库

  private fun initCatalogToggle() {
    updateCatalogButtons()
    binding.btnCatalogWy.setOnClickListener { selectCatalog(SPConfig.SOURCE_NETEASE) }
    binding.btnCatalogQq.setOnClickListener { selectCatalog(SPConfig.SOURCE_QQ) }
    binding.btnCatalogKw.setOnClickListener { selectCatalog(SPConfig.SOURCE_KUGOU) }
  }

  private fun selectCatalog(source: String) {
    if (source == SPConfig.catalogSource()) {
      return
    }
    SPConfig.setCatalogSource(source)
    updateCatalogButtons()
    toast(
      when (source) {
        SPConfig.SOURCE_KUGOU -> "已切换到酷狗曲库，正在刷新首页"
        SPConfig.SOURCE_QQ -> "已切换到 QQ 音乐曲库，正在刷新首页"
        else -> "已切换到网易云曲库，正在刷新首页"
      }
    )
    reloadHomeWithNewCatalog()
  }

  private fun updateCatalogButtons() {
    val source = SPConfig.catalogSource()
    styleCatalogButton(binding.btnCatalogWy, source == SPConfig.SOURCE_NETEASE)
    styleCatalogButton(binding.btnCatalogQq, source == SPConfig.SOURCE_QQ)
    styleCatalogButton(binding.btnCatalogKw, source == SPConfig.SOURCE_KUGOU)
  }

  private fun styleCatalogButton(view: android.widget.TextView, selected: Boolean) {
    view.setBackgroundResource(
      if (selected) R.drawable.bg_segment_selected else android.R.color.transparent
    )
    val attr = if (selected) {
      com.google.android.material.R.attr.colorOnPrimary
    } else {
      com.google.android.material.R.attr.colorOnSurfaceVariant
    }
    view.setTextColor(com.google.android.material.color.MaterialColors.getColor(view, attr))
  }

  /**
   * 切换曲库后回到首页并强制重建，保证首页、榜单等页面按新音源重新拉取数据。
   */
  private fun reloadHomeWithNewCatalog() {
    try {
      val navOptions = androidx.navigation.NavOptions.Builder()
        .setPopUpTo(R.id.homeFragment, true)
        .setLaunchSingleTop(true)
        .build()
      findNavController().navigate(R.id.homeFragment, null, navOptions)
    } catch (e: Exception) {
      // 兜底：导航失败时至少重建 Activity
      try {
        requireActivity().recreate()
      } catch (ignored: Exception) {
      }
    }
  }
}
