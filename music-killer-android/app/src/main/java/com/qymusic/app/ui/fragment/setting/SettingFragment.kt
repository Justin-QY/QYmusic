package com.qymusic.app.ui.fragment.setting

import android.content.Intent
import android.graphics.Color
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.drake.net.Get
import com.drake.tooltip.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.qymusic.app.BuildConfig
import com.qymusic.app.R
import com.qymusic.app.base.BaseMusicAppbarFragment
import com.qymusic.app.config.Api
import com.qymusic.app.config.SPConfig
import com.qymusic.app.databinding.FragmentSettingBinding
import com.qymusic.app.enums.MusicBridge
import com.qymusic.app.model.LxScriptModel
import com.qymusic.app.utils.AppStorage
import com.qymusic.app.utils.AppUpdater
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 设置页：按设计稿重组为「云端管理与底部导航 / 曲库 / 播放 / 播放音源 / 通用 / 关于与联系」
 */
class SettingFragment : BaseMusicAppbarFragment<FragmentSettingBinding>(R.layout.fragment_setting) {

    companion object {
        private const val WEBSITE = "https://www.qyder.com"
        private const val EMAIL = "qyder8@gmail.com"
    }

    private var countingScripts = false

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
        binding.itemSearchSource.setOnClickListener {
            findNavController().navigate(R.id.action_setting_search_source)
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

        initCatalogToggle()

        // 插件与底栏分别进入独立页面，避免两处重复配置
        binding.itemPluginManager.setOnClickListener {
            findNavController().navigate(R.id.action_setting_plugins)
        }
        binding.itemNavManager.setOnClickListener {
            findNavController().navigate(R.id.action_setting_nav)
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

        // 两个入口的摘要：启用平台数 / 底栏模块数
        binding.tvPluginManagerState.text =
            "${SPConfig.enabledCloudPlatforms().size} 个平台已启用，点击管理"
        binding.tvNavManagerState.text =
            "${com.qymusic.app.config.NavModules.visible().size} 个模块显示在底栏，点击管理"

        // 通用设置入口摘要：主题 · 启动页 · 歌词
        binding.tvGeneralSettingsState.text = GeneralSettingsFragment.summary()

        // 曲库搜索入口摘要：聚合开关 · 启用平台数
        binding.tvSearchSourceState.text =
            (if (SPConfig.isAggregateSearch()) "聚合搜索开" else "聚合搜索关") +
                " · ${SPConfig.enabledSearchPlatforms().size} 个平台"
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
        refreshSummary()
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
