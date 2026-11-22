package com.qymusic.app.ui.fragment.setting

import android.content.Intent
import android.graphics.Color
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.navigation.fragment.findNavController
import com.drake.tooltip.toast
import com.qymusic.app.BuildConfig
import com.qymusic.app.R
import com.qymusic.app.base.BaseMusicAppbarFragment
import com.qymusic.app.config.SPConfig
import com.qymusic.app.databinding.FragmentSettingBinding
import com.qymusic.app.enums.MusicBridge
import com.qymusic.app.utils.AppUpdater
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 设置页：按设计稿重组为「云端管理 / 界面设置 / 播放 / 播放音源 / 通用 / 关于与联系」
 */
class SettingFragment : BaseMusicAppbarFragment<FragmentSettingBinding>(R.layout.fragment_setting) {

    companion object {
        private const val WEBSITE = "https://www.qyder.com"
    }

    override fun initView() {
        // 磨砂玻璃背景 + 透明 AppBar，让渐变透上来
        baseContentBinding.root.setBackgroundResource(R.drawable.bg_settings_glass)
        baseContentBinding.appbar.setBackgroundColor(Color.TRANSPARENT)
        baseContentBinding.toolbar.setBackgroundColor(Color.TRANSPARENT)

        binding.itemWebsite.setOnClickListener { openWebsite() }
        binding.itemCheckUpdate.setOnClickListener {
            AppUpdater.check(requireActivity() as AppCompatActivity, manual = true)
        }
        binding.itemQuality.setOnClickListener {
            findNavController().navigate(R.id.action_setting_quality)
        }
        binding.itemManageSource.setOnClickListener {
            findNavController().navigate(R.id.action_setting_source)
        }
        binding.itemStorageManager.setOnClickListener {
            findNavController().navigate(R.id.action_setting_storage)
        }

        binding.itemGeneralSettings.setOnClickListener {
            findNavController().navigate(R.id.action_setting_general)
        }
        binding.itemInterfaceSettings.setOnClickListener {
            findNavController().navigate(R.id.action_setting_interface)
        }
        binding.itemLicense.setOnClickListener {
            findNavController().navigate(R.id.action_setting_license)
        }
        binding.itemTerms.setOnClickListener {
            // 隐私政策已并入用户协议，一起展示
            showLongTextDialog(
                "用户协议与隐私政策",
                getString(R.string.terms_content) + "<br><br>" + getString(R.string.privacy_content)
            )
        }
    }

    override fun initData() {
    }

    override fun onResume() {
        super.onResume()
        refreshSummary()
    }

    // ------------------------------------------------------------------ 摘要

    private fun refreshSummary() {
        binding.tvVersion.text = "版本 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

        binding.tvQualityValue.text =
            MusicBridge.getBridgeForLevel(SPConfig.currentBridge()).displayName

        binding.tvUpdateState.text =
            "当前 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · 点击检查"


    }

    // ------------------------------------------------------------------ 关于

    private fun showLongTextDialog(title: String, content: String) {
        if (!isAdded) {
            return
        }
        // 协议正文是 HTML（标题加粗 + 段落换行），统一交给 HtmlCompat 渲染
        val text = androidx.core.text.HtmlCompat.fromHtml(
            content,
            androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setMessage(text)
            .setPositiveButton("关闭", null)
            .show()
    }

    // ------------------------------------------------------------------ 官网 / 邮件

    private fun openWebsite() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, WEBSITE.toUri()))
        } catch (e: Exception) {
            toast("没有找到可用的浏览器")
        }
    }

}
