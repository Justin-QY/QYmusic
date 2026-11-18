package com.qymusic.app.ui.fragment.setting

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Handler
import android.os.Looper
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
import com.qymusic.app.utils.SleepTimer
import com.qymusic.app.utils.SleepTimerDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 设置页：按设计稿重组为「当前配置 / 曲库 / 播放 / 播放音源 / 通用 / 关于与联系」
 */
class SettingFragment : BaseMusicAppbarFragment<FragmentSettingBinding>(R.layout.fragment_setting) {

    companion object {
        private const val WEBSITE = "https://www.qyder.com"
        private const val EMAIL = "qyder8@gmail.com"
    }

    private var countingScripts = false

    private val uiHandler = Handler(Looper.getMainLooper())

    /** 定时播放剩余时间每秒刷新一次 */
    private val sleepTimerTicker = object : Runnable {
        override fun run() {
            updateSleepTimerState()
            uiHandler.postDelayed(this, 1000L)
        }
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

        binding.itemSleepTimer.setOnClickListener {
            SleepTimerDialog.show(requireContext()) { updateSleepTimerState() }
        }
        binding.itemLyricSettings.setOnClickListener {
            findNavController().navigate(R.id.action_setting_lyrics)
        }
        binding.itemGeneralSettings.setOnClickListener {
            findNavController().navigate(R.id.action_setting_general)
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
        binding.itemVersion.setOnClickListener { copyVersion() }

        initCatalogToggle()
        initSwitches()

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
        countScripts()
        updateSleepTimerState()
        uiHandler.removeCallbacks(sleepTimerTicker)
        uiHandler.post(sleepTimerTicker)
    }

    override fun onPause() {
        uiHandler.removeCallbacks(sleepTimerTicker)
        super.onPause()
    }

    // ------------------------------------------------------------------ 当前配置

    private fun refreshSummary() {
        binding.tvVersion.text = "版本 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        binding.tvAboutVersion.text =
            "版本 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})，点击复制"

        binding.tvConfigCatalog.text = when (SPConfig.catalogSource()) {
            SPConfig.SOURCE_KUGOU -> "酷狗"
            SPConfig.SOURCE_QQ -> "QQ音乐"
            else -> "网易云"
        }

        binding.tvConfigQuality.text =
            MusicBridge.getBridgeForLevel(SPConfig.currentBridge()).displayName

        val thirdEnabled = SPConfig.isThirdPartyEnabled() && SPConfig.thirdPartyKey().isNotBlank()
        binding.tvConfigSource.text = if (thirdEnabled) "第三方优先" else "落雪优先"

        binding.tvQualityValue.text =
            MusicBridge.getBridgeForLevel(SPConfig.currentBridge()).displayName

        binding.tvSourceThirdState.text = when {
            thirdEnabled -> "ChKSz API · 已启用"
            SPConfig.thirdPartyKey().isNotBlank() -> "ChKSz API · 已配置未启用"
            else -> "ChKSz API · 未启用"
        }

        binding.switchTranslation.isChecked = SPConfig.isShowTranslation()
        binding.tvLyricSettingsState.text = buildLyricSettingsSummary()
        binding.tvUpdateState.text = "当前 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        binding.tvContactEmail.text = EMAIL

        // 两个入口的摘要：启用平台数 / 底栏模块数
        binding.tvPluginManagerState.text =
            "${SPConfig.enabledCloudPlatforms().size} 个平台已启用，点击管理"
        binding.tvNavManagerState.text =
            "${com.qymusic.app.config.NavModules.visible().size} 个模块显示在底栏，点击管理"

        // 通用设置入口摘要：主题 · 默认启动页
        binding.tvGeneralSettingsState.text = GeneralSettingsFragment.summary()
    }

    /** 歌词设置入口摘要：字号 + 翻译 + 超级岛，一眼看出当前配置 */
    private fun buildLyricSettingsSummary(): String {
        val size = SPConfig.lyricFontSize()
        val sizeLabel = when (size) {
            SPConfig.LYRIC_FONT_SIZE_SMALL -> "小"
            SPConfig.LYRIC_FONT_SIZE_NORMAL -> "标准"
            SPConfig.LYRIC_FONT_SIZE_LARGE -> "大"
            SPConfig.LYRIC_FONT_SIZE_XLARGE -> "特大"
            else -> "自定义"
        }
        val translation = if (SPConfig.isLyricTranslationEnabled()) "翻译开" else "翻译关"
        val island = if (SPConfig.isLyricSuperIslandEnabled()) "超级岛开" else "超级岛关"
        return "字号$sizeLabel · $translation · $island"
    }

    private fun countScripts() {
        if (countingScripts) {
            return
        }
        countingScripts = true
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val list = Get<List<LxScriptModel>>(Api.LX_SCRIPTS).await()
                binding.tvSourceLxState.text = "服务器脚本 · ${list.size} 个同时加载"
            } catch (e: Exception) {
                binding.tvSourceLxState.text = "服务器脚本 · 读取失败"
            } finally {
                countingScripts = false
            }
        }
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

    // ------------------------------------------------------------------ 开关

    private fun initSwitches() {
        binding.switchTranslation.setOnCheckedChangeListener { _, checked ->
            SPConfig.setShowTranslation(checked)
        }
    }

    // ------------------------------------------------------------------ 定时播放

    /** 定时状态文案；弹窗本身由共享的 SleepTimerDialog 负责，与右上角菜单入口一致 */
    private fun updateSleepTimerState() {
        if (!isAdded || !::baseContentBinding.isInitialized) {
            return
        }
        val text = if (SleepTimer.isActive()) {
            "剩余 " + SleepTimerDialog.formatRemaining(SleepTimer.remainingMs()) + " 后暂停 · 点击取消"
        } else {
            "到点自动暂停播放"
        }
        if (binding.tvSleepTimerState.text.toString() != text) {
            binding.tvSleepTimerState.text = text
        }
    }

    // ------------------------------------------------------------------ 关于

    private fun showLongTextDialog(title: String, content: String) {
        if (!isAdded) {
            return
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setMessage(content)
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun copyVersion() {
        val text = "QY MUSIC ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        try {
            val manager =
                requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            manager?.setPrimaryClip(ClipData.newPlainText("版本号", text))
            toast("版本号已复制")
        } catch (e: Exception) {
            toast(text)
        }
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
