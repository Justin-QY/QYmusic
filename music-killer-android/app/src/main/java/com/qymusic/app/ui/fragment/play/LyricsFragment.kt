package com.qymusic.app.ui.fragment.play

import android.util.Log
import android.util.TypedValue
import androidx.lifecycle.lifecycleScope
import com.dirror.lyricviewx.OnPlayClickListener
import com.drake.net.scope.AndroidScope
import com.drake.net.utils.scopeNetLife
import com.drake.net.utils.withIO
import kotlinx.coroutines.launch
import com.qymusic.app.R
import com.qymusic.app.base.BaseMusicNavFragment
import com.qymusic.app.config.SPConfig
import com.qymusic.app.databinding.FragmentLyricsBinding
import com.qymusic.app.model.MusicModel
import com.qymusic.app.utils.DownloadHelper
import com.qymusic.app.utils.lyric.LyricUtils
import java.io.FileReader

/**
 * 歌词
 */
class LyricsFragment : BaseMusicNavFragment<FragmentLyricsBinding>(R.layout.fragment_lyrics) {

  companion object {

    private const val TAG = "LyricsFragment"

    /**
     * 普通行相对当前行的缩放比例。
     * 当前行使用用户设置的字号，普通行按比例缩小，放大字号后整体仍然协调。
     */
    private const val NORMAL_TEXT_SCALE = 0.8f
  }

  private var job: AndroidScope? = null

  /** 上一次加载歌词时的翻译开关，用于返回歌词页时按新设置重新拉取 */
  private var loadedWithTranslation = SPConfig.isLyricTranslationEnabled()

  override fun initView() {
    binding.lyricViewX.setDraggable(true, object : OnPlayClickListener {
      override fun onPlayClick(time: Long): Boolean {
        mainViewModel.updateCurrentPosition(time)
        return true
      }
    })

    applyLyricStyle()
  }

  override fun initData() {
    // 跟随 View 生命周期：避免视图重建后重复订阅、重复拉取歌词
    viewLifecycleOwner.lifecycleScope.launch {
      mainViewModel.musicModel.collect {
        if (it.id == 0L) {
          return@collect
        }
        loadLyric(it)
      }
    }

    viewLifecycleOwner.lifecycleScope.launch {
      mainViewModel.currentPosition.collect {
        binding.lyricViewX.updateTime(it)
      }
    }

  }

  override fun onResume() {
    super.onResume()
    // 从「设置 · 歌词设置」返回时，字号立即生效；翻译开关变化则重新拉一次歌词
    applyLyricStyle()
    val current = mainViewModel.musicModel.value
    if (current.id != 0L && loadedWithTranslation != SPConfig.isLyricTranslationEnabled()) {
      loadLyric(current)
    }
  }

  /**
   * 应用字号设置。
   *
   * LyricViewX 的两个 textSize 接口按 px 处理（库内部直接用 Paint.setTextSize），
   * 所以这里把设置里的 sp 换算成 px；当前行 = 设置字号，普通行 = 设置字号 * 0.8。
   */
  private fun applyLyricStyle() {
    val sizeSp = SPConfig.lyricFontSize().toFloat()
    val currentPx = spToPx(sizeSp)
    binding.lyricViewX.setCurrentTextSize(currentPx)
    binding.lyricViewX.setNormalTextSize(currentPx * NORMAL_TEXT_SCALE)
    // 翻译由服务端按开关把翻译行拼进歌词（LyricUtils 的 translate 参数），
    // LyricViewX 1.4.0-alpha02 没有暴露翻译绘制开关，这里不额外处理。
  }

  private fun spToPx(sp: Float): Float = TypedValue.applyDimension(
    TypedValue.COMPLEX_UNIT_SP,
    sp,
    resources.displayMetrics
  )

  private fun loadLyric(model: MusicModel) {
    val context = context ?: return
    val withTranslation = SPConfig.isLyricTranslationEnabled()
    job?.cancel()

    job = scopeNetLife {

      val lyricFile = DownloadHelper.getLyricFilePath(context, model)

      val lyricContent = if (lyricFile.exists() && lyricFile.length() > 0) {
        withIO {
          val fileReader = FileReader(lyricFile)
          fileReader.readText().also {
            fileReader.close()
          }
        }
      } else {
        LyricUtils.getLyricContent(model)
      }

      binding.lyricViewX.loadLyric(
        if (lyricContent.isBlank()) "[00:00.00]暂无歌词" else lyricContent
      )
      loadedWithTranslation = withTranslation
    }.catch { error ->
      // toast("获取歌词失败")
      Log.e(TAG, error.message.toString())
    }
  }
}
