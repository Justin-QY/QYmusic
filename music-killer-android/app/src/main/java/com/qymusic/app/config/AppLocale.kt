package com.qymusic.app.config

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * 应用内语言切换（设置 → 通用设置）。
 *
 * - API 33+ 走系统 per-app language（[AppCompatDelegate.setApplicationLocales]）；
 * - 低版本由 AppCompat 的兼容实现接管，效果一致，不需要额外分支；
 * - 选择结果另存一份到 [SPConfig.APP_LANGUAGE]，进程冷启动时在 Application 里重新应用，
 *   即使系统没有记住应用语言，下次启动也能恢复。
 *
 * 项目目前只有中文资源，选英文不会崩溃，只是没有对应翻译的界面继续显示中文。
 */
object AppLocale {

  /** 系统语言用「空 LocaleList」表示，AppCompat 会回落到系统配置 */
  fun localesFor(language: String): LocaleListCompat = when (language) {
    SPConfig.LANGUAGE_ZH -> LocaleListCompat.forLanguageTags("zh-CN")
    SPConfig.LANGUAGE_EN -> LocaleListCompat.forLanguageTags("en")
    else -> LocaleListCompat.getEmptyLocaleList()
  }

  fun applySaved() {
    apply(SPConfig.appLanguage())
  }

  /**
   * 应用语言。切换后当前 Activity 若在前台会由 AppCompat 自动重建，
   * 这里只负责写入，调用方按需提示用户重启。
   */
  fun apply(language: String) {
    runCatching {
      AppCompatDelegate.setApplicationLocales(localesFor(language))
    }
  }
}
