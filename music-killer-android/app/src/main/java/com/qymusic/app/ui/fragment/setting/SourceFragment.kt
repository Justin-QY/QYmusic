package com.qymusic.app.ui.fragment.setting

import android.net.Uri
import android.provider.OpenableColumns
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.drake.net.Get
import com.drake.net.Post
import com.drake.net.utils.scopeNet
import com.drake.tooltip.toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import com.qymusic.app.R
import com.qymusic.app.base.BaseMusicAppbarFragment
import com.qymusic.app.config.Api
import com.qymusic.app.config.SPConfig
import com.qymusic.app.databinding.FragmentSourceBinding
import com.qymusic.app.databinding.ItemLocalScriptBinding
import com.qymusic.app.databinding.ItemScriptNameBinding
import com.qymusic.app.model.LxScriptModel
import com.qymusic.app.utils.LxLocalScriptStore

/**
 * 播放音源设置。
 *
 * 页面结构：
 * - 概览卡：可用脚本总数、服务器 / 本地 / 第三方状态；
 * - 落雪 LX 脚本：服务器脚本（可折叠查看、刷新）+ 本地脚本（导入、删除，仅本机）；
 * - 第三方音源：开关、ChKSz 密钥、连接测试。
 */
class SourceFragment : BaseMusicAppbarFragment<FragmentSourceBinding>(R.layout.fragment_source) {

    private var loadingScripts = false
    private var syncingSwitch = false

    private var serverScriptCount = 0
    private var localScriptCount = 0
    private var serverExpanded = false

