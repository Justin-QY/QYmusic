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

  fun visible(): List<Module> = ALL.filter { SPConfig.isNavVisible(it.key) }

  fun find(id: Int): Module? = ALL.firstOrNull { it.id == id }
}
