package com.qymusic.app.ui.fragment.setting

import androidx.lifecycle.lifecycleScope
import com.drake.tooltip.toast
import com.qymusic.app.R
import com.qymusic.app.base.BaseMusicAppbarFragment
import com.qymusic.app.config.SPConfig
import com.qymusic.app.databinding.FragmentQualityBinding
import com.qymusic.app.enums.MusicBridge

/**
 * 默认音质设置（对应设计稿第二页）
 */
class QualityFragment :
  BaseMusicAppbarFragment<FragmentQualityBinding>(R.layout.fragment_quality) {

  private val radios = mapOf(
    R.id.rb_standard to MusicBridge.MP3_128K,
    R.id.rb_high to MusicBridge.MP3_320K,
    R.id.rb_lossless to MusicBridge.FLAC_2000K,
    R.id.rb_hires to MusicBridge.HIRES,
  )

  override fun initView() {
    binding.groupQuality.setOnCheckedChangeListener { _, checkedId ->
      val bridge = radios[checkedId] ?: return@setOnCheckedChangeListener
      SPConfig.setCurrentBridge(bridge.level)
      toast("默认音质已设置为 ${bridge.displayName}")
    }

    // 整行可点，而不是只能点小圆点
    radios.keys.forEach { radioId ->
      val radio = binding.root.findViewById<android.view.View>(radioId)
      (radio?.parent as? android.view.View)?.setOnClickListener {
        binding.groupQuality.check(radioId)
      }
    }

    // 省流量模式：沿用原来的 SPConfig key，播放链路仍在读取时生效
    binding.switchDataSaver.isChecked = SPConfig.isDataSaver()
    binding.switchDataSaver.setOnCheckedChangeListener { _, checked ->
      SPConfig.setDataSaver(checked)
    }
    binding.itemDataSaver.setOnClickListener {
      binding.switchDataSaver.toggle()
    }
  }

  override fun initData() {
  }

  override fun onResume() {
    super.onResume()
    val current = MusicBridge.getBridgeForLevel(SPConfig.currentBridge())
    val id = radios.entries.firstOrNull { it.value == current }?.key ?: R.id.rb_standard
    if (binding.groupQuality.checkedRadioButtonId != id) {
      binding.groupQuality.check(id)
    }

    val dataSaver = SPConfig.isDataSaver()
    if (binding.switchDataSaver.isChecked != dataSaver) {
      binding.switchDataSaver.isChecked = dataSaver
    }
  }
}
