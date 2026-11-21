package com.qymusic.app.ui.fragment.setting

import android.content.Intent
import android.graphics.Color
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.drake.tooltip.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.qymusic.app.BuildConfig
import com.qymusic.app.R
import com.qymusic.app.base.BaseMusicAppbarFragment
import com.qymusic.app.config.SPConfig
import com.qymusic.app.databinding.FragmentSettingBinding
import com.qymusic.app.enums.MusicBridge
import com.qymusic.app.utils.AppStorage
import com.qymusic.app.utils.AppUpdater
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 设置页：按设计稿重组为「云端管理 / 界面设置 / 播放 / 播放音源 / 通用 / 关于与联系」
 */
class SettingFragment : BaseMusicAppbarFragment<FragmentSettingBinding>(R.layout.fragment_setting) {

    companion object {
        private const val WEBSITE = "https://www.qyder.com"
        private const val EMAIL = "qyder8@gmail.com"
    }

    override fun initView() {
        // 磨砂玻璃背景 + 透明 AppBar，让渐变透上来
        baseContentBinding.root.setBackgroundResource(R.drawable.bg_settings_glass)
        baseContentBinding.appbar.setBackgroundColor(Color.TRANSPARENT)
        baseContentBinding.toolbar.setBackgroundColor(Color.TRANSPARENT)

        binding.itemWebsite.setOnClickListener { openWebsite() }
        binding.itemContact.setOnClickListener { sendEmail() }
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
            showLongTextDialog("用户协议", getString(R.string.terms_content))
        }
        binding.itemPrivacy.setOnClickListener {
            showLongTextDialog("隐私政策", getString(R.string.privacy_content))
        }

        // 云端平台配置独立页面
        binding.itemPluginManager.setOnClickListener {
            findNavController().navigate(R.id.action_setting_plugins)
        }
    }

    override fun initData() {
    }

    override fun onResume() {
        super.onResume()
        refreshSummary()
        refreshStorageSummary()
    }

    // ------------------------------------------------------------------ 摘要

    private fun refreshSummary() {
        binding.tvVersion.text = "版本 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

        binding.tvQualityValue.text =
            MusicBridge.getBridgeForLevel(SPConfig.currentBridge()).displayName

        binding.tvUpdateState.text =
            "当前 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · 点击检查"
        binding.tvContactEmail.text = EMAIL

        // 云端管理入口摘要：启用平台数
        binding.tvPluginManagerState.text =
            "${SPConfig.enabledCloudPlatforms().size} 个平台已启用，点击管理"

        // 通用设置入口摘要：主题 · 启动页 · 歌词
        binding.tvGeneralSettingsState.text = GeneralSettingsFragment.summary()

        // 界面设置入口摘要：首页曲库 · 聚合搜索开关
        binding.tvInterfaceState.text =
            "首页曲库${InterfaceSettingsFragment.catalogLabel()} · " +
                InterfaceSettingsFragment.aggregateSearchLabel()
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

    // ------------------------------------------------------------------ 存储管理

    /** 存储管理入口右侧摘要：音频、图片、下载与临时文件的总占用 */
    private fun refreshStorageSummary() {
        val context = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val total = withContext(Dispatchers.IO) { AppStorage.usage(context).total }
            if (!isAdded) return@launch
            binding.tvStorageSummary.text =
                "共占用 ${AppStorage.formatSize(total)}，点击查看明细"
        }
    }

    // ------------------------------------------------------------------ 官网 / 邮件

    private fun openWebsite() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, WEBSITE.toUri()))
        } catch (e: Exception) {
            toast("没有找到可用的浏览器")
        }
    }

    private fun sendEmail() {
        try {
            startActivity(Intent(Intent.ACTION_SENDTO, "mailto:$EMAIL".toUri()))
        } catch (e: Exception) {
            toast("没有找到可用的邮件应用")
        }
    }
}
