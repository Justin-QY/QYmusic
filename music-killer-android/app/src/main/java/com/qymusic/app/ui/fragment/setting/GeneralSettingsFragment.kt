package com.qymusic.app.ui.fragment.setting

import android.graphics.Color
import androidx.appcompat.app.AppCompatDelegate
import androidx.navigation.fragment.findNavController
import com.drake.tooltip.toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.qymusic.app.R
import com.qymusic.app.base.BaseMusicAppbarFragment
import com.qymusic.app.config.NavModules
import com.qymusic.app.config.SPConfig
import com.qymusic.app.databinding.FragmentGeneralSettingsBinding

/**
 * 通用设置（设置 → 通用设置）。
 *
 * - 主题：跟随系统 / 浅色 / 深色，写入 [SPConfig.THEME_MODE] 并统一用
 *   [AppCompatDelegate.setDefaultNightMode] 应用，设置页不再单独持有主题逻辑；
 * - 默认启动页：写入 [SPConfig.START_PAGE]，由 [NavModules.startPage] 过滤隐藏模块，
 *   MainActivity 冷启动后跳转。
 *
 * 所有配置都在 SPConfig，页面不持有状态，onResume 重新同步一次。
 */
class GeneralSettingsFragment :
  BaseMusicAppbarFragment<FragmentGeneralSettingsBinding>(R.layout.fragment_general_settings) {

  companion object {

    /** 歌词字号标签：小 / 标准 / 大 / 特大 */
    fun lyricSizeLabel(): String = when (SPConfig.lyricFontSize()) {
      SPConfig.LYRIC_FONT_SIZE_SMALL -> "小"
      SPConfig.LYRIC_FONT_SIZE_NORMAL -> "标准"
      SPConfig.LYRIC_FONT_SIZE_LARGE -> "大"
      SPConfig.LYRIC_FONT_SIZE_XLARGE -> "特大"
      else -> "自定义"
    }

    /** 歌词设置入口摘要：字号 + 翻译 + 超级岛，一眼看出当前配置 */
    fun lyricSummary(): String {
      val sizeLabel = lyricSizeLabel()
      val translation = if (SPConfig.isLyricTranslationEnabled()) "翻译开" else "翻译关"
      val island = if (SPConfig.isLyricSuperIslandEnabled()) "超级岛开" else "超级岛关"
      return "字号$sizeLabel · $translation · $island"
    }

    /** 设置页入口右侧摘要：主题 · 启动页 · 歌词字号 */
    fun summary(): String =
      "歌词${lyricSizeLabel()}"

    /** 底部导航入口摘要：当前显示在底栏的模块数 */
    fun navSummary(): String = "${NavModules.visible().size} 个模块显示在底栏，点击管理"
  }

  /** 同步 UI 时屏蔽控件回调，避免把默认值写回 SPConfig */
  private var syncing = false

  override fun initView() {
    // 与设置页一致的磨砂玻璃背景 + 透明 AppBar
    baseContentBinding.root.setBackgroundResource(R.drawable.bg_settings_glass)
    baseContentBinding.appbar.setBackgroundColor(Color.TRANSPARENT)
    baseContentBinding.toolbar.setBackgroundColor(Color.TRANSPARENT)

    binding.itemLyricSettings.setOnClickListener {
      findNavController().navigate(R.id.action_general_lyrics)
    }
    binding.itemNavManager.setOnClickListener {
      findNavController().navigate(R.id.action_general_nav)
    }
  }

  override fun initData() {
  }

  override fun onResume() {
    super.onResume()
    syncUi()
  }

  private fun syncUi() {
    syncing = true

    binding.tvLyricSettingsState.text = lyricSummary()

    binding.tvNavManagerState.text = navSummary()

    syncing = false
  }

}
