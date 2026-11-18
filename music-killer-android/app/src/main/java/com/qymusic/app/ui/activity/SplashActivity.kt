package com.qymusic.app.ui.activity

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.drake.net.Get
import com.qymusic.app.BuildConfig
import com.qymusic.app.R
import com.qymusic.app.config.Api
import com.qymusic.app.model.MusicRankModel
import com.qymusic.app.model.Page
import com.qymusic.app.model.PlayListModel
import com.qymusic.app.model.PlayListTagModel
import com.qymusic.app.model.QueryPlayListParams
import com.qymusic.app.utils.query
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 冷启动加载页。
 *
 * 启动后先展示品牌与进度，预取首页「推荐」首屏需要的三个接口
 * （歌单分类、榜单、推荐歌单），完成后淡入 [MainActivity]。
 * 网络失败或超时（[WARM_UP_TIMEOUT_MS]）不会卡住，直接进入 App，
 * 由首页自己的下拉刷新兜底。
 */
class SplashActivity : AppCompatActivity() {

  private companion object {
    /** 最短停留时间，避免数据很快时闪一下 */
    const val MIN_STAY_MS = 600L

    /** 预取最长等待时间，超时直接进入 App */
    const val WARM_UP_TIMEOUT_MS = 4_000L
  }

  private var started = false

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    WindowCompat.setDecorFitsSystemWindows(window, false)
    setContentView(R.layout.activity_splash)

    // 启动页不允许返回键打断
    onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
      override fun handleOnBackPressed() {
      }
    })

    findViewById<TextView>(R.id.tv_version).text =
      "v${BuildConfig.VERSION_NAME} · www.qyder.com"

    // 图标轻微淡入放大，避免画面突然出现
    findViewById<View>(R.id.img_logo).apply {
      alpha = 0f
      scaleX = 0.88f
      scaleY = 0.88f
      animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(360L).start()
    }

    lifecycleScope.launch {
      val minStay = async { delay(MIN_STAY_MS) }
      val warmUp = async { withTimeoutOrNull(WARM_UP_TIMEOUT_MS) { warmUpHomeData() } }

      val ready = warmUp.await() != null
      minStay.await()

      findViewById<TextView>(R.id.tv_status).text =
        if (ready) "即将进入" else "网络较慢，正在进入…"

      if (!isFinishing && !isDestroyed) {
        enterMain()
      }
    }
  }

  /**
   * 预取首页「推荐」页数据，和 [com.qymusic.app.ui.fragment.recommend.RecommendFragment]
   * 首屏使用同一批接口，先跑一遍可以暖连接、减少进入后的白屏时间。
   */
  private suspend fun warmUpHomeData() = coroutineScope {
    val tasks = listOf(
      async {
        runCatching {
          Get<MutableList<PlayListTagModel>>(Api.catalog(Api.PLAYLIST_INDEX_TAGS)).await()
        }
      },
      async {
        runCatching {
          Get<List<MusicRankModel>>(Api.catalog(Api.RANK_INDEX)).await()
        }
      },
      async {
        runCatching {
          Get<Page<PlayListModel>>(Api.catalog(Api.PLAYLIST_RECOMMEND)) {
            query(QueryPlayListParams(id = "rcm"))
          }.await()
        }
      }
    )
    tasks.forEach { it.await() }
  }

  private fun enterMain() {
    if (started) {
      return
    }
    started = true
    startActivity(Intent(this, MainActivity::class.java))
    overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    finish()
  }
}
