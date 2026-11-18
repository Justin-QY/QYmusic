package com.qymusic.app.utils

import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 定时播放弹窗（睡眠定时）。
 *
 * 设置页入口与首页 / 云端右上角菜单共用这一套交互，避免维护两份：
 * - 未开启时给出 15/30/60/90 分钟预设与自定义分钟数；
 * - 已开启时显示剩余时间，可重新设置或取消。
 */
object SleepTimerDialog {

  private val PRESETS = intArrayOf(15, 30, 60, 90)

  private val LABELS = arrayOf("15 分钟", "30 分钟", "60 分钟", "90 分钟", "自定义分钟数…")

  /**
   * 展示定时播放弹窗。
   *
   * @param context 需要 Activity 主题上下文，MaterialAlertDialogBuilder 才能拿到正确主题
   * @param onChanged 定时状态变化（开启 / 取消）后的回调，用于刷新设置页摘要等
   */
  fun show(context: Context, onChanged: () -> Unit = {}) {
    if (SleepTimer.isActive()) {
      MaterialAlertDialogBuilder(context)
        .setTitle("定时播放")
        .setMessage("剩余 " + formatRemaining(SleepTimer.remainingMs()) + " 后自动暂停播放。")
        .setPositiveButton("重新设置") { _, _ -> showPresets(context, onChanged) }
        .setNeutralButton("取消定时") { _, _ ->
          SleepTimer.cancel()
          onChanged()
          toast(context, "已取消定时播放")
        }
        .setNegativeButton("关闭", null)
        .show()
      return
    }
    showPresets(context, onChanged)
  }

  private fun showPresets(context: Context, onChanged: () -> Unit) {
    val labels = LABELS
    var selected = 0
    MaterialAlertDialogBuilder(context)
      .setTitle("定时播放")
      .setSingleChoiceItems(labels, selected) { _, which -> selected = which }
      .setNegativeButton("取消", null)
      .setPositiveButton("开始") { _, _ ->
        if (selected < PRESETS.size) {
          startTimer(context, onChanged, PRESETS[selected])
        } else {
          showCustomMinutes(context, onChanged)
        }
      }
      .show()
  }

  private fun showCustomMinutes(context: Context, onChanged: () -> Unit) {
    val density = context.resources.displayMetrics.density
    val input = EditText(context).apply {
      inputType = InputType.TYPE_CLASS_NUMBER
      hint = "1 - 600 分钟"
      setSingleLine()
    }
    val container = FrameLayout(context).apply {
      val horizontal = (22 * density).toInt()
      setPadding(horizontal, (8 * density).toInt(), horizontal, 0)
      addView(
        input,
        FrameLayout.LayoutParams(
          FrameLayout.LayoutParams.MATCH_PARENT,
          FrameLayout.LayoutParams.WRAP_CONTENT
        )
      )
    }
    MaterialAlertDialogBuilder(context)
      .setTitle("自定义分钟数")
      .setView(container)
      .setNegativeButton("取消", null)
      .setPositiveButton("开始") { _, _ ->
        val minutes = input.text.toString().trim().toIntOrNull()
        if (minutes == null || minutes <= 0 || minutes > 600) {
          toast(context, "请输入 1 - 600 之间的分钟数")
          return@setPositiveButton
        }
        startTimer(context, onChanged, minutes)
      }
      .show()
  }

  private fun startTimer(context: Context, onChanged: () -> Unit, minutes: Int) {
    SleepTimer.start(minutes)
    onChanged()
    toast(context, "已开启定时播放：$minutes 分钟后暂停")
  }

  /** 小于一小时显示 mm:ss，超过一小时显示 h:mm:ss */
  fun formatRemaining(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0L)
    val hours = totalSeconds / 3600
    val minutes = totalSeconds % 3600 / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
      String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
      String.format("%02d:%02d", minutes, seconds)
    }
  }

  private fun toast(context: Context, message: String) {
    Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show()
  }
}
