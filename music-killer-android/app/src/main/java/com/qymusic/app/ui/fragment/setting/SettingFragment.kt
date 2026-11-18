package com.qymusic.app.ui.fragment.setting

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.bumptech.glide.Glide
import com.drake.net.Get
import com.drake.tooltip.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.qymusic.app.BuildConfig
import com.qymusic.app.QYMusicApplication
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

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
        binding.btnClearCache.setOnClickListener { clearCache() }
        binding.itemStorageManager.setOnClickListener {
            findNavController().navigate(R.id.action_setting_storage)
        }

        binding.itemSleepTimer.setOnClickListener { showSleepTimerDialog() }
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
        refreshCacheSize()
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

        binding.switchDataSaver.isChecked = SPConfig.isDataSaver()
        binding.switchTranslation.isChecked = SPConfig.isShowTranslation()
        binding.tvLyricSettingsState.text = buildLyricSettingsSummary()
        binding.tvUpdateState.text = "当前 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        binding.tvContactEmail.text = EMAIL

        // 两个入口的摘要：启用平台数 / 底栏模块数
        binding.tvPluginManagerState.text =
            "${SPConfig.enabledCloudPlatforms().size} 个平台已启用，点击管理"
        binding.tvNavManagerState.text =
            "${com.qymusic.app.config.NavModules.visible().size} 个模块显示在底栏，点击管理"

        // 通用设置入口摘要：语言 · 主题 · 默认启动页
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
        binding.switchDataSaver.setOnCheckedChangeListener { _, checked ->
            SPConfig.setDataSaver(checked)
        }
        binding.switchTranslation.setOnCheckedChangeListener { _, checked ->
            SPConfig.setShowTranslation(checked)
        }
    }

    // ------------------------------------------------------------------ 定时播放

    /**
     * 定时播放入口：
     * - 未开启时弹预设（15/30/60/90 分钟）与自定义分钟数；
     * - 已开启时显示剩余时间，可重新设置或取消。
     */
    private fun showSleepTimerDialog() {
        if (!isAdded) {
            return
        }
        if (SleepTimer.isActive()) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("定时播放")
                .setMessage("剩余 " + formatSleepRemaining(SleepTimer.remainingMs()) + " 后自动暂停播放。")
                .setPositiveButton("重新设置") { _, _ -> showSleepTimerPresets() }
                .setNeutralButton("取消定时") { _, _ ->
                    SleepTimer.cancel()
                    updateSleepTimerState()
                    toast("已取消定时播放")
                }
                .setNegativeButton("关闭", null)
                .show()
            return
        }
        showSleepTimerPresets()
    }

    private fun showSleepTimerPresets() {
        val presets = intArrayOf(15, 30, 60, 90)
        val labels = arrayOf("15 分钟", "30 分钟", "60 分钟", "90 分钟", "自定义分钟数…")
        var selected = 0
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("定时播放")
            .setSingleChoiceItems(labels, selected) { _, which -> selected = which }
            .setNegativeButton("取消", null)
            .setPositiveButton("开始") { _, _ ->
                if (selected < presets.size) {
                    startSleepTimer(presets[selected])
                } else {
                    showCustomMinutesDialog()
                }
            }
            .show()
    }

    private fun showCustomMinutesDialog() {
        val density = resources.displayMetrics.density
        val input = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "1 - 600 分钟"
            setSingleLine()
        }
        val container = FrameLayout(requireContext()).apply {
            val horizontal = (22 * density).toInt()
            setPadding(horizontal, (8 * density).toInt(), horizontal, 0)
            addView(
                input,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("自定义分钟数")
            .setView(container)
            .setNegativeButton("取消", null)
            .setPositiveButton("开始") { _, _ ->
                val minutes = input.text.toString().trim().toIntOrNull()
                if (minutes == null || minutes <= 0 || minutes > 600) {
                    toast("请输入 1 - 600 之间的分钟数")
                    return@setPositiveButton
                }
                startSleepTimer(minutes)
            }
            .show()
    }

    private fun startSleepTimer(minutes: Int) {
        SleepTimer.start(minutes)
        updateSleepTimerState()
        toast("已开启定时播放：$minutes 分钟后暂停")
    }

    private fun updateSleepTimerState() {
        if (!isAdded || !::baseContentBinding.isInitialized) {
            return
        }
        val text = if (SleepTimer.isActive()) {
            "剩余 " + formatSleepRemaining(SleepTimer.remainingMs()) + " 后暂停 · 点击取消"
        } else {
            "到点自动暂停播放"
        }
        if (binding.tvSleepTimerState.text.toString() != text) {
            binding.tvSleepTimerState.text = text
        }
    }

    /** 小于一小时显示 mm:ss，超过一小时显示 h:mm:ss */
    private fun formatSleepRemaining(ms: Long): String {
        val totalSeconds = (ms / 1000).coerceAtLeast(0L)
        val hours = totalSeconds / 3600
        val minutes = totalSeconds % 3600 / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
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

    // ------------------------------------------------------------------ 缓存

    private fun refreshCacheSize() {
        viewLifecycleOwner.lifecycleScope.launch {
            val size = withContext(Dispatchers.IO) { calculateCacheSize() }
            binding.tvCacheSize.text = "已占用 ${formatSize(size)}，点击清除"
        }
    }

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

    private fun calculateCacheSize(): Long {
        val dirs = listOfNotNull(requireContext().cacheDir, requireContext().externalCacheDir)
        return dirs.sumOf { dir -> dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } }
    }

    private fun clearCache() {
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    QYMusicApplication.simpleCache.keys.toList().forEach {
                        QYMusicApplication.simpleCache.removeResource(it)
                    }
                    Glide.get(requireContext()).clearDiskCache()
                    requireContext().cacheDir?.listFiles()?.forEach { file ->
                        if (file.name != "media_cache") {
                            file.deleteRecursively()
                        }
                    }
                    File(requireContext().cacheDir, "update").deleteRecursively()
                }
            }
            Glide.get(requireContext()).clearMemory()
            toast("缓存已清除")
            refreshCacheSize()
        }
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 MB"
        val mb = bytes / 1024.0 / 1024.0
        return if (mb < 1) "${bytes / 1024} KB" else String.format("%.1f MB", mb)
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
