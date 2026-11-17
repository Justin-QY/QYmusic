package com.qymusic.app.ui.fragment.setting

import android.content.Intent
import android.graphics.Color
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

        initCatalogToggle()
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
    }

    // ------------------------------------------------------------------ 当前配置

    private fun refreshSummary() {
        binding.tvVersion.text = "版本 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

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
