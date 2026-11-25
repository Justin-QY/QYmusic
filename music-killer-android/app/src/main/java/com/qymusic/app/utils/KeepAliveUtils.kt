package com.qymusic.app.utils

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * 后台保活：状态查询 + 系统设置入口。
 *
 * 系统清理后台进程会让播放服务一起消失（表现为回到 App 时重新加载、歌被暂停），
 * 这里统一提供「忽略电池优化 / 通知权限 / 自启动管理」的检测和跳转，
 * 启动弹窗与「通用设置 → 后台保活」共用。
 */
object KeepAliveUtils {

  /** 是否已允许忽略电池优化（6.0 以下没有该限制） */
  fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
      return true
    }
    val powerManager = context.getSystemService(PowerManager::class.java) ?: return true
    return powerManager.isIgnoringBatteryOptimizations(context.packageName)
  }

  /** 是否已授予通知权限（13 以下默认视为有权限） */
  fun hasNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
      ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
      PackageManager.PERMISSION_GRANTED

  /** 是否已经全部配置好：忽略电池优化 + 通知权限 */
  fun isFullyConfigured(context: Context): Boolean =
    isIgnoringBatteryOptimizations(context) && hasNotificationPermission(context)

  fun requestIgnoreBatteryOptimizations(activity: Activity) {
    val intent = Intent(
      Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
      Uri.parse("package:${activity.packageName}"),
    )
    runCatching { activity.startActivity(intent) }.onFailure { openAppSettings(activity) }
  }

  /** 小米 / 澎湃 OS 的自启动管理页，非小米设备回落到应用详情 */
  fun openAutoStartSettings(activity: Activity) {
    val intent = Intent().apply {
      component = ComponentName(
        "com.miui.securitycenter",
        "com.miui.permcenter.autostart.AutoStartManagementActivity",
      )
    }
    runCatching { activity.startActivity(intent) }.onFailure { openAppSettings(activity) }
  }

  fun openAppSettings(activity: Activity) {
    runCatching {
      activity.startActivity(
        Intent(
          Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
          Uri.parse("package:${activity.packageName}"),
        )
      )
    }
  }

  /** 设置页入口右侧摘要 */
  fun statusSummary(context: Context): String {
    val battery = if (isIgnoringBatteryOptimizations(context)) "电池优化已关闭" else "电池优化未关闭"
    val notice = if (hasNotificationPermission(context)) "通知已开启" else "通知未开启"
    return "$battery · $notice"
  }
}
