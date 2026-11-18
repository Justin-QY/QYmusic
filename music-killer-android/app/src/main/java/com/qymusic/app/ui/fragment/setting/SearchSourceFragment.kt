package com.qymusic.app.ui.fragment.setting

import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.drake.tooltip.toast
import com.qymusic.app.R
import com.qymusic.app.base.BaseMusicAppbarFragment
import com.qymusic.app.config.SPConfig
import com.qymusic.app.databinding.FragmentSearchSourceBinding
import com.qymusic.app.databinding.ItemModulePluginBinding
import com.qymusic.app.databinding.ItemModulePluginDisabledBinding
import com.qymusic.app.ui.widget.DragSortSupport

/**
 * 曲库搜索设置。
 *
 * - 聚合搜索开关：关闭时搜索页只查当前选中的单个平台（默认关闭）；
 *   开启后按下面启用的平台顺序并发搜索并合并结果，每条结果标注来源平台。
 * - 平台开关与排序：三个平台默认全部启用，至少保留一个。
 *
 * 数据全部读写 [SPConfig]，搜索页每次进入都会重新读取。
 */
class SearchSourceFragment :
  BaseMusicAppbarFragment<FragmentSearchSourceBinding>(R.layout.fragment_search_source) {

  private data class PlatformInfo(
    val key: String,
    val name: String,
    val avatar: String,
    val desc: String,
  )

  private companion object {
    val PLATFORMS = listOf(
      PlatformInfo(SPConfig.SOURCE_NETEASE, "网易云", "网", "网易云曲库搜索"),
      PlatformInfo(SPConfig.SOURCE_QQ, "QQ音乐", "Q", "QQ 音乐曲库搜索"),
      PlatformInfo(SPConfig.SOURCE_KUGOU, "酷狗", "酷", "酷狗概念版曲库搜索"),
    )
  }

  private val enabledAdapter = EnabledPlatformAdapter()

  private var syncingSwitch = false

  override fun initView() {
    binding.rvPlatforms.layoutManager = LinearLayoutManager(requireContext())
    binding.rvPlatforms.adapter = enabledAdapter
    DragSortSupport.attach(binding.rvPlatforms, enabledAdapter::move, ::savePlatformOrder)

    binding.switchAggregate.setOnCheckedChangeListener { _, checked ->
      if (syncingSwitch) {
        return@setOnCheckedChangeListener
      }
      SPConfig.setAggregateSearch(checked)
      syncAggregateState()
      toast(if (checked) "已开启聚合搜索" else "已关闭聚合搜索")
    }
  }

  override fun initData() {
  }

  override fun onResume() {
    super.onResume()
    syncAggregateState()
    renderPlatforms()
  }

  private fun syncAggregateState() {
    val enabled = SPConfig.isAggregateSearch()
    syncingSwitch = true
    binding.switchAggregate.isChecked = enabled
    syncingSwitch = false
    binding.tvAggregateState.text = if (enabled) {
      "已开启 · 同时搜索启用的平台，结果按上方顺序合并并标注来源"
    } else {
      "同时搜索多个平台并合并结果，每条结果标注来源平台；默认关闭"
    }
  }

  // ------------------------------------------------------------------ 平台分组

  private fun renderPlatforms() {
    val enabledKeys = SPConfig.enabledSearchPlatforms()
    enabledAdapter.submit(enabledKeys.mapNotNull { key -> PLATFORMS.firstOrNull { it.key == key } })

    val disabled = PLATFORMS.filter { it.key !in enabledKeys }
    binding.llDisabled.removeAllViews()
    disabled.forEach { item ->
      val row = ItemModulePluginDisabledBinding.inflate(layoutInflater, binding.llDisabled, false)
      row.tvPluginAvatar.text = item.avatar
      row.tvPluginName.text = item.name
      row.tvPluginVersion.text = "已关闭"
      row.tvPluginDesc.text = item.desc
      row.btnPluginEnable.setOnClickListener { setPlatformEnabled(item.key, true) }
      binding.llDisabled.addView(row.root)
    }
    binding.tvDisabledEmpty.isVisible = disabled.isEmpty()
  }

  private fun setPlatformEnabled(platform: String, enabled: Boolean) {
    val name = PLATFORMS.firstOrNull { it.key == platform }?.name ?: "该平台"
    if (!SPConfig.setSearchPlatformEnabled(platform, enabled)) {
      toast("至少保留一个搜索平台")
      renderPlatforms()
      return
    }
    renderPlatforms()
    toast(if (enabled) "已启用$name" else "已关闭$name")
  }

  private fun savePlatformOrder() {
    SPConfig.applySearchPlatformOrder(enabledAdapter.keys())
  }

  // ------------------------------------------------------------------ 适配器

  private inner class EnabledPlatformAdapter :
    RecyclerView.Adapter<EnabledPlatformAdapter.Holder>() {

    private val items = mutableListOf<PlatformInfo>()

    inner class Holder(val itemBinding: ItemModulePluginBinding) :
      RecyclerView.ViewHolder(itemBinding.root)

    fun submit(list: List<PlatformInfo>) {
      items.clear()
      items.addAll(list)
      notifyDataSetChanged()
    }

    fun move(from: Int, to: Int): Boolean {
      if (from == to || from !in items.indices || to !in items.indices) {
        return false
      }
      items.add(to, items.removeAt(from))
      notifyItemMoved(from, to)
      return true
    }

    fun keys(): List<String> = items.map { it.key }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
      Holder(ItemModulePluginBinding.inflate(layoutInflater, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
      val item = items[position]
      with(holder.itemBinding) {
        tvPluginAvatar.text = item.avatar
        tvPluginName.text = item.name
        tvPluginVersion.text = ""
        tvPluginDesc.text = item.desc
        // 复用行时先摘掉监听，避免复用时把旧状态写回配置
        switchPlugin.setOnCheckedChangeListener(null)
        switchPlugin.isChecked = true
        switchPlugin.setOnCheckedChangeListener { _, checked ->
          if (!checked) {
            setPlatformEnabled(item.key, false)
          }
        }
      }
    }
  }
}
