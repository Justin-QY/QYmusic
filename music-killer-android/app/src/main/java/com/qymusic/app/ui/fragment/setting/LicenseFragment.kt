package com.qymusic.app.ui.fragment.setting

import android.content.Intent
import android.view.View
import androidx.core.net.toUri
import com.drake.tooltip.toast
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.qymusic.app.R
import com.qymusic.app.base.BaseMusicAppbarFragment
import com.qymusic.app.databinding.FragmentLicenseBinding
import com.qymusic.app.databinding.ItemLicenseBinding

/**
 * 开源许可：分组展示「音源与协议参考」与「客户端组件」。
 *
 * 每行显示项目名与许可证，点击弹窗展示说明、许可证类型与源码地址。
 * 参考项目未包含其源代码，仅作机制或界面参考。
 */
class LicenseFragment : BaseMusicAppbarFragment<FragmentLicenseBinding>(R.layout.fragment_license) {

  private data class LicenseItem(
    val name: String,
    val license: String,
    val description: String,
    val url: String,
  )

  private data class LicenseGroup(val title: String, val items: List<LicenseItem>)

  private val groups = listOf(
    LicenseGroup(
      "音源与协议参考",
      listOf(
        LicenseItem(
          "QPlayer 插件",
          "MIT",
          "插件机制、音源声明与登录方式参考；仓库内 qplayer-plugins 为归档产物，未包含其源码",
          "https://github.com/TIMER-err/qplayer-plugin-template"
        ),
        LicenseItem(
          "LX Music 落雪音乐",
          "Apache-2.0",
          "音源脚本机制与脚本管理方式参考",
          "https://github.com/lyswhut/lx-music-desktop"
        ),
        LicenseItem(
          "MeloX",
          "GPL-3.0",
          "界面与交互设计参考，参考未含代码",
          "https://github.com/lladlam/MeloX-Android"
        ),
        LicenseItem(
          "md3Music",
          "AGPL-3.0",
          "界面与交互设计参考，参考未含代码",
          "https://github.com/zzyoxml/md3Music"
        ),
        LicenseItem(
          "QQMusicApi",
          "GPL-3.0",
          "QQ 音乐接口协议与字段结构参考",
          "https://github.com/jsososo/QQMusicApi"
        ),
      )
    ),
    LicenseGroup(
      "客户端组件",
      listOf(
        LicenseItem(
          "AndroidX / Material3",
          "Apache-2.0",
          "基础组件、Material3 控件与主题",
          "https://github.com/androidx/androidx"
        ),
        LicenseItem(
          "Media3 / ExoPlayer",
          "Apache-2.0",
          "播放内核、媒体会话与后台播放",
          "https://github.com/androidx/media"
        ),
        LicenseItem(
          "BRV / Net / Engine",
          "Apache-2.0",
          "列表适配、网络请求与基础框架",
          "https://github.com/liangjingkanji/BRV"
        ),
        LicenseItem(
          "Glide",
          "BSD / MIT",
          "图片加载与缓存",
          "https://github.com/bumptech/glide"
        ),
        LicenseItem(
          "LitePal",
          "Apache-2.0",
          "本地数据库",
          "https://github.com/LitePalFramework/LitePal"
        ),
        LicenseItem(
          "OkHttp / kotlinx.serialization",
          "Apache-2.0",
          "网络传输与 JSON 序列化",
          "https://github.com/square/okhttp"
        ),
        LicenseItem(
          "ZXing",
          "Apache-2.0",
          "二维码解析",
          "https://github.com/zxing/zxing"
        ),
      )
    ),
  )

  override fun initView() {
    renderLicenseList()
  }

  override fun initData() {
  }

  private fun renderLicenseList() {
    val container = binding.llLicense
    container.removeAllViews()
    groups.forEach { group ->
      container.addView(createGroupHeader(group.title))
      group.items.forEach { item ->
        val row = ItemLicenseBinding.inflate(layoutInflater, container, false)
        row.tvLicenseName.text = item.name
        row.tvLicenseType.text = item.license
        row.tvLicenseDesc.text = item.description
        row.root.setOnClickListener { showLicenseDetail(item) }
        container.addView(row.root)
      }
    }
  }

  private fun createGroupHeader(title: String): View {
    val density = resources.displayMetrics.density
    return android.widget.TextView(requireContext()).apply {
      text = title
      setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
      setTextColor(
        MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant)
      )
      setPadding((18 * density).toInt(), (16 * density).toInt(), 0, (4 * density).toInt())
    }
  }

  private fun showLicenseDetail(item: LicenseItem) {
    if (!isAdded) {
      return
    }
    MaterialAlertDialogBuilder(requireContext())
      .setTitle(item.name)
      .setMessage(
        "许可证：${item.license}\n\n说明：${item.description}\n\n源码地址：\n${item.url}"
      )
      .setNegativeButton("关闭", null)
      .setNeutralButton("打开源码") { _, _ -> openUrl(item.url) }
      .show()
  }

  private fun openUrl(url: String) {
    try {
      startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    } catch (e: Exception) {
      toast("没有找到可用的浏览器")
    }
  }
}
