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
import androidx.appcompat.app.AppCompatDelegate
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

        binding.itemSleepTimer.setOnClickListener { showSleepTimerDialog() }
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
        initCloudPlatforms()
        binding.itemKugouCheckin.setOnClickListener { checkKugouVip() }
        initThemeToggle()
        initSwitches()
        initNavToggles()
    }

    /**
     * 底部导航开关：模块来自 NavModules 注册表，未来新增模块自动出现
     */
    private fun initNavToggles() {
        val container = binding.llNavModules
        container.removeAllViews()
        val density = resources.displayMetrics.density
        com.qymusic.app.config.NavModules.ALL.forEach { module ->
            val switch = com.google.android.material.materialswitch.MaterialSwitch(requireContext()).apply {
                text = module.title
                isChecked = SPConfig.isNavVisible(module.key)
                setPadding(0, (6 * density).toInt(), 0, (6 * density).toInt())
            }
            // 回滚开关时会再次触发监听，用这个标记避免误存配置 / 重建界面
            var reverting = false
            switch.setOnCheckedChangeListener { _, checked ->
                if (reverting) {
                    return@setOnCheckedChangeListener
                }
                val visibleCount = com.qymusic.app.config.NavModules.ALL.count {
                    SPConfig.isNavVisible(it.key)
                }
                if (!checked && visibleCount <= 1) {
                    reverting = true
                    switch.isChecked = true
                    reverting = false
                    toast("至少保留一个导航栏")
                    return@setOnCheckedChangeListener
                }
                SPConfig.setNavVisible(module.key, checked)
                requireActivity().recreate()
            }
            container.addView(switch)
        }
    }

    override fun initData() {
    }

    override fun onResume() {
        super.onResume()
        refreshSummary()
        refreshCacheSize()
        countScripts()
        updateSleepTimerState()
        uiHandler.removeCallbacks(sleepTimerTicker)
        uiHandler.post(sleepTimerTicker)
        // 从云端平台设置返回时同步开关状态
        binding.switchCloudNetease.isChecked = SPConfig.isCloudPlatformEnabled(SPConfig.CLOUD_NETEASE)
        binding.switchCloudQq.isChecked = SPConfig.isCloudPlatformEnabled(SPConfig.CLOUD_QQ)
        binding.switchCloudKugou.isChecked = SPConfig.isCloudPlatformEnabled(SPConfig.CLOUD_KUGOU)
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
        binding.tvUpdateState.text = "当前 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        binding.tvContactEmail.text = EMAIL

        binding.tvKugouCheckinState.text = if (SPConfig.isKugouLoggedIn()) {
            "已登录酷狗，点击签到领取 VIP"
        } else {
            "先在「云端」登录酷狗概念版账号"
        }
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

    // ------------------------------------------------------------------ 酷狗签到领 VIP

    /**
     * 先查 /kg/vip/status，再弹确认框；确认后调用 /kg/vip/checkin。
     * 服务端返回结构可能随酷狗侧调整，这里只做宽松解析，未知字段统一兜底展示。
     */
    private fun checkKugouVip() {
        val cookie = SPConfig.kugouCookie()
        if (cookie.isBlank()) {
            showVipDialog("未登录", "请先在「云端」登录酷狗概念版账号，再回来签到领取 VIP。")
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val raw = Get<String>(Api.KUGOU_VIP_STATUS) {
                    addQuery("cookie", cookie)
                }.await().orEmpty()
                val error = vipError(raw)
                if (error != null) {
                    showVipDialog("查询失败", error)
                    return@launch
                }
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("酷狗签到领 VIP")
                    .setMessage(vipSummary(raw).ifBlank { "点击「立即签到」领取今日 VIP 权益。" })
                    .setNegativeButton("取消", null)
                    .setPositiveButton("立即签到") { _, _ -> submitKugouCheckin(cookie) }
                    .show()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                showVipDialog("查询失败", "Cookie 已失效或未登录，请到「云端」重新登录酷狗。")
            }
        }
    }

    private fun submitKugouCheckin(cookie: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val raw = Get<String>(Api.KUGOU_VIP_CHECKIN) {
                    addQuery("cookie", cookie)
                }.await().orEmpty()
                val error = vipError(raw)
                if (error != null) {
                    showVipDialog("签到失败", error)
                    return@launch
                }
                showVipDialog(
                    "签到成功",
                    vipSummary(raw).ifBlank { "今日签到完成，VIP 权益已到账。" }
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                showVipDialog("签到失败", "Cookie 已失效或未登录，请到「云端」重新登录酷狗。")
            }
        }
    }

    private fun showVipDialog(title: String, message: String) {
        if (!isAdded) {
            return
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setMessage(message.ifBlank { "暂无数据" })
            .setPositiveButton("知道了", null)
            .show()
    }

    /** 服务端 code 非成功时返回提示文案，成功（或没有 code 字段）返回 null */
    private fun vipError(raw: String): String? {
        val json = runCatching { org.json.JSONObject(raw.trim()) }.getOrNull() ?: return null
        if (!json.has("code")) {
            return null
        }
        val code = json.optInt("code", 0)
        if (code == 200 || code == 0) {
            return null
        }
        val message = json.optString("msg")
            .ifBlank { json.optString("message") }
            .ifBlank { "Cookie 已失效或未登录，请重新登录酷狗。" }
        return message
    }

    /**
     * 把 VIP 状态 / 签到结果整理成可读文本。
     * 字段名不固定，逐个尝试常见命名，全部落空时直接展示原始数据。
     */
    private fun vipSummary(raw: String): String {
        val text = raw.trim()
        if (text.isEmpty()) {
            return ""
        }
        val json = runCatching { org.json.JSONObject(text) }.getOrNull()
            ?: return text.take(300)
        val rawData = json.opt("data")
        if (rawData is String && rawData.isNotBlank()) {
            return rawData.take(300)
        }
        val data = rawData as? org.json.JSONObject ?: json

        fun field(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
            if (!data.has(key)) {
                return@firstNotNullOfOrNull null
            }
            val value = data.opt(key) ?: return@firstNotNullOfOrNull null
            val valueText = if (value is Boolean) {
                if (value) "是" else "否"
            } else {
                value.toString()
            }
            valueText.takeIf { it.isNotBlank() && it != "null" }
        }

        val lines = mutableListOf<String>()
        field("nickname", "nickName", "userName", "nick", "user")?.let { lines += "账号：$it" }
        field(
            "isVip", "vip", "isMember", "vipStatus", "member", "vipType", "status", "state"
        )?.let { lines += "会员状态：$it" }
        field(
            "vipExpire", "vipExpireTime", "expireTime", "vipEndTime", "endTime",
            "expireAt", "vipEndDate", "expireDate"
        )?.let { lines += "到期时间：$it" }
        field(
            "isSign", "todaySign", "todaySignIn", "signed", "signStatus",
            "checkin", "checkinStatus", "isCheckin"
        )?.let { lines += "签到状态：$it" }
        field(
            "reward", "rewardDesc", "rewardName", "vipDays", "days",
            "giftName", "gift", "score", "points", "coin"
        )?.let { lines += "本次奖励：$it" }
        field("msg", "message", "tips", "desc")?.let { lines += it }

        return if (lines.isEmpty()) data.toString().take(300) else lines.joinToString("\n")
    }

    // ------------------------------------------------------------------ 主题

    private fun initThemeToggle() {
        val checked = when (SPConfig.themeMode()) {
            AppCompatDelegate.MODE_NIGHT_NO -> R.id.btn_theme_light
            AppCompatDelegate.MODE_NIGHT_YES -> R.id.btn_theme_dark
            else -> R.id.btn_theme_system
        }
        binding.toggleTheme.check(checked)

        binding.toggleTheme.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) {
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
            AppCompatDelegate.setDefaultNightMode(mode)
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

    // ------------------------------------------------------------------ 云端平台

    /**
     * 云端平台开关：至少保留一个，关闭最后一个时回滚开关。
     * 云端页在 onResume 重新读取配置，返回后立即生效，无需重建 Activity。
     */
    private fun initCloudPlatforms() {
        val platforms = listOf(
            Triple(SPConfig.CLOUD_NETEASE, "网易云", binding.switchCloudNetease),
            Triple(SPConfig.CLOUD_QQ, "QQ音乐", binding.switchCloudQq),
            Triple(SPConfig.CLOUD_KUGOU, "酷狗", binding.switchCloudKugou),
        )
        platforms.forEach { (platform, _, switch) ->
            switch.isChecked = SPConfig.isCloudPlatformEnabled(platform)
            switch.setOnCheckedChangeListener { button, checked ->
                if (checked == SPConfig.isCloudPlatformEnabled(platform)) {
                    return@setOnCheckedChangeListener
                }
                if (!SPConfig.setCloudPlatformEnabled(platform, checked)) {
                    button.isChecked = true
                    toast("至少保留一个云端平台")
                }
            }
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
