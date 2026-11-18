package com.qymusic.app.utils

import android.content.ComponentName
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.qymusic.app.QYMusicApplication
import com.qymusic.app.service.MusicService

/**
 * 定时播放（睡眠定时）。
 *
 * - 只在内存里保存到点时间，进程结束即失效，不做跨进程持久化；
 * - 到点后通过 MediaController 暂停当前播放，和 App 内其它播放控制走同一条
 *   Media3 会话链路，不结束 MusicService，通知栏仍可继续播放；
 * - 定时期间保持一个 MediaController 连接，保证到点能立刻下发暂停命令。
 */
object SleepTimer {

  private const val TAG = "SleepTimer"

  /** 暂停后稍等一会再断开控制器，确保暂停命令已经送达会话 */
  private const val RELEASE_DELAY_MS = 1_200L

  private val mainHandler = Handler(Looper.getMainLooper())

  /** 到点时间戳，0 表示没有开启定时 */
  private var deadline = 0L

  private var controller: MediaController? = null

  /** 是否需要在连接成功后保留控制器 */
  private var keepController = false

  /** 是否已有连接在建立中，避免重复创建 MediaController */
  private var connecting = false

  private val listeners = mutableListOf<() -> Unit>()

  private val timeoutRunnable = Runnable { onTimeout() }

  fun isActive(): Boolean = deadline > 0L

  fun remainingMs(): Long {
    if (!isActive()) {
      return 0L
    }
    return (deadline - System.currentTimeMillis()).coerceAtLeast(0L)
  }

  fun addOnChangeListener(listener: () -> Unit) {
    if (!listeners.contains(listener)) {
      listeners.add(listener)
    }
  }

  fun removeOnChangeListener(listener: () -> Unit) {
    listeners.remove(listener)
  }

  /**
   * 开启定时播放。
   *
   * @param minutes 到点后暂停播放的分钟数
   */
  fun start(minutes: Int) {
    if (minutes <= 0) {
      return
    }
    mainHandler.removeCallbacks(timeoutRunnable)
    val delay = minutes * 60_000L
    deadline = System.currentTimeMillis() + delay
    keepController = true
    connectController()
    mainHandler.postDelayed(timeoutRunnable, delay)
    notifyChanged()
  }

  /**
   * 取消定时，不改变当前播放状态。
   */
  fun cancel() {
    mainHandler.removeCallbacks(timeoutRunnable)
    deadline = 0L
    keepController = false
    releaseController()
    notifyChanged()
  }

  private fun onTimeout() {
    mainHandler.removeCallbacks(timeoutRunnable)
    deadline = 0L
    keepController = false

    val current = controller
    controller = null
    if (current == null || !current.isConnected) {
      // 连接还没建立或已断开（例如服务刚被系统重建），重新连一次再暂停
      runCatching { current?.release() }
      pauseWithTemporaryController()
    } else {
      runCatching { current.pause() }
      mainHandler.postDelayed({ runCatching { current.release() } }, RELEASE_DELAY_MS)
    }

    notifyChanged()
    runCatching {
      Toast.makeText(
        QYMusicApplication.context,
        "定时播放时间到，已暂停播放",
        Toast.LENGTH_LONG
      ).show()
    }
  }

  private fun connectController() {
    if (controller != null || connecting) {
      return
    }
    connecting = true
    val context = QYMusicApplication.context
    val sessionToken =
      SessionToken(context, ComponentName(context, MusicService::class.java))
    val future = runCatching {
      MediaController.Builder(context, sessionToken).buildAsync()
    }.getOrNull()
    if (future == null) {
      connecting = false
      Log.w(TAG, "build MediaController failed")
      return
    }
    future.addListener(
      {
        connecting = false
        val connected = runCatching { future.get() }.getOrNull()
        if (connected == null) {
          Log.w(TAG, "connect MediaController failed")
          return@addListener
        }
        if (!keepController) {
          // 连接期间定时已被取消，直接释放
          runCatching { connected.release() }
          return@addListener
        }
        controller = connected
      },
      MoreExecutors.directExecutor()
    )
  }

  private fun releaseController() {
    val current = controller ?: return
    controller = null
    runCatching { current.release() }
  }

  private fun pauseWithTemporaryController() {
    val context = QYMusicApplication.context
    val sessionToken =
      SessionToken(context, ComponentName(context, MusicService::class.java))
    val future = runCatching {
      MediaController.Builder(context, sessionToken).buildAsync()
    }.getOrNull() ?: return
    future.addListener(
      {
        val connected = runCatching { future.get() }.getOrNull() ?: return@addListener
        runCatching { connected.pause() }
        mainHandler.postDelayed({ runCatching { connected.release() } }, RELEASE_DELAY_MS)
      },
      MoreExecutors.directExecutor()
    )
  }

  private fun notifyChanged() {
    listeners.toList().forEach { listener ->
      runCatching { listener.invoke() }
    }
  }
}
