package com.qymusic.app.utils.lyric

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.InvocationTargetException

/**
 * 可选的小米超级岛兼容层（基于 Shizuku）。
 *
 * 背景：HyperOS 在渲染第三方「焦点通知 / 超级岛」前，会由 XMSF 服务做一次白名单校验。
 * 部分机型（尤其是 HyperOS 2/3/4）因为校验失败，只把通知当普通通知显示。
 * 已知可行的绕行方式是：在发布焦点通知前，用 Shizuku 临时切断 `com.xiaomi.xmsf`
 * 的网络（OEM 防火墙链 9），让校验失败放行，发布后立刻恢复。
 *
 * 权限边界：
 * - 没有安装 / 没有运行 / 没有授权 Shizuku 时，本类不执行任何系统操作，
 *   [pulse] 直接执行传入的发布动作，App 行为与之前完全一致；
 * - 只有在设置页显式授权后，才会在每次更新歌词通知时做一次 100ms 左右的防火墙脉冲。
 *
 * 该实现只依赖公开的 Shizuku API 与系统 Binder 的反射调用，不内置任何第三方代码。
 */
object ShizukuXmsfHelper {

  private const val TAG = "SuperIslandShizuku"

  private const val XMSF_PACKAGE = "com.xiaomi.xmsf"
  private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

  /** OEM 防火墙链：9 = deny 链，规则 0 = 默认，2 = 拒绝 */
  private const val OEM_DENY_CHAIN = 9
  private const val RULE_DEFAULT = 0
  private const val RULE_DENY = 2

  private const val PULSE_MS = 120L
  private const val REQUEST_CODE = 4720

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val mutex = Mutex()

  @Volatile
  private var currentXmsfUid: Int? = null

  /** 诊断用：最近一次防火墙脉冲结果 */
  @Volatile
  var lastPulseResult: String = "未尝试"
    private set

  fun isShizukuInstalled(context: Context): Boolean = runCatching {
    context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
    true
  }.getOrDefault(false)

  fun isBinderAlive(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

  fun hasPermission(): Boolean = runCatching {
    Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
  }.getOrDefault(false)

  /** 设置页副标题：未安装 / 未运行 / 未授权 / 已启用 */
  fun statusLabel(context: Context): String = when {
    !isShizukuInstalled(context) -> "未安装 Shizuku，点击了解"
    !isBinderAlive() -> "Shizuku 未运行，点击打开"
    !hasPermission() -> "未授权，点击授权"
    else -> "已启用兼容模式"
  }

  /**
   * 请求 Shizuku 授权。必须在设置页由用户主动点击时调用。
   */
  fun requestPermission(activity: Activity, onResult: (Boolean) -> Unit) {
    if (hasPermission()) {
      onResult(true)
      return
    }
    if (!isBinderAlive()) {
      onResult(false)
      return
    }
    activity.runOnUiThread {
      val listener = object : Shizuku.OnRequestPermissionResultListener {
        override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
          if (requestCode != REQUEST_CODE) {
            return
          }
          Shizuku.removeRequestPermissionResultListener(this)
          val granted = grantResult == PackageManager.PERMISSION_GRANTED
          if (granted) {
            restoreAsync(activity.applicationContext)
          }
          onResult(granted)
        }
      }
      Shizuku.addRequestPermissionResultListener(listener)
      runCatching { Shizuku.requestPermission(REQUEST_CODE) }
        .onFailure {
          Log.w(TAG, "requestPermission failed: ${it.message}")
          Shizuku.removeRequestPermissionResultListener(listener)
          onResult(false)
        }
    }
  }

  /**
   * 用一次短脉冲包住「发布焦点通知」：
   * 有权限时 block → 发布 → 等待 → 恢复；无权限时直接发布。
   */
  fun pulse(context: Context, block: () -> Unit) {
    if (!hasPermission()) {
      lastPulseResult = "未授权（直接发布）"
      runCatching(block)
      return
    }
    val appContext = context.applicationContext
    scope.launch {
      mutex.withLock {
        val uid = resolveXmsfUid(appContext)
        if (uid == null) {
          lastPulseResult = "未找到 com.xiaomi.xmsf"
          runCatching(block)
          return@withLock
        }
        var blockError = ""
        val blocked = runCatching { applyFirewallRule(uid, enabled = false) }
          .onFailure {
            blockError = it.message ?: it.javaClass.simpleName
            Log.w(TAG, "block xmsf failed: ${it.message}")
          }
          .isSuccess
        if (!blocked) {
          lastPulseResult = "切断失败：$blockError"
        }
        try {
          runCatching(block)
        } finally {
          if (blocked) {
            runCatching { delay(PULSE_MS) }
            val restored = runCatching { applyFirewallRule(uid, enabled = true) }
              .onFailure { Log.w(TAG, "restore xmsf failed: ${it.message}") }
              .isSuccess
            lastPulseResult = if (restored) "切断并恢复成功" else "恢复失败"
          }
        }
      }
    }
  }

