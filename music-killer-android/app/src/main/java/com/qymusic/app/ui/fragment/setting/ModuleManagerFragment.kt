package com.qymusic.app.ui.fragment.setting

import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.drake.tooltip.toast
import com.qymusic.app.R
import com.qymusic.app.base.BaseMusicAppbarFragment
import com.qymusic.app.config.SPConfig
import com.qymusic.app.databinding.FragmentModuleManagerBinding
import com.qymusic.app.databinding.ItemModulePluginBinding
import com.qymusic.app.databinding.ItemModulePluginDisabledBinding
import com.qymusic.app.ui.widget.DragSortSupport

/**
 * 云端管理页（参考 QPlayer 插件页）：
 * - 已启用的云端平台可开关、可长按拖动排序；
 * - 未启用的平台点「启用」即可加回，至少保留一个。
 *
 * 数据全部读写 [SPConfig]：云端页在 onResume 重新读取平台顺序，返回后立即生效。
 * 底部导航的显隐与排序在「底部导航管理」页，二者互不耦合。
 */
class ModuleManagerFragment :
  BaseMusicAppbarFragment<FragmentModuleManagerBinding>(R.layout.fragment_module_manager) {

  private data class PluginInfo(
    val platform: String,
    val name: String,
    val version: String,
    val desc: String,
    val avatar: String,
  )

  private companion object {
    /** 插件版本先写死在客户端，等插件自带上报后再从插件读取 */
    val PLUGINS = listOf(
      PluginInfo(
        SPConfig.CLOUD_NETEASE, "网易云", "0.5.9",
        "云端歌单、我喜欢的音乐与收藏歌手", "网"
      ),
      PluginInfo(
        SPConfig.CLOUD_QQ, "QQ音乐", "0.7.5",
        "QQ 音乐云端歌单与收藏", "Q"
      ),
      PluginInfo(
        SPConfig.CLOUD_KUGOU, "酷狗", "0.3.0",
        "酷狗概念版云端歌单，支持签到领 VIP", "酷"
      ),
    )
  }

  private val enabledAdapter = EnabledPluginAdapter()

  override fun initView() {
    binding.rvPlugins.layoutManager = LinearLayoutManager(requireContext())
    binding.rvPlugins.adapter = enabledAdapter
    DragSortSupport.attach(binding.rvPlugins, enabledAdapter::move, ::savePluginOrder)
  }

  override fun initData() {
  }

  override fun onResume() {
    super.onResume()
    renderPlugins()
  }

  // ------------------------------------------------------------------ 插件分组

  /**
   * 已启用平台按 SPConfig 保存的顺序进入可拖动列表，未启用的平台以「启用」按钮展示。
   * 拖动只能改变已启用平台之间的顺序，未启用平台的相对位置保持不变。
   */
  private fun renderPlugins() {
    val enabledKeys = SPConfig.enabledCloudPlatforms()
    enabledAdapter.submit(enabledKeys.mapNotNull { key -> PLUGINS.firstOrNull { it.platform == key } })

    val disabled = PLUGINS.filter { it.platform !in enabledKeys }
    binding.llDisabled.removeAllViews()
    disabled.forEach { item ->
      val row = ItemModulePluginDisabledBinding.inflate(layoutInflater, binding.llDisabled, false)
      row.tvPluginAvatar.text = item.avatar
      row.tvPluginName.text = item.name
      row.tvPluginVersion.text = item.version
      row.tvPluginDesc.text = item.desc
      row.btnPluginEnable.setOnClickListener { setPlatformEnabled(item.platform, true) }
      binding.llDisabled.addView(row.root)
    }
    binding.tvDisabledEmpty.isVisible = disabled.isEmpty()
  }

  private fun setPlatformEnabled(platform: String, enabled: Boolean) {
    val name = PLUGINS.firstOrNull { it.platform == platform }?.name ?: "该平台"
    if (!SPConfig.setCloudPlatformEnabled(platform, enabled)) {
      toast("至少保留一个云端平台")
      renderPlugins()
      return
    }
    renderPlugins()
    toast(if (enabled) "已启用$name" else "已关闭$name")
  }

  private fun savePluginOrder() {
    SPConfig.applyCloudPlatformOrder(enabledAdapter.keys())
  }

  // ------------------------------------------------------------------ 适配器

  private inner class EnabledPluginAdapter :
    RecyclerView.Adapter<EnabledPluginAdapter.Holder>() {

    private val items = mutableListOf<PluginInfo>()

    inner class Holder(val itemBinding: ItemModulePluginBinding) :
      RecyclerView.ViewHolder(itemBinding.root)

    fun submit(list: List<PluginInfo>) {
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

    fun keys(): List<String> = items.map { it.platform }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
      Holder(ItemModulePluginBinding.inflate(layoutInflater, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
      val item = items[position]
      with(holder.itemBinding) {
        tvPluginAvatar.text = item.avatar
        tvPluginName.text = item.name
        tvPluginVersion.text = item.version
        tvPluginDesc.text = item.desc
        // 复用行时先摘掉监听，避免复用时把旧状态写回配置
        switchPlugin.setOnCheckedChangeListener(null)
        switchPlugin.isChecked = true
        switchPlugin.setOnCheckedChangeListener { _, checked ->
          if (!checked) {
            setPlatformEnabled(item.platform, false)
          }
        }
      }
    }
  }
}
