package com.qymusic.app.config

import com.qymusic.app.R

/**
 * 底部导航模块注册表。
 *
 * 新增模块时只在这里加一条，设置页的显示/隐藏开关会自动出现，
 * 不需要改 MainActivity 或设置页布局。
 */
object NavModules {

  data class Module(
    /** 对应 nav_graph 中的 destination id */
    val id: Int,
    /** 持久化用的稳定 key，不要随意修改 */
    val key: String,
    val title: String,
    val icon: Int,
  )

  val ALL: List<Module> = listOf(
    Module(R.id.homeFragment, "home", "首页", R.drawable.ic_home_24),
    Module(R.id.cloudFragment, "cloud", "云端", R.drawable.ic_cloud_24),
  )

  /** 按保存的导航顺序排列的全部模块（未保存时默认 首页, 云端） */
  fun ordered(): List<Module> {
    val keys = SPConfig.navOrder(ALL.map { it.key })
    return keys.mapNotNull { key -> ALL.firstOrNull { it.key == key } }
  }

  fun visible(): List<Module> = ordered().filter { SPConfig.isNavVisible(it.key) }

  fun find(id: Int): Module? = ALL.firstOrNull { it.id == id }

  /**
   * 默认启动页：按保存的 key 在「当前可见模块」里查找；
   * 选中的模块被隐藏或 key 失效时回落到第一个可见模块，避免启动到不存在的页面。
   */
  fun startPage(): Module {
    val candidates = visible().ifEmpty { ALL }
    val key = SPConfig.startPageKey()
    return candidates.firstOrNull { it.key == key } ?: candidates.first()
  }
}
