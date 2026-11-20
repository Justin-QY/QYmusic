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
import com.qymusic.app.databinding.ItemSearchPlatformBinding
import com.qymusic.app.ui.widget.DragSortSupport

/**
 * 曲库搜索设置（设置 → 界面设置 → 曲库搜索）。
 *
 * - 跨平台音乐聚合：默认关闭，只请求明确勾选的平台；
 * - 平台列表：点击切换是否参与聚合搜索，长按拖动调整结果顺序，至少保留一个平台；
 * - 关闭当前首页曲库对应的平台时，首页曲库自动切到剩余平台，避免两处配置不一致。
 */
class SearchSourceFragment :
  BaseMusicAppbarFragment<FragmentSearchSourceBinding>(R.layout.fragment_search_source) {

  private data class PlatformInfo(val key: String, val name: String)

  private companion object {
    val PLATFORMS = listOf(
      PlatformInfo(SPConfig.SOURCE_NETEASE, SPConfig.platformLabel(SPConfig.SOURCE_NETEASE)),
      PlatformInfo(SPConfig.SOURCE_QQ, SPConfig.platformLabel(SPConfig.SOURCE_QQ)),
      PlatformInfo(SPConfig.SOURCE_KUGOU, SPConfig.platformLabel(SPConfig.SOURCE_KUGOU)),
    )
  }

  private val adapter = PlatformAdapter()

  private var syncingSwitch = false

  override fun initView() {
    binding.rvPlatforms.layoutManager = LinearLayoutManager(requireContext())
    binding.rvPlatforms.adapter = adapter
    DragSortSupport.attach(binding.rvPlatforms, adapter::move, ::savePlatformOrder)

    binding.switchAggregate.setOnCheckedChangeListener { _, checked ->
      if (syncingSwitch) {
        return@setOnCheckedChangeListener
      }
      SPConfig.setAggregateSearch(checked)
      syncAggregateState()
      toast(if (checked) "已开启跨平台聚合" else "已关闭跨平台聚合")
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
  }

  // ------------------------------------------------------------------ 平台列表

  private fun renderPlatforms() {
    val order = SPConfig.searchPlatformOrder()
    adapter.submit(order.mapNotNull { key -> PLATFORMS.firstOrNull { it.key == key } })
    adapter.setEnabled(SPConfig.enabledSearchPlatforms())
  }

  private fun togglePlatform(platform: String) {
    val enabled = SPConfig.enabledSearchPlatforms()
    if (platform in enabled) {
      if (!SPConfig.setSearchPlatformEnabled(platform, false)) {
        toast("至少保留一个搜索平台")
        return
      }
      // 关掉的正好是首页曲库时同步切换，避免两处设置不一致
      if (SPConfig.catalogSource() == platform) {
        SPConfig.enabledSearchPlatforms().firstOrNull()?.let { SPConfig.setCatalogSource(it) }
      }
    } else {
      SPConfig.setSearchPlatformEnabled(platform, true)
    }
    renderPlatforms()
  }

  private fun savePlatformOrder() {
    val enabled = SPConfig.enabledSearchPlatforms()
    SPConfig.applySearchPlatformOrder(adapter.keys().filter { it in enabled })
  }

  // ------------------------------------------------------------------ 适配器

  private inner class PlatformAdapter :
    RecyclerView.Adapter<PlatformAdapter.Holder>() {

    private val items = mutableListOf<PlatformInfo>()

    private var enabledKeys: Set<String> = emptySet()

    inner class Holder(val itemBinding: ItemSearchPlatformBinding) :
      RecyclerView.ViewHolder(itemBinding.root)

    fun submit(list: List<PlatformInfo>) {
      items.clear()
      items.addAll(list)
      notifyDataSetChanged()
    }

    fun setEnabled(keys: Set<String>) {
      enabledKeys = keys
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
      Holder(ItemSearchPlatformBinding.inflate(layoutInflater, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
      val item = items[position]
      val on = item.key in enabledKeys
      with(holder.itemBinding) {
        tvPlatformName.text = item.name
        tvPlatformEnabled.isVisible = on
        tvPlatformState.text = if (on) "参与聚合搜索" else "未参与请求"
        root.setOnClickListener { togglePlatform(item.key) }
      }
    }
  }
}
