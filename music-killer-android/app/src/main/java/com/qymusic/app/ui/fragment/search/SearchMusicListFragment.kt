package com.qymusic.app.ui.fragment.search

import android.view.ViewGroup
import androidx.core.view.isVisible
import com.drake.brv.utils.models
import com.drake.brv.utils.setup
import com.drake.net.Get
import com.drake.net.utils.scope
import com.drake.tooltip.toast
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import com.qymusic.app.R
import com.qymusic.app.base.BaseMusicNavFragment
import com.qymusic.app.config.Api
import com.qymusic.app.config.SPConfig
import com.qymusic.app.databinding.FragmentSearchMusicListBinding
import com.qymusic.app.model.MusicModel
import com.qymusic.app.model.Page
import com.qymusic.app.model.setupMusicRv

/**
 * 歌曲搜索结果列表。
 *
 * - 默认（聚合搜索关闭）：在「曲库搜索」启用的平台里切换，一次只查一个平台；
 * - 聚合搜索开启：按启用顺序并发查询全部平台，合并结果并给每条结果标注来源平台。
 */
class SearchMusicListFragment :
        BaseSearchListFragment<FragmentSearchMusicListBinding>(R.layout.fragment_search_music_list) {

  private companion object {
    const val PAGE_SIZE = 20
  }

  /** 当前搜索音源：wy=网易云，qq=QQ音乐，kg=酷狗 */
  private var searchSource = when (SPConfig.catalogSource()) {
    SPConfig.SOURCE_KUGOU -> SPConfig.SOURCE_KUGOU
    SPConfig.SOURCE_QQ -> SPConfig.SOURCE_QQ
    else -> SPConfig.SOURCE_NETEASE
  }

  /** 「曲库搜索」配置签名，返回设置页后配置变了就重新加载 */
  private var lastConfig = ""

  override fun getMusicModels(): List<Any?>? {
    return binding.rvMusic.models
  }

  override fun initView() {
    setupSourceToggle()

    binding.toggleSource.addOnButtonCheckedListener { _, checkedId, isChecked ->
      if (!isChecked) {
        return@addOnButtonCheckedListener
      }
      val source = sourceOfButton(checkedId)
      if (source == searchSource) {
        return@addOnButtonCheckedListener
      }
      searchSource = source
      // 搜索页切换后同步到设置页，下次进搜索默认还是这个音源
      SPConfig.setCatalogSource(source)
      mData.clear()
      pageNo = 1
      binding.page.index = 1
      binding.page.refresh()
    }

    binding.page.onRefresh {
      if (SPConfig.isAggregateSearch()) {
        loadAggregate()
      } else {
        loadSingle()
      }
    }

    binding.rvMusic.setupMusicRv(this)

    if (mData.isNotEmpty()) {
      binding.page.index = pageNo
      binding.rvMusic.models = mData.toMutableList()
    }
  }

  override fun onResume() {
    super.onResume()
    val signature = searchConfigSignature()
    if (lastConfig.isEmpty()) {
      lastConfig = signature
      return
    }
    if (signature != lastConfig) {
      lastConfig = signature
      setupSourceToggle()
      mData.clear()
      pageNo = 1
      binding.page.index = 1
      binding.page.refresh()
    }
  }

  override fun onSearch() {
    binding.page.showLoading()
  }

  // ------------------------------------------------------------------ 平台切换

  private fun searchConfigSignature(): String =
    "${SPConfig.isAggregateSearch()}|${SPConfig.enabledSearchPlatforms().joinToString(",")}"

  /** 平台切换按钮按「曲库搜索」里的启用与顺序重建；聚合搜索时整行隐藏 */
  private fun setupSourceToggle() {
    val enabled = SPConfig.enabledSearchPlatforms().toList()
    val buttons = mapOf(
      SPConfig.SOURCE_NETEASE to binding.btnSourceWy,
      SPConfig.SOURCE_QQ to binding.btnSourceQq,
      SPConfig.SOURCE_KUGOU to binding.btnSourceKw,
    )

    binding.toggleSource.removeAllViews()
    enabled.forEach { key ->
      val button = buttons[key] ?: return@forEach
      (button.parent as? ViewGroup)?.removeView(button)
      binding.toggleSource.addView(button)
    }

    if (searchSource !in enabled) {
      // 当前首页曲库不在搜索平台里时，搜索页回落到第一个启用平台，
      // 同时把首页曲库一起改过去，避免两处设置不一致。
      searchSource = enabled.first()
      SPConfig.setCatalogSource(searchSource)
    }
    binding.toggleSource.isVisible =
      !SPConfig.isAggregateSearch() && enabled.size > 1
    binding.toggleSource.check(buttonId(searchSource))
  }

  private fun buttonId(source: String): Int = when (source) {
    SPConfig.SOURCE_KUGOU -> R.id.btn_source_kw
    SPConfig.SOURCE_QQ -> R.id.btn_source_qq
    else -> R.id.btn_source_wy
  }

  private fun sourceOfButton(checkedId: Int): String = when (checkedId) {
    R.id.btn_source_kw -> SPConfig.SOURCE_KUGOU
    R.id.btn_source_qq -> SPConfig.SOURCE_QQ
    else -> SPConfig.SOURCE_NETEASE
  }

  private fun searchUrl(source: String): String = when (source) {
    SPConfig.SOURCE_KUGOU -> Api.SEARCH_KUGOU
    SPConfig.SOURCE_QQ -> Api.SEARCH_QQ
    else -> Api.SEARCH_NETEASE
  }

  // ------------------------------------------------------------------ 数据

  private suspend fun fetchPage(source: String, pageNo: Int): Page<MusicModel> =
    kotlinx.coroutines.coroutineScope {
      Get<Page<MusicModel>>(searchUrl(source)) {
        addQuery("keyword", keyword)
        addQuery("pageNo", pageNo)
        addQuery("pageSize", PAGE_SIZE)
      }.await()
    }

  /** 单平台搜索：保持原有行为 */
  private fun com.drake.brv.PageRefreshLayout.loadSingle() {
    val source = searchSource
    val currentPage = index
    scope {
      val result = fetchPage(source, currentPage)
      addData(result.data.onEach {
        it.source = source
        it.showSource = false
        mainViewModel.getMusicModelState(it)
      }) {
        result.total > modelCount
      }
      result.page = currentPage
      addPage(result)
    }
  }

  /**
   * 聚合搜索：按启用顺序并发查询所有平台，按平台顺序拼接结果，
   * 每条结果带上自身来源，播放时按来源解析音源。
   */
  private fun com.drake.brv.PageRefreshLayout.loadAggregate() {
    val platforms = SPConfig.enabledSearchPlatforms().toList()
    val currentPage = index
    scope {
      val pages = platforms.map { source ->
        async { source to runCatching { fetchPage(source, currentPage) }.getOrNull() }
      }.awaitAll()

      if (pages.all { it.second == null }) {
        toast("搜索失败，请检查网络后重试")
        return@scope
      }

      val merged = mutableListOf<MusicModel>()
      var total = 0L
      var hasMore = false
      pages.forEach { (source, page) ->
        if (page == null) {
          return@forEach
        }
        total += page.total
        page.data.forEach { item ->
          item.source = source
          item.showSource = true
          merged.add(item)
        }
        if (page.total > currentPage.toLong() * PAGE_SIZE) {
          hasMore = true
        }
      }

      addData(merged.onEach { mainViewModel.getMusicModelState(it) }) { hasMore }
      addPage(Page(total = total, data = merged).apply { page = currentPage })
    }
  }
}