  /**
   * 冷启动兜底：如果上次进程在脉冲中途被杀，这里把 XMSF 网络恢复成默认规则。
   */
  fun restoreAsync(context: Context) {
    if (!hasPermission()) {
      return
    }
    val appContext = context.applicationContext
    scope.launch {
      mutex.withLock {
        val uid = resolveXmsfUid(appContext) ?: return@withLock
        runCatching { applyFirewallRule(uid, enabled = true) }
          .onFailure { Log.w(TAG, "startup restore xmsf failed: ${it.message}") }
      }
    }
  }

  private fun resolveXmsfUid(context: Context): Int? {
    currentXmsfUid?.let { return it }
    val uid = runCatching {
      context.packageManager.getPackageUid(XMSF_PACKAGE, 0)
    }.getOrNull() ?: return null
    currentXmsfUid = uid
    return uid
  }

  private fun installHiddenApiExemptions() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
      return
    }
    runCatching { HiddenApiBypass.addHiddenApiExemptions("") }
      .onFailure { Log.w(TAG, "addHiddenApiExemptions failed: ${it.message}") }
  }

  private fun applyFirewallRule(uid: Int, enabled: Boolean) {
    installHiddenApiExemptions()
    val backends = listOf(
      "connectivity" to "android.net.IConnectivityManager\$Stub",
      "network_management" to "android.os.INetworkManagementService\$Stub",
    )
    var lastError: Throwable? = null
    for ((serviceName, stubClass) in backends) {
      try {
        val service = wrappedService(serviceName, stubClass) ?: continue
        val rule = if (enabled) RULE_DEFAULT else RULE_DENY
        if (!enabled) {
          runCatching { call(service, listOf("setFirewallChainEnabled"), OEM_DENY_CHAIN, true) }
        }
        call(
          service,
          listOf("setUidFirewallRule", "setFirewallUidRule"),
          OEM_DENY_CHAIN,
          uid,
          rule,
        )
        return
      } catch (error: Throwable) {
        lastError = error
        Log.w(TAG, "firewall backend $serviceName failed: ${error.message}")
      }
    }
    throw lastError ?: IllegalStateException("no firewall backend available")
  }

  private fun wrappedService(serviceName: String, stubClassName: String): Any? {
    val binder = SystemServiceHelper.getSystemService(serviceName) ?: return null
    val stub = Class.forName(stubClassName)
    val asInterface = stub.getMethod("asInterface", IBinder::class.java)
    return asInterface.invoke(null, ShizukuBinderWrapper(binder))
  }

  private fun call(target: Any, names: List<String>, vararg args: Any) {
    val methods = target.javaClass.methods.filter {
      it.name in names && it.parameterCount == args.size
    }
    if (methods.isEmpty()) {
      throw NoSuchMethodException(names.joinToString())
    }
    var lastError: Throwable? = null
    for (method in methods) {
      try {
        invoke(target, method, args)
        return
      } catch (error: Throwable) {
        lastError = error
      }
    }
    throw lastError ?: NoSuchMethodException(names.joinToString())
  }

  private fun invoke(target: Any, method: java.lang.reflect.Method, args: Array<out Any>) {
    method.isAccessible = true
    val adapted = Array(args.size) { index ->
      val expected = method.parameterTypes[index]
      val value = args[index]
      when {
        expected == Int::class.javaPrimitiveType -> when (value) {
          is Boolean -> if (value) 1 else 0
          is Number -> value.toInt()
          else -> error("unsupported int argument")
        }
        expected == Boolean::class.javaPrimitiveType -> when (value) {
          is Boolean -> value
          is Number -> value.toInt() != 0
          else -> error("unsupported boolean argument")
        }
        else -> value
      }
    }
    try {
      method.invoke(target, *adapted)
    } catch (error: InvocationTargetException) {
      throw error.targetException ?: error
    }
  }
}
