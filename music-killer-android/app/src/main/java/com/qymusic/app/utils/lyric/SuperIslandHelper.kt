package com.qymusic.app.utils.lyric

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.qymusic.app.R
import com.qymusic.app.config.SPConfig
import com.qymusic.app.ui.activity.MainActivity
import org.json.JSONObject

/**
 * 澎湃 OS（HyperOS）超级岛 / 焦点通知。
 *
 * 参考实现：C:\OldData\mk\refs\melox
 * - `platform/xiaomi/HyperOsFocusBridge.kt`：用 com.xzakota.hyper.notification:focus-api 的
 *   `FocusNotification.buildV3 {}` 生成 extras，再发一条独立的、HIGH 重要级、静音的
 *   「超级岛歌词」通知（`Notification.Builder.addExtras(focusExtras)`）。
 * - `platform/xiaomi/XiaomiSuperIslandLyricService.kt`：把这条通知放进 specialUse 前台服务，
 *   让 HyperOS 愿意把它渲染成超级岛。
 *
 * 这里的可行部分（不需要任何私有 API / Shizuku）：
 * - 我们按 focus-api 的序列化格式**手写**同一份 extras：
 *   key `miui.focus.param`，value 是 JSON `{"param_v2": { ... }}`；
 *   如需图标/按钮再补 `miui.focus.pics` / `miui.focus.actions`（本实现不需要）。
 * - 这条 extras 挂在一条独立的高优先级静音通知上，由正在播放的 MusicService 发布，
 *   因此不依赖 HyperOS 的 Binder / 私有接口，任何设备上都只是「多一条普通通知」，
 *   不会崩溃。
 *
 * 已知限制（无法在第三方 App 内完全解决，见 MeloX 的 Shizuku 兼容逻辑）：
 * - 部分 HyperOS 版本对「普通前台服务里的第二条通知」只按普通通知处理，需要
 *   specialUse 前台服务 + 可选 Shizuku 防火墙 pulse 才会稳定上岛；
 *   本工程不引入 Shizuku / 额外前台服务，开关保留，最差情况退化为普通通知。
 * - 焦点通知协议版本由系统设置 `notification_focus_protocol` 决定（MeloX 也读这个
 *   值），系统不识别 extras 时会直接忽略，不会报错。
 */
object SuperIslandHelper {

  private const val TAG = "SuperIsland"

  /** 独立通道：HyperOS 要求「专注 / 焦点」通道，普通媒体通知通道无法升级重要级 */
  const val CHANNEL_ID = "qymusic_super_island_lyrics"

  private const val CHANNEL_NAME = "超级岛歌词"

  /** 与媒体通知区分开，避免互相覆盖 */
  const val NOTIFICATION_ID = 1761

  /** 系统里保存焦点通知协议版本的 key，MeloX 用它判断是否 HyperOS 3 */
  private const val FOCUS_PROTOCOL_SETTING = "notification_focus_protocol"

  /** 是否小米系机型（HyperOS 只在这些机型上存在） */
  fun isXiaomiFamily(): Boolean {
    val brand = (Build.MANUFACTURER + " " + Build.BRAND).lowercase()
    return brand.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco")
  }

  /** 读取系统焦点通知协议版本，读不到按 0 处理（非小米 / 老系统） */
  fun focusProtocol(context: Context): Int = runCatching {
    Settings.System.getInt(context.contentResolver, FOCUS_PROTOCOL_SETTING, 0)
  }.getOrDefault(0)

  /**
   * 设备是否可能支持超级岛：小米系 + Android 14（HyperOS 1）及以上。
   * 真正的渲染结果仍由系统决定，这里只用于「要不要发」以及设置页提示。
   */
  fun isSupported(context: Context): Boolean =
    Build.VERSION.SDK_INT >= 34 && isXiaomiFamily()

  /**
   * 发布 / 更新超级岛歌词通知。
   *
   * 所有异常都在内部吞掉（权限被关、厂商改协议、JSON 不被识别……），
   * 绝不影响播放与媒体通知。
   *
   * @return 是否真正发出了通知（仅用于日志与提示）
   */
  fun publish(
    context: Context,
    title: String,
    artist: String,
    lyric: String,
    nextLyric: String,
    progress: Int,
    isPlaying: Boolean,
  ): Boolean {
    if (!SPConfig.isLyricSuperIslandEnabled()) {
      return false
    }
    if (!isSupported(context)) {
      return false
    }
    return try {
      val appContext = context.applicationContext
      val manager = NotificationManagerCompat.from(appContext)
      if (!manager.areNotificationsEnabled()) {
        return false
      }
      ensureChannel(appContext)

      val line = lyric.trim().ifBlank { title.trim() }.ifBlank { "QY MUSIC" }
      val label = listOf(title.trim(), artist.trim())
        .filter { it.isNotBlank() }
        .joinToString(" · ")
        .ifBlank { "QY MUSIC" }

      val extras = buildFocusExtras(appContext, title, artist, line, nextLyric, progress)
      val safeProgress = progress.coerceIn(0, 100)

      val notification = if (Build.VERSION.SDK_INT >= 36) {
        // Android 16 / HyperOS 4：用系统 Live Update 通知，锁屏/通知栏/超级岛可以原生渲染
        val style = Notification.ProgressStyle()
          .setStyledByProgress(false)
          .setProgress(safeProgress)
          .setProgressPoints(listOf(Notification.ProgressStyle.Point(safeProgress)))
        Notification.Builder(appContext, CHANNEL_ID)
          .setSmallIcon(R.drawable.baseline_lyrics_24)
          .setContentTitle(line)
          .setContentText(label)
          .setStyle(style)
          .setFlag(Notification.FLAG_PROMOTED_ONGOING, true)
          .setShortCriticalText(line.take(24))
          .setCategory(Notification.CATEGORY_TRANSPORT)
          .setVisibility(Notification.VISIBILITY_PUBLIC)
          .setShowWhen(false)
          .setOnlyAlertOnce(true)
          .setOngoing(isPlaying)
          .setContentIntent(openAppIntent(appContext))
          .addExtras(extras)
          .build()
      } else {
        NotificationCompat.Builder(appContext, CHANNEL_ID)
          .setSmallIcon(R.drawable.baseline_lyrics_24)
          .setContentTitle(line)
          .setContentText(label)
          .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
          .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
          .setShowWhen(false)
          .setOnlyAlertOnce(true)
          .setSilent(true)
          .setLocalOnly(true)
          .setOngoing(isPlaying)
          .setContentIntent(openAppIntent(appContext))
          .addExtras(extras)
          .build()
      }

      manager.notify(NOTIFICATION_ID, notification)
      Log.d(TAG, "super island payload published: $line")
      true
    } catch (e: SecurityException) {
      Log.w(TAG, "notification permission denied: ${e.message}")
      false
    } catch (e: Throwable) {
      // 厂商协议 / ROM 服务异常都不允许冒泡到播放逻辑
      Log.w(TAG, "publish super island failed: ${e.message}")
      false
    }
  }

