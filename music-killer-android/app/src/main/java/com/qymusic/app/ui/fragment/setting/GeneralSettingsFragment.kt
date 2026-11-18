package com.qymusic.app.ui.fragment.setting

import android.graphics.Color
import androidx.appcompat.app.AppCompatDelegate
import com.drake.tooltip.toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.qymusic.app.R
import com.qymusic.app.base.BaseMusicAppbarFragment
import com.qymusic.app.config.AppLocale
import com.qymusic.app.config.NavModules
import com.qymusic.app.config.SPConfig
import com.qymusic.app.databinding.FragmentGeneralSettingsBinding

/**
 * 通用设置（设置 → 通用设置）。
 *
 * - 系统语言：跟随系统 / 简体中文 / English，写入 [SPConfig.APP_LANGUAGE] 后交给
 *   [AppLocale] 应用（API 33+ 走系统 per-app language，低版本 AppCompat 兜底）；
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

    fun languageLabel(language: String): String = when (language) {
      SPConfig.LANGUAGE_ZH -> "简体中文"
      SPConfig.LANGUAGE_EN -> "English"
      else -> "跟随系统"
    }

    fun themeLabel(mode: Int): String = when (mode) {
      AppCompatDelegate.MODE_NIGHT_NO -> "浅色"
      AppCompatDelegate.MODE_NIGHT_YES -> "深色"
      else -> "跟随系统"
    }

    fun startPageLabel(): String = NavModules.startPage().title

    /** 设置页入口右侧摘要：语言 · 主题 · 启动页 */
    fun summary(): String =
      "${languageLabel(SPConfig.appLanguage())} · ${themeLabel(SPConfig.themeMode())} · 启动${startPageLabel()}"
  }

  /** 同步 UI 时屏蔽控件回调，避免把默认值写回 SPConfig */
  private var syncing = false

  override fun initView() {
    // 与设置页一致的磨砂玻璃背景 + 透明 AppBar
    baseContentBinding.root.setBackgroundResource(R.drawable.bg_settings_glass)
    baseContentBinding.appbar.setBackgroundColor(Color.TRANSPARENT)
    baseContentBinding.toolbar.setBackgroundColor(Color.TRANSPARENT)

    binding.toggleLanguage.addOnButtonCheckedListener { _, checkedId, isChecked ->
      if (syncing || !isChecked) {
        return@addOnButtonCheckedListener
      }
      val language = when (checkedId) {
        R.id.btn_lang_zh -> SPConfig.LANGUAGE_ZH
        R.id.btn_lang_en -> SPConfig.LANGUAGE_EN
        else -> SPConfig.LANGUAGE_SYSTEM
      }
      if (language == SPConfig.appLanguage()) {
        return@addOnButtonCheckedListener
      }
      SPConfig.setAppLanguage(language)
      AppLocale.apply(language)
      syncUi()
      toast(
        if (language == SPConfig.LANGUAGE_SYSTEM) {
          "已切换为跟随系统，界面将自动重新加载"
        } else {
          "已切换语言，界面将自动重新加载；如未刷新请重启应用"
        }
      )
    }

    binding.toggleTheme.addOnButtonCheckedListener { _, checkedId, isChecked ->
      if (syncing || !isChecked) {
        return@addOnButtonCheckedListener
      }
      val mode = when (checkedId) {
        R.id.btn_theme_light -> AppCompatDelegate.MODE_NIGHT_NO
        R.id.btn_theme_dark -> AppCompatDelegate.MODE_NIGHT_YES
        else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
      }
      if (mode == SPConfig.themeMode()) {
        return@addOnButtonCheckedListener
      }
      SPConfig.setThemeMode(mode)
      // 系统会在深浅色真正变化时自动重建 Activity，这里不再重复 recreate()
      AppCompatDelegate.setDefaultNightMode(mode)
    }

    binding.itemStartPage.setOnClickListener { showStartPageDialog() }
  }

  override fun initData() {
  }

  override fun onResume() {
    super.onResume()
    syncUi()
  }

  private fun syncUi() {
    syncing = true

    val languageId = when (SPConfig.appLanguage()) {
      SPConfig.LANGUAGE_ZH -> R.id.btn_lang_zh
      SPConfig.LANGUAGE_EN -> R.id.btn_lang_en
      else -> R.id.btn_lang_system
    }
    if (binding.toggleLanguage.checkedButtonId != languageId) {
      binding.toggleLanguage.check(languageId)
    }
    binding.tvLanguageHint.text = buildLanguageHint()

    val themeId = when (SPConfig.themeMode()) {
      AppCompatDelegate.MODE_NIGHT_NO -> R.id.btn_theme_light
      AppCompatDelegate.MODE_NIGHT_YES -> R.id.btn_theme_dark
      else -> R.id.btn_theme_system
    }
    if (binding.toggleTheme.checkedButtonId != themeId) {
      binding.toggleTheme.check(themeId)
    }
    binding.tvThemeHint.text = "当前：${themeLabel(SPConfig.themeMode())}"

    binding.tvStartPageValue.text = "当前：${startPageLabel()} · 点击切换"

    syncing = false
  }

  /** 语言提示：没有英文资源的机型上明确说明「只保存配置」 */
  private fun buildLanguageHint(): String {
    return when (SPConfig.appLanguage()) {
      SPConfig.LANGUAGE_EN -> "当前：English · 英文翻译暂未全部完成，未翻译的界面仍显示中文"
      SPConfig.LANGUAGE_ZH -> "当前：简体中文"
      else -> "当前：跟随系统 · 由系统语言决定界面语言"
    }
  }

  /**
   * 启动页候选取「当前启用的 NavModules」，与底栏保持一致；
   * 已隐藏的模块不会出现在候选里，已保存的选择被隐藏时由 NavModules.startPage() 回落。
   */
  private fun showStartPageDialog() {
    if (!isAdded) {
      return
    }
    val modules = NavModules.visible().ifEmpty { NavModules.ALL }
    if (modules.isEmpty()) {
      return
    }
    val labels = modules.map { it.title }.toTypedArray()
    val checked = modules.indexOfFirst { it.key == NavModules.startPage().key }
      .coerceAtLeast(0)

    MaterialAlertDialogBuilder(requireContext())
      .setTitle("默认启动页")
      .setSingleChoiceItems(labels, checked) { dialog, which ->
        val module = modules.getOrNull(which) ?: return@setSingleChoiceItems
        SPConfig.setStartPageKey(module.key)
        dialog.dismiss()
        syncUi()
        toast("下次启动将直接打开${module.title}")
      }
      .setNegativeButton("取消", null)
      .show()
  }
}
