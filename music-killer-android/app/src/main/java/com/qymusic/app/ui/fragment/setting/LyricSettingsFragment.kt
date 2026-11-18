package com.qymusic.app.ui.fragment.setting

import android.content.Intent
import android.graphics.Color
import android.util.TypedValue
import com.drake.tooltip.toast
import com.qymusic.app.R
import com.qymusic.app.base.BaseMusicAppbarFragment
import com.qymusic.app.config.SPConfig
import com.qymusic.app.constants.IntentActions
import com.qymusic.app.databinding.FragmentLyricSettingsBinding
import com.qymusic.app.utils.lyric.SuperIslandHelper

/**
 * 歌词设置（设置 → 歌词）。
 *
 * - 字号：小 / 标准 / 大 / 特大，实时预览一行歌词；
 * - 翻译：与设置页「显示歌曲翻译」共用 [SPConfig.SHOW_TRANSLATION]；
 * - 澎湃 OS 超级岛歌词：写入 [SPConfig.LYRIC_SUPER_ISLAND]，并广播通知 MusicService
 *   立即接入 / 退出（实现见 SuperIslandHelper / SuperIslandLyrics）。
 *
 * 所有配置都落在 SPConfig，页面本身不持有状态，onResume 重新同步一次。
 */
class LyricSettingsFragment :
  BaseMusicAppbarFragment<FragmentLyricSettingsBinding>(R.layout.fragment_lyric_settings) {

  private companion object {
    /** 与歌词页保持一致：普通行 = 当前行 * 0.8 */
    const val NORMAL_TEXT_SCALE = 0.8f
  }

  private val sizeButtons = linkedMapOf(
    R.id.btn_lyric_size_small to SPConfig.LYRIC_FONT_SIZE_SMALL,
    R.id.btn_lyric_size_normal to SPConfig.LYRIC_FONT_SIZE_NORMAL,
    R.id.btn_lyric_size_large to SPConfig.LYRIC_FONT_SIZE_LARGE,
    R.id.btn_lyric_size_xlarge to SPConfig.LYRIC_FONT_SIZE_XLARGE,
  )

  /** 同步 UI 时屏蔽控件的回调，避免把默认值写回 SPConfig */
  private var syncing = false

  override fun initView() {
    // 与设置页一致的磨砂玻璃背景 + 透明 AppBar
    baseContentBinding.root.setBackgroundResource(R.drawable.bg_settings_glass)
    baseContentBinding.appbar.setBackgroundColor(Color.TRANSPARENT)
    baseContentBinding.toolbar.setBackgroundColor(Color.TRANSPARENT)

    binding.toggleLyricSize.addOnButtonCheckedListener { _, checkedId, isChecked ->
      if (syncing || !isChecked) {
        return@addOnButtonCheckedListener
      }
      val size = sizeButtons[checkedId] ?: return@addOnButtonCheckedListener
      SPConfig.setLyricFontSize(size)
      applyPreview(size)
    }

    binding.switchLyricTranslation.setOnCheckedChangeListener { _, checked ->
      if (syncing) {
        return@setOnCheckedChangeListener
      }
      SPConfig.setLyricTranslationEnabled(checked)
    }

    binding.switchSuperIsland.setOnCheckedChangeListener { _, checked ->
      if (syncing) {
        return@setOnCheckedChangeListener
      }
      SPConfig.setLyricSuperIslandEnabled(checked)
      notifyService()
      if (checked) {
        if (SuperIslandHelper.isSupported(requireContext())) {
          toast("已开启超级岛歌词，播放时生效")
        } else {
          toast("已保存，但当前设备不是 HyperOS，本机不会显示超级岛")
        }
      } else {
        toast("已关闭超级岛歌词")
      }
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

    val size = SPConfig.lyricFontSize()
    val checkedId = sizeButtons.entries.firstOrNull { it.value == size }?.key
      ?: R.id.btn_lyric_size_normal
    if (binding.toggleLyricSize.checkedButtonId != checkedId) {
      binding.toggleLyricSize.check(checkedId)
    }
    applyPreview(size)

    binding.switchLyricTranslation.isChecked = SPConfig.isLyricTranslationEnabled()
    binding.switchSuperIsland.isChecked = SPConfig.isLyricSuperIslandEnabled()

    val supported = SuperIslandHelper.isSupported(requireContext())
    binding.tvSuperIslandHint.text = when {
      !supported -> "当前设备不是 HyperOS，开关可以保存，但本机不会出现超级岛"
      SuperIslandHelper.focusProtocol(requireContext()) >= 3 ->
        "已检测到 HyperOS 焦点通知，播放时在超级岛显示当前歌词"
      else -> "播放时在超级岛 / 焦点通知里显示当前歌词"
    }

    syncing = false
  }

  /** 实时预览：当前行用设置字号，下一行按 0.8 缩放 */
  private fun applyPreview(sizeSp: Int) {
    binding.tvLyricSizeValue.text = "${labelFor(sizeSp)} · ${sizeSp}sp"
    binding.tvPreviewCurrent.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp.toFloat())
    binding.tvPreviewNext.setTextSize(
      TypedValue.COMPLEX_UNIT_SP,
      sizeSp * NORMAL_TEXT_SCALE
    )
  }

  private fun labelFor(sizeSp: Int): String = when (sizeSp) {
    SPConfig.LYRIC_FONT_SIZE_SMALL -> "小"
    SPConfig.LYRIC_FONT_SIZE_NORMAL -> "标准"
    SPConfig.LYRIC_FONT_SIZE_LARGE -> "大"
    SPConfig.LYRIC_FONT_SIZE_XLARGE -> "特大"
    else -> "自定义"
  }

  /**
   * 让已经在播放的 MusicService 立即读取新开关：
   * 打开就接入超级岛，关闭就撤掉通知，不用等下一首。
   */
  private fun notifyService() {
    runCatching {
      val context = requireContext()
      context.sendBroadcast(
        Intent(IntentActions.SUPER_ISLAND_SETTING).setPackage(context.packageName)
      )
    }
  }
}
