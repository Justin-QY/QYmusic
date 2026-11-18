package com.qymusic.app.ui.fragment.setting

import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.drake.tooltip.toast
import com.qymusic.app.R
import com.qymusic.app.base.BaseMusicAppbarFragment
import com.qymusic.app.config.NavModules
import com.qymusic.app.config.SPConfig
import com.qymusic.app.databinding.FragmentNavManagerBinding
import com.qymusic.app.databinding.ItemModuleNavBinding
import com.qymusic.app.databinding.ItemNavPreviewBinding
import com.qymusic.app.ui.widget.DragSortSupport

/**
 * 底部导航管理页：
 * - 首页 / 云端的显示开关，至少保留一个；
 * - 长按拖动调整底栏顺序；
 * - 顶部实时预览底栏排列。
 *
 * 底栏由 MainActivity 在 onCreate 按 [NavModules.visible] 重建，
 * 因此保存显隐或顺序后调用 recreate() 立即生效。
 */
class NavManagerFragment :
  BaseMusicAppbarFragment<FragmentNavManagerBinding>(R.layout.fragment_nav_manager) {

  private data class NavRow(val module: NavModules.Module, val visible: Boolean)

  private val navAdapter = NavAdapter()

  override fun initView() {
    binding.rvNav.layoutManager = LinearLayoutManager(requireContext())
    binding.rvNav.adapter = navAdapter
    DragSortSupport.attach(binding.rvNav, navAdapter::move, ::saveNavOrder)
  }

  override fun initData() {
  }

  override fun onResume() {
    super.onResume()
    renderNav()
  }

  // ------------------------------------------------------------------ 导航模块

  private fun renderNav() {
    val rows = NavModules.ordered().map { module ->
      NavRow(module, SPConfig.isNavVisible(module.key))
    }
    navAdapter.submit(rows)
    renderPreview(rows)
  }

  /** 底栏预览：按实际显示顺序铺一排图标 + 文字 */
  private fun renderPreview(rows: List<NavRow>) {
    val container = binding.llNavPreview
    container.removeAllViews()
    val visible = rows.filter { it.visible }
    visible.forEach { row ->
      val item = ItemNavPreviewBinding.inflate(layoutInflater, container, false)
      item.imgPreviewIcon.setImageResource(row.module.icon)
      item.tvPreviewName.text = row.module.title
      container.addView(item.root)
    }
    binding.tvPreviewHint.text = if (visible.size <= 1) {
      "只剩一个模块时整条底栏会自动隐藏，避免占位"
    } else {
      "预览按实际底栏顺序排列，共 ${visible.size} 个模块"
    }
  }

  /** 至少保留一个模块可见，改动后重建界面让底栏立即生效 */
  private fun setNavVisible(key: String, visible: Boolean) {
    if (!visible && NavModules.ordered().count { SPConfig.isNavVisible(it.key) } <= 1) {
      toast("至少保留一个导航栏")
      renderNav()
      return
    }
    SPConfig.setNavVisible(key, visible)
    renderNav()
    requireActivity().recreate()
  }

  private fun saveNavOrder() {
    val keys = navAdapter.keys()
    if (keys == SPConfig.navOrder(NavModules.ALL.map { it.key })) {
      return
    }
    SPConfig.setNavOrder(keys)
    toast("底栏顺序已保存")
    // 等拖动收尾动画结束再重建，避免在 ItemTouchHelper 回调里直接 recreate
    binding.root.post {
      if (isAdded && !requireActivity().isFinishing) {
        requireActivity().recreate()
      }
    }
  }

  // ------------------------------------------------------------------ 适配器

  private inner class NavAdapter : RecyclerView.Adapter<NavAdapter.Holder>() {

    private val items = mutableListOf<NavRow>()

    inner class Holder(val itemBinding: ItemModuleNavBinding) :
      RecyclerView.ViewHolder(itemBinding.root)

    fun submit(list: List<NavRow>) {
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

    fun keys(): List<String> = items.map { it.module.key }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
      Holder(ItemModuleNavBinding.inflate(layoutInflater, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
      val row = items[position]
      with(holder.itemBinding) {
        imgNavIcon.setImageResource(row.module.icon)
        tvNavName.text = row.module.title
        tvNavDesc.text = navDesc(row)
        switchNav.setOnCheckedChangeListener(null)
        switchNav.isChecked = row.visible
        switchNav.setOnCheckedChangeListener { _, checked ->
          setNavVisible(row.module.key, checked)
        }
      }
    }

    /** 描述里带上底栏里的实际位置，拖完能直观看到效果 */
    private fun navDesc(row: NavRow): String {
      if (!row.visible) {
        return "已从底栏隐藏"
      }
      val index = items.filter { it.visible }
        .indexOfFirst { it.module.key == row.module.key }
      return "底栏第 ${index + 1} 项 · 长按可排序"
    }
  }
}
