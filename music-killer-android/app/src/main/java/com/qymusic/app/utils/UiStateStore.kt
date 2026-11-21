package com.qymusic.app.utils

import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * 进程内存中的界面状态。
 *
 * 系统销毁 Activity 但进程仍存活时，Android 会重建 Activity / Fragment，
 * ViewModel、列表数据与滚动位置都会丢失。这里把「当前页面、分类、输入、滚动位置」
 * 记在进程级单例里，重建后优先恢复，不再从头加载界面。
 *
 * 注意：进程被杀后这里自然清空，属于正常冷启动。
 */
object UiStateStore {

  /** 首页播放面板 ViewPager 当前页（歌曲 / 歌词） */
  var mainViewPagerPage: Int = 0

  /** 首页播放面板是否处于展开状态 */
  var mainBottomBarExpanded: Boolean = false

  /** 最后停留的导航目的地 id */
  var lastNavDestination: Int = 0

  /** 搜索页当前分类（单曲 / 专辑 / 歌单 / 歌手） */
  var searchViewPagerPage: Int = 0

  /** 歌单页：当前分类 / 排序 / 滚动位置 */
  var playlistCategoryPosition: Int = 0
  var playlistOrder: String = "new"

  private val scrolls = mutableMapOf<String, Int>()
  private val texts = mutableMapOf<String, String>()

  fun saveScroll(key: String, position: Int) {
    if (position >= 0) {
      scrolls[key] = position
    }
  }

  fun scroll(key: String): Int? = scrolls[key]

  fun saveText(key: String, value: String) {
    texts[key] = value
  }

  fun text(key: String): String? = texts[key]
}

/**
 * 给列表接上「进程内滚动位置记忆」：
 * - 列表滚动停止时记录第一个可见项；
 * - Activity 重建后，等数据填充完成再滚回原位置，避免数据异步加载把位置冲掉。
 */
fun RecyclerView.preserveScrollState(key: String) {
  val layoutManager = layoutManager as? LinearLayoutManager ?: return
  val saved = UiStateStore.scroll(key)

  if (saved != null && saved > 0) {
    var restored = false
    val observer = object : RecyclerView.AdapterDataObserver() {
      override fun onChanged() {
        tryRestore()
      }

      override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
        tryRestore()
      }

      private fun tryRestore() {
        if (restored || adapter == null || adapter!!.itemCount == 0) {
          return
        }
        restored = true
        layoutManager.scrollToPositionWithOffset(saved, 0)
        adapter?.unregisterAdapterDataObserver(this)
      }
    }
    adapter?.registerAdapterDataObserver(observer)
    post {
      if (!restored && adapter != null && adapter!!.itemCount > 0) {
        restored = true
        layoutManager.scrollToPositionWithOffset(saved, 0)
        runCatching { adapter?.unregisterAdapterDataObserver(observer) }
      }
    }
  }

  addOnScrollListener(object : RecyclerView.OnScrollListener() {
    override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
      if (newState == RecyclerView.SCROLL_STATE_IDLE) {
        val position = layoutManager.findFirstVisibleItemPosition()
        if (position != RecyclerView.NO_POSITION) {
          UiStateStore.saveScroll(key, position)
        }
      }
    }
  })
}