    /** 选择本地落雪脚本文件（只读取内容，不联网、不上传） */
    private val pickScriptFile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                importFromUri(uri)
            }
        }

    override fun initView() {
        binding.btnEditKey.setOnClickListener { showKeyDialog() }
        binding.btnRefresh.setOnClickListener { refreshSources() }
        binding.btnTestThirdParty.setOnClickListener { testThirdParty() }
        binding.btnToggleServer.setOnClickListener { toggleServerScripts() }
        binding.btnImportLocal.setOnClickListener { showImportChooser() }

        binding.switchThirdParty.setOnCheckedChangeListener { _, checked ->
            if (syncingSwitch) {
                return@setOnCheckedChangeListener
            }
            if (checked) {
                if (SPConfig.thirdPartyKey().isBlank()) {
                    showKeyDialog(enableAfterSave = true)
                } else {
                    SPConfig.setThirdPartyEnabled(true)
                }
            } else {
                SPConfig.setThirdPartyEnabled(false)
            }
            updateThirdPartyStatus()
        }

        applyServerExpanded()
    }

    override fun initData() {
    }

    override fun onResume() {
        super.onResume()
        updateThirdPartyStatus()
        refreshLocalScripts()
    }

    override fun onFirstResume() {
        super.onFirstResume()
        loadScripts(showToast = false)
    }

    // ------------------------------------------------------------------ 概览 / 折叠

    private fun updateOverview() {
        val hasKey = SPConfig.thirdPartyKey().isNotBlank()
        val thirdEnabled = SPConfig.isThirdPartyEnabled() && hasKey

        binding.tvAvailableCount.text = (serverScriptCount + localScriptCount).toString()
        binding.tvServerChip.text = "✓ 服务器脚本 $serverScriptCount 个"
        binding.tvLocalChip.text = "本地脚本 $localScriptCount 个"
        binding.tvThirdChip.text = if (thirdEnabled) "第三方音源 已启用" else "第三方音源 未启用"
        binding.tvLocalBadge.text = "$localScriptCount 个"
        binding.tvServerBadge.text =
            if (serverScriptCount > 0) "$serverScriptCount 个已加载" else "未加载"
        binding.tvServerToggle.text =
            if (serverScriptCount > 0) "查看 $serverScriptCount 个脚本" else "查看脚本"
    }

    private fun toggleServerScripts() {
        serverExpanded = !serverExpanded
        applyServerExpanded()
    }

    private fun applyServerExpanded() {
        binding.llScripts.visibility = if (serverExpanded) View.VISIBLE else View.GONE
        binding.ivServerChevron.rotation = if (serverExpanded) 90f else 0f
    }

    // ------------------------------------------------------------------ 第三方音源

    private fun updateThirdPartyStatus() {
        val hasKey = SPConfig.thirdPartyKey().isNotBlank()
        val enabled = SPConfig.isThirdPartyEnabled() && hasKey
        syncingSwitch = true
        binding.switchThirdParty.isChecked = enabled
        syncingSwitch = false
        binding.tvChkszStatus.text = when {
            !hasKey -> "未配置"
            enabled -> "已启用"
            else -> "已保存"
        }
        if (!hasKey && SPConfig.isThirdPartyEnabled()) {
            SPConfig.setThirdPartyEnabled(false)
        }
        updateOverview()
    }

    private fun showKeyDialog(enableAfterSave: Boolean = false) {
        val density = resources.displayMetrics.density
        val input = android.widget.EditText(requireContext()).apply {
            hint = "ChKSz API Key"
            isSingleLine = true
            setText(SPConfig.thirdPartyKey())
            setSelection(text.length)
        }
        val container = android.widget.FrameLayout(requireContext()).apply {
            val padding = (22 * density).toInt()
            setPadding(padding, (8 * density).toInt(), padding, 0)
            addView(
                input,
                android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("ChKSz API 密钥")
            .setMessage("请填写你在 api.chksz.com 登录后获取的个人 apikey。密钥仅保存在本机，不会上传。")
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                val key = input.text.toString().trim()
                if (key.isEmpty()) {
                    toast("密钥不能为空")
                    return@setPositiveButton
                }
                SPConfig.setThirdPartyKey(key)
                if (enableAfterSave) {
                    SPConfig.setThirdPartyEnabled(true)
                }
                updateThirdPartyStatus()
                toast(if (SPConfig.isThirdPartyEnabled()) "第三方音源已启用" else "密钥已保存")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun testThirdParty() {
        val key = SPConfig.thirdPartyKey()
        if (key.isBlank()) {
            toast("请先填写 ChKSz API 密钥")
            showKeyDialog()
            return
        }
        toast("正在测试…")
        viewLifecycleOwner.lifecycleScope.launch {
            val ok = runCatching {
                val raw = Get<String>("${SPConfig.THIRD_PARTY_BASE}/api/163_search") {
                    addQuery("keyword", "周杰伦")
                    addQuery("limit", 1)
                    addQuery("apikey", key)
                }.await().orEmpty()
                JSONObject(raw).optInt("code", 0) == 200
            }.getOrElse { false }
            toast(if (ok) "连接成功，密钥可用" else "连接失败，请检查密钥或网络")
        }
    }

    // ------------------------------------------------------------------ 服务器脚本

    private fun loadScripts(showToast: Boolean) {
        if (loadingScripts) {
            return
        }
        loadingScripts = true
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val all = Get<List<LxScriptModel>>(Api.LX_SCRIPTS).await()
                // 暂时只展示「星海」，服务端其余脚本先不上
                val list = all.filter {
                    it.name.contains("星海") || it.id.contains("xinghai", ignoreCase = true)
                }
                serverScriptCount = list.size
                renderScripts(list)
            } catch (e: Exception) {
                serverScriptCount = 0
                binding.tvServerBadge.text = "读取失败"
                binding.tvScriptsEmpty.visibility = View.VISIBLE
                binding.tvScriptsEmpty.text =
                    "读取失败：${e.message ?: "网络异常"}\n请点「刷新音源」重试"
                if (showToast) {
                    toast(e.message ?: "读取音源脚本失败")
                }
            } finally {
                loadingScripts = false
                updateOverview()
            }
        }
    }

    private fun renderScripts(list: List<LxScriptModel>) {
        binding.tvScriptsEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        if (list.isEmpty()) {
            binding.tvScriptsEmpty.text = "还没有可用的音源脚本"
        }

        val container = binding.llScripts
        container.removeAllViews()
        list.forEach { script ->
            val item = ItemScriptNameBinding.inflate(layoutInflater, container, false)
            item.tvScriptName.text = script.showName
            container.addView(item.root)
        }
    }

    private fun refreshSources() {
        if (loadingScripts) {
            return
        }
        scopeNet {
            Post<String>(Api.LX_RELOAD).await()
            toast("音源已刷新")
            loadScripts(showToast = false)
        }
    }

    // ------------------------------------------------------------------ 本地脚本（仅本机）

    private fun showImportChooser() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("导入本地脚本")
            .setItems(arrayOf("从文件选择", "粘贴脚本文本")) { _, which ->
                if (which == 0) {
                    runCatching { pickScriptFile.launch(arrayOf("*/*")) }
                        .onFailure { toast("无法打开文件选择器") }
                } else {
                    showPasteDialog()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun importFromUri(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching {
                val name = queryDisplayName(uri) ?: "custom_${System.currentTimeMillis()}.js"
                val code = withContext(Dispatchers.IO) {
                    requireContext().contentResolver.openInputStream(uri)
                        ?.bufferedReader()
                        ?.use { it.readText() }
                        ?: error("无法读取文件")
                }
                withContext(Dispatchers.IO) {
                    LxLocalScriptStore.import(requireContext(), name, code).getOrThrow()
                }
            }
            result.onSuccess {
                toast("已导入：${it.title}")
                refreshLocalScripts()
            }.onFailure {
                toast(it.message ?: "导入失败")
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        requireContext().contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    private fun showPasteDialog() {
        val density = resources.displayMetrics.density
        val input = EditText(requireContext()).apply {
            hint = "粘贴落雪 LX 脚本内容（JS）"
            minLines = 6
            maxLines = 12
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
        }
        val container = FrameLayout(requireContext()).apply {
            val padding = (22 * density).toInt()
            setPadding(padding, (8 * density).toInt(), padding, 0)
            addView(
                input,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("粘贴脚本")
            .setMessage("脚本只保存在本机，不会上传服务器。")
            .setView(container)
            .setPositiveButton("导入") { _, _ ->
                val code = input.text.toString()
                viewLifecycleOwner.lifecycleScope.launch {
                    LxLocalScriptStore.import(
                        requireContext(),
                        "custom_${System.currentTimeMillis()}.js",
                        code,
                    ).onSuccess {
                        toast("已导入：${it.title}")
                        refreshLocalScripts()
                    }.onFailure {
                        toast(it.message ?: "导入失败")
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun refreshLocalScripts() {
        if (!isAdded) {
            return
        }
        val list = LxLocalScriptStore.list(requireContext())
        localScriptCount = list.size
        binding.tvLocalScriptsEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE

        val container = binding.llLocalScripts
        container.removeAllViews()
        list.forEach { script ->
            val item = ItemLocalScriptBinding.inflate(layoutInflater, container, false)
            item.tvLocalScriptName.text = script.title
            item.tvLocalScriptDesc.text = "${script.fileName} · ${script.sizeLabel} · 仅本地"
            item.btnDeleteLocalScript.setOnClickListener { confirmDeleteLocal(script) }
            container.addView(item.root)
        }
        updateOverview()
    }

    private fun confirmDeleteLocal(script: LxLocalScriptStore.LocalScript) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("删除本地脚本")
            .setMessage("确定删除「${script.title}」？只影响本机文件。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                if (LxLocalScriptStore.delete(requireContext(), script.fileName)) {
                    toast("已删除")
                    refreshLocalScripts()
                } else {
                    toast("删除失败")
                }
            }
            .show()
    }
}