  /** 关闭超级岛开关或停止播放时移除，避免留下一条常驻通知 */
  fun clear(context: Context) {
    runCatching {
      NotificationManagerCompat.from(context.applicationContext).cancel(NOTIFICATION_ID)
    }.onFailure { Log.w(TAG, "clear super island failed: ${it.message}") }
  }

  private fun openAppIntent(context: Context): PendingIntent {
    val intent = Intent(context, MainActivity::class.java).apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
    return PendingIntent.getActivity(
      context,
      NOTIFICATION_ID,
      intent,
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
  }

  /**
   * 按 focus-api 1.4 的序列化结构手写 extras。
   *
   * 顶层：`{"param_v2": <FocusTemplateV3>}`（V3 工厂的 SerialName 就是 param_v2）。
   * 内部对应 MeloX `HyperOsFocusBridge.buildV3 {}` 的字段：
   * business / isShowNotification / enableFloat / updatable / islandFirstFloat /
   * aodTitle / ticker / chatInfo / progressInfo / param_island(bigIslandArea)。
   */
  private fun buildFocusExtras(
    context: Context,
    title: String,
    artist: String,
    lyric: String,
    nextLyric: String,
    progress: Int,
  ): Bundle {
    val safeProgress = progress.coerceIn(0, 100)
    val current = lyric.take(42).ifBlank { "♪" }
    val next = nextLyric.trim().take(42).ifBlank { current }

    val chatInfo = JSONObject().apply {
      put("title", current)
      put("content", listOf(title.trim(), artist.trim())
        .filter { it.isNotBlank() }
        .joinToString(" · ")
        .ifBlank { "QY MUSIC" })
      put("appIconPkg", context.packageName)
    }

    val progressInfo = JSONObject().apply {
      put("progress", safeProgress)
      put("colorProgress", "#FF757575")
      put("colorProgressEnd", "#FF757575")
    }

    val bigIslandArea = JSONObject().apply {
      put("imageTextInfoLeft", JSONObject().apply {
        put("type", 1)
        put("textInfo", JSONObject().apply {
          put("title", current)
          put("showHighlightColor", false)
          put("narrowFont", false)
        })
      })
      put("textInfo", JSONObject().apply {
        put("title", next)
        put("showHighlightColor", false)
        put("narrowFont", false)
      })
    }

    val smallIslandArea = JSONObject().apply {
      put("combinePicInfo", JSONObject().apply {
        put("progressInfo", JSONObject().apply {
          put("progress", safeProgress)
          put("colorReach", "#FF757575")
          put("colorUnReach", "#333333")
        })
      })
    }

    val island = JSONObject().apply {
      put("islandProperty", 1)
      put("bigIslandArea", bigIslandArea)
      put("smallIslandArea", smallIslandArea)
    }

    val param = JSONObject().apply {
      put("business", "lyric_display")
      put("isShowNotification", true)
      put("enableFloat", false)
      put("updatable", true)
      put("islandFirstFloat", false)
      put("ticker", current)
      put("aodTitle", current.take(20).ifBlank { "♪" })
      put("chatInfo", chatInfo)
      put("progressInfo", progressInfo)
      put("param_island", island)
    }

    val root = JSONObject().put("param_v2", param)

    return Bundle().apply {
      putString("miui.focus.param", root.toString())
    }
  }

  private fun ensureChannel(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
      return
    }
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
      ?: return
    if (manager.getNotificationChannel(CHANNEL_ID) != null) {
      return
    }
    val channel = NotificationChannel(
      CHANNEL_ID,
      CHANNEL_NAME,
      NotificationManager.IMPORTANCE_HIGH,
    ).apply {
      description = "在澎湃 OS 超级岛显示当前歌词"
      setSound(null, null)
      enableVibration(false)
      setShowBadge(false)
      lockscreenVisibility = Notification.VISIBILITY_PUBLIC
    }
    manager.createNotificationChannel(channel)
  }
}
