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
            switch.setOnCheckedChangeListener { _, checked ->
                if (!checked && com.qymusic.app.config.NavModules.ALL.count {
                        SPConfig.isNavVisible(it.key)
                    } <= 1
                ) {
                    switch.isChecked = true
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
            SPConfig.SOURCE_KUWO -> "酷我"
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
        binding.btnCatalogKw.setOnClickListener { selectCatalog(SPConfig.SOURCE_KUWO) }
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
                SPConfig.SOURCE_KUWO -> "已切换到酷我曲库，正在刷新首页"
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
        styleCatalogButton(binding.btnCatalogKw, source == SPConfig.SOURCE_KUWO)
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
