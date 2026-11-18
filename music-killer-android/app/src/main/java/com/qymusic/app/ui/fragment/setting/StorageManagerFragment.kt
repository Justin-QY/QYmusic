package com.qymusic.app.ui.fragment.setting

import android.graphics.Color
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.drake.tooltip.toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.qymusic.app.R
import com.qymusic.app.base.BaseMusicAppbarFragment
import com.qymusic.app.databinding.FragmentStorageManagerBinding
import com.qymusic.app.model.download.DownloadModel
import com.qymusic.app.utils.AppStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.litepal.LitePal

/**
 * 存储管理页：
 * - 分别统计音频缓存、图片缓存、下载文件与临时缓存，并汇总总占用；
 * - 支持单项清理与全部清理，清理前弹窗确认，完成后 Toast 并刷新数值。
 *
 * 清理范围只包含缓存与下载文件，不涉及账号、收藏和播放历史（这些数据在数据库中）。
 */
class StorageManagerFragment :
  BaseMusicAppbarFragment<FragmentStorageManagerBinding>(R.layout.fragment_storage_manager) {

  private enum class Target(val label: String, val description: String) {
    AUDIO("音频缓存", "将删除已缓存的播放音频，下次播放时会重新缓存。"),
    IMAGE("图片缓存", "将删除封面等图片缓存，重新加载时会自动下载。"),
    DOWNLOAD("下载文件", "将删除全部已下载歌曲与歌词，并移除下载记录。"),
    TEMP("临时缓存", "将删除接口缓存、升级包与临时日志等文件。"),
    ALL("全部", "将清空音频缓存、图片缓存、下载文件与临时缓存。"),
  }

  private var usage: AppStorage.Usage? = null

  private var cleaning = false

  override fun initView() {
    // 与设置页一致的磨砂玻璃背景 + 透明 AppBar
    baseContentBinding.root.setBackgroundResource(R.drawable.bg_settings_glass)
    baseContentBinding.appbar.setBackgroundColor(Color.TRANSPARENT)
    baseContentBinding.toolbar.setBackgroundColor(Color.TRANSPARENT)

    binding.btnCleanAudio.setOnClickListener { confirmClean(Target.AUDIO) }
    binding.btnCleanImage.setOnClickListener { confirmClean(Target.IMAGE) }
    binding.btnCleanDownload.setOnClickListener { confirmClean(Target.DOWNLOAD) }
    binding.btnCleanTemp.setOnClickListener { confirmClean(Target.TEMP) }
    binding.btnCleanAll.setOnClickListener { confirmClean(Target.ALL) }
  }

  override fun initData() {
  }

  override fun onResume() {
    super.onResume()
    refreshUsage()
  }

  // ------------------------------------------------------------------ 统计

  private fun refreshUsage() {
    if (!isAdded) return
    val context = requireContext().applicationContext
    viewLifecycleOwner.lifecycleScope.launch {
      val result = withContext(Dispatchers.IO) {
        AppStorage.usage(context) to buildPathText(context)
      }
      if (!isAdded || !::baseContentBinding.isInitialized) return@launch
      usage = result.first
      binding.tvStoragePath.text = result.second
      renderUsage(result.first)
    }
  }

  /** 应用私有目录与下载目录，均在 IO 线程获取（下载目录可能触发权限检查与建目录） */
  private fun buildPathText(context: android.content.Context): String = buildString {
    append("应用数据：")
    append(context.filesDir?.parentFile?.absolutePath ?: "-")
    AppStorage.downloadDir(context)?.let {
      append("\n下载目录：")
      append(it.absolutePath)
    }
  }

  private fun renderUsage(value: AppStorage.Usage) {
    binding.tvAudioSize.text = AppStorage.formatSize(value.audio)
    binding.tvImageSize.text = AppStorage.formatSize(value.image)
    binding.tvDownloadSize.text = AppStorage.formatSize(value.download)
    binding.tvTempSize.text = AppStorage.formatSize(value.temp)
    binding.tvTotalSize.text = AppStorage.formatSize(value.total)
    binding.tvTotalHint.text = buildString {
      append("音频 ").append(AppStorage.formatSize(value.audio))
      append(" · 图片 ").append(AppStorage.formatSize(value.image))
      append(" · 下载 ").append(AppStorage.formatSize(value.download))
      append(" · 临时 ").append(AppStorage.formatSize(value.temp))
    }
  }

  // ------------------------------------------------------------------ 清理

  private fun confirmClean(target: Target) {
    if (cleaning || !isAdded) return

    val size = when (target) {
      Target.AUDIO -> usage?.audio
      Target.IMAGE -> usage?.image
      Target.DOWNLOAD -> usage?.download
      Target.TEMP -> usage?.temp
      Target.ALL -> usage?.total
    }
    val message = buildString {
      append(target.description)
      if (size != null && size > 0) {
        append("\n\n本次预计释放 ")
        append(AppStorage.formatSize(size))
        append("。")
      }
      append("\n\n清理不会删除账号、收藏和播放历史。")
    }

    MaterialAlertDialogBuilder(requireContext())
      .setTitle(if (target == Target.ALL) "清理全部文件" else "清理${target.label}")
      .setMessage(message)
      .setNegativeButton("取消", null)
      .setPositiveButton("清理") { _, _ -> startClean(target) }
      .show()
  }

  private fun startClean(target: Target) {
    if (cleaning || !isAdded) return
    cleaning = true
    setButtonsEnabled(false)
    binding.btnCleanAll.text = "清理中…"

    val context = requireContext().applicationContext
    viewLifecycleOwner.lifecycleScope.launch {
      val success = try {
        withContext(Dispatchers.IO) { performClean(context, target) }
      } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
      } catch (e: Exception) {
        false
      }

      if (!isAdded || !::baseContentBinding.isInitialized) {
        cleaning = false
        return@launch
      }

      // Glide 内存缓存需在主线程清理
      if (target == Target.IMAGE || target == Target.ALL) {
        runCatching { Glide.get(requireContext()).clearMemory() }
      }

      cleaning = false
      setButtonsEnabled(true)
      binding.btnCleanAll.text = "全部清理"
      toast(
        if (!success) {
          "清理失败，请稍后重试"
        } else if (target == Target.ALL) {
          "全部缓存与下载已清理"
        } else {
          "${target.label}已清理"
        }
      )
      refreshUsage()
    }
  }

  private fun setButtonsEnabled(enabled: Boolean) {
    val buttons = listOf(
      binding.btnCleanAudio,
      binding.btnCleanImage,
      binding.btnCleanDownload,
      binding.btnCleanTemp,
      binding.btnCleanAll,
    )
    buttons.forEach { it.isEnabled = enabled }
  }

  /** 在 IO 线程执行真正的清理，返回是否整体成功 */
  private fun performClean(context: android.content.Context, target: Target): Boolean {
    var success = true
    if (target == Target.AUDIO || target == Target.ALL) {
      success = runCatching { AppStorage.clearAudioCache() }.isSuccess && success
    }
    if (target == Target.IMAGE || target == Target.ALL) {
      success = runCatching { AppStorage.clearImageCache(context) }.isSuccess && success
    }
    if (target == Target.DOWNLOAD || target == Target.ALL) {
      success = runCatching { clearDownloads(context) }.isSuccess && success
    }
    if (target == Target.TEMP || target == Target.ALL) {
      success = runCatching { AppStorage.clearTempCache(context) }.isSuccess && success
    }
    return success
  }

  /**
   * 先让下载服务取消队列任务（同时删除文件与记录），再兜底清空下载目录与数据库，
   * 避免列表里留下打不开的下载项。
   */
  private fun clearDownloads(context: android.content.Context) {
    val models = runCatching { LitePal.findAll(DownloadModel::class.java) }
      .getOrNull()
      .orEmpty()
    val service = _downloadService
    models.forEach { model ->
      if (service != null) {
        runCatching { service.deleteDownload(model.musicId) }
      }
    }
    AppStorage.clearDownloadFiles(context)
    runCatching { LitePal.deleteAll(DownloadModel::class.java) }
  }
}
