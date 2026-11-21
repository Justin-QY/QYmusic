package com.qymusic.app.utils.lyric

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * 超级岛歌词的专用前台服务。
 *
 * HyperOS 对「普通 notify() 发出来的焦点通知」通常只按普通通知处理，
 * 只有把同一条通知交给 specialUse 前台服务（[startForeground]）发布，
 * 系统才会把它升级成超级岛。
 *
 * 服务本身不播放音频，只负责承载这条通知：
 * - [publish]：更新通知（已在运行则原地刷新）；
 * - [stop]：停止服务并移除通知。
 */
class SuperIslandService : Service() {

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_STOP) {
      pendingNotification = null
      stopForegroundCompat()
      stopSelf()
      return START_NOT_STICKY
    }

    val notification = pendingNotification
    if (notification == null) {
      running = false
      stopSelf()
      return START_NOT_STICKY
    }

    val started = runCatching { startWithNotification(notification) }
      .onFailure { Log.w(TAG, "startForeground failed: ${it.message}") }
      .isSuccess
    running = started
    if (!started) {
      stopSelf()
    }
    return START_NOT_STICKY
  }

  private fun startWithNotification(notification: Notification) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      startForeground(
        NOTIFICATION_ID,
        notification,
        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
      )
    } else {
      startForeground(NOTIFICATION_ID, notification)
    }
  }

  private fun stopForegroundCompat() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
      stopForeground(STOP_FOREGROUND_REMOVE)
    } else {
      @Suppress("DEPRECATION")
      stopForeground(true)
    }
  }

  override fun onDestroy() {
    running = false
    super.onDestroy()
  }

  companion object {

    private const val TAG = "SuperIslandService"

    /** 与 SuperIslandHelper.NOTIFICATION_ID 保持一致，避免出现两条常驻通知 */
    const val NOTIFICATION_ID = SuperIslandHelper.NOTIFICATION_ID

    private const val ACTION_PUBLISH = "com.qymusic.app.action.SUPER_ISLAND_PUBLISH"
    private const val ACTION_STOP = "com.qymusic.app.action.SUPER_ISLAND_STOP"

    @Volatile
    private var pendingNotification: Notification? = null

    @Volatile
    private var running = false

    fun publish(context: Context, notification: Notification): Boolean {
      pendingNotification = notification
      val appContext = context.applicationContext
      val intent = Intent(appContext, SuperIslandService::class.java)
        .setAction(ACTION_PUBLISH)

      // 已在运行就普通 startService 刷新通知，避免每次歌词都走 startForegroundService
      val updated = running && runCatching { appContext.startService(intent) }.isSuccess
      if (updated) {
        return true
      }

      val started = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
          appContext.startForegroundService(intent)
        } else {
          appContext.startService(intent)
        }
      }.onFailure { Log.w(TAG, "start SuperIslandService failed: ${it.message}") }
        .isSuccess
      if (!started) {
        running = false
      }
      return started
    }

    fun stop(context: Context) {
      pendingNotification = null
      running = false
      val appContext = context.applicationContext
      runCatching {
        appContext.stopService(Intent(appContext, SuperIslandService::class.java))
      }.onFailure { Log.w(TAG, "stop SuperIslandService failed: ${it.message}") }
      runCatching {
        appContext.getSystemService(android.app.NotificationManager::class.java)
          ?.cancel(NOTIFICATION_ID)
      }
    }
  }
}
