package com.qymusic.app.utils

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.drake.net.Get
import com.drake.net.component.Progress
import com.drake.net.interfaces.ProgressListener
import com.drake.tooltip.toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.hjq.permissions.Permission
import com.hjq.permissions.XXPermissions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import com.qymusic.app.BuildConfig
import com.qymusic.app.config.Api
import com.qymusic.app.model.UpdateModel
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * App 在线更新。
 *
 * <p>固定回退流程，不需要用户选择渠道：</p>
 * - 先请求自有服务器 /app/update.json；
 * - 服务器不可用、或服务器版本不高于当前版本时，再请求 GitHub Release API，
 *   读取 tag_name / body / assets 中的 APK；
 * - 服务器下发的 minVersionCode 仍优先于 GitHub 判断。
 *
 * <p>一旦发现新版本都按强制更新处理：弹窗不可取消、没有「稍后」，
 * 下载失败只能重试，不能跳过更新继续使用。</p>
 *
 * <p>服务端只暴露版本信息和安装包，不参与签名校验，所以这里必须自己校验，
 * 避免被中间人替换成恶意安装包。GitHub 没有 MD5 时跳过 MD5，但签名校验始终保留。</p>
 */
object AppUpdater {

    private const val UPDATE_JSON = "/app/update.json"

    /** 同一进程内避免启动检查和手动检查同时进行 */
    private var checking = false

    private sealed class CheckResult {
        class Update(val model: UpdateModel) : CheckResult()
        object Latest : CheckResult()
        class Failed(val detail: String) : CheckResult()
    }


    /**
     * 手动检查更新：忽略节流，失败/已最新都会给提示。
     */
    fun check(activity: AppCompatActivity, manual: Boolean = false) {
        startCheck(activity, manual = manual, startup = false)
    }

    /**
     * 启动时检查更新。
     *
     * <p>强制更新策略下，检查失败不能静默放行：启动检查失败会弹不可取消的
     * 「重试 / 退出应用」弹窗，避免网络异常时直接进入主界面。</p>
     */
    fun checkOnStart(activity: AppCompatActivity) {
        startCheck(activity, manual = false, startup = true)
    }

    private fun startCheck(activity: AppCompatActivity, manual: Boolean, startup: Boolean) {
        if (checking || activity.isFinishing || activity.isDestroyed) {
            return
        }
        checking = true
        activity.lifecycleScope.launch {
            try {
                when (val result = resolve()) {
                    is CheckResult.Update -> showUpdateDialog(activity, result.model)
                    is CheckResult.Failed -> showCheckFailedDialog(
                        activity = activity,
                        detail = result.detail,
                        startup = startup,
                    ) {
                        startCheck(activity, manual, startup)
                    }

                    else -> if (manual) {
                        toast("当前已是最新版本")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 兜底：任何未预期的异常也必须提示，不能静默放行
                showCheckFailedDialog(
                    activity = activity,
                    detail = e.friendlyMessage(),
                    startup = startup,
                ) {
                    startCheck(activity, manual, startup)
                }
            } finally {
                checking = false
            }
        }
    }

    // ------------------------------------------------------------------ 版本判定

    /**
     * 固定回退流程：服务器 → GitHub。
     *
     * - 服务器 minVersionCode 高于当前版本时，直接采用服务器下发的安装包；
     * - 服务器返回的版本高于当前版本时，也用服务器；
     * - 服务器不可用或没有更高版本时，回退 GitHub，tag 版本高于当前版本即强制更新。
     */
    private suspend fun CoroutineScope.resolve(): CheckResult {
        val currentCode = BuildConfig.VERSION_CODE.toLong()
        val currentName = BuildConfig.VERSION_NAME

        val server = runCatching { fetchServer() }
        val model = server.getOrNull()
            ?: return CheckResult.Failed(server.exceptionOrNull()?.friendlyMessage() ?: "网络异常")

        if (model.minVersionCode > currentCode || isNewer(model, currentCode, currentName)) {
            return CheckResult.Update(model)
        }
        return CheckResult.Latest
    }

    private suspend fun CoroutineScope.fetchServer(): UpdateModel {
        val model = Get<UpdateModel>(Api.BASE_API + UPDATE_JSON).await()
        if (model.versionCode <= 0L && model.versionName.isBlank()) {
            throw IllegalStateException("服务器未返回版本信息")
        }
        return model.copy(
            url = if (model.url.isBlank()) "" else absoluteUrl(model.url),
            backupUrl = if (model.backupUrl.isBlank()) "" else absoluteUrl(model.backupUrl),
        )
    }

    /**
     * 有 versionCode 时以 versionCode 为准；没有时才比较 versionName。
     *
     * 版本名可能回退（例如 1.3.10 → 1.0.0），只比 versionName 会把旧版本误判为新版本，
     * 因此 GitHub Release 里写了 versionCode 时，版本名不再参与判断。
     */
    private fun isNewer(model: UpdateModel, currentCode: Long, currentName: String): Boolean {
        if (model.versionCode > 0L) {
            return model.versionCode > currentCode
        }
        return compareVersionName(model.versionName, currentName) > 0
    }

    private fun compareVersionName(left: String, right: String): Int {
        val a = versionNumbers(left)
        val b = versionNumbers(right)
        val size = maxOf(a.size, b.size)
        for (i in 0 until size) {
            val x = a.getOrElse(i) { 0L }
            val y = b.getOrElse(i) { 0L }
            if (x != y) {
                return x.compareTo(y)
            }
        }
        return 0
    }

    private fun versionNumbers(name: String): List<Long> =
        Regex("""\d+""").findAll(name).map { it.value.toLongOrNull() ?: 0L }.toList()

    private fun Throwable.friendlyMessage(): String {
        val text = message.orEmpty().ifBlank { this::class.java.simpleName }
        return text.take(160)
    }

    // ------------------------------------------------------------------ 更新弹窗

    private fun showUpdateDialog(activity: AppCompatActivity, model: UpdateModel) {
        if (activity.isFinishing || activity.isDestroyed) {
            return
        }
        val message = buildString {
            append("发现新版本，必须更新后才能继续使用。\n\n")
            if (model.versionName.isNotBlank()) {
                append("最新版本：").append(model.versionName).append('\n')
            }
            if (model.size > 0) {
                append("安装包：").append(model.size.formatBytes()).append('\n')
            }
            if (model.updateTime.isNotBlank()) {
                append("发布时间：").append(model.updateTime).append('\n')
            }
            append('\n')
            append(model.changelog.ifBlank { "修复问题并优化体验。" })
        }

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle("请更新到 ${model.versionName.ifBlank { "最新版本" }}")
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton("立即更新", null)
            .create()
        // 强制更新：屏蔽返回键与点击外部，且不提供「稍后」
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            startDownload(activity, model, dialog)
        }
    }

    /**
     * 检查失败弹窗。
     *
     * 启动检查：不可取消，只提供「重试 / 退出应用」，避免网络异常时放行进入主界面；
     * 手动检查：已在使用中，提供「重试 / 取消」。
     */
    private fun showCheckFailedDialog(
        activity: AppCompatActivity,
        detail: String,
        startup: Boolean,
        retry: () -> Unit,
    ) {
        if (activity.isFinishing || activity.isDestroyed) {
            return
        }
        val builder = MaterialAlertDialogBuilder(activity)
            .setTitle("检查更新失败")
            .setMessage("检查更新失败，请检查网络后重试。\n\n${detail.ifBlank { "网络异常" }}")
            .setCancelable(!startup)
            .setPositiveButton("重试", null)
        if (startup) {
            builder.setNegativeButton("退出应用") { _, _ -> exitApp(activity) }
        } else {
            builder.setNegativeButton("取消", null)
        }
        val dialog = builder.create()
        if (startup) {
            dialog.setCancelable(false)
            dialog.setCanceledOnTouchOutside(false)
        }
        dialog.show()
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            dialog.dismiss()
            toast("正在重新检查更新…")
            retry()
        }
    }

    private fun exitApp(activity: Activity) {
        try {
            activity.finishAffinity()
        } catch (ignored: Exception) {
        }
        android.os.Process.killProcess(android.os.Process.myPid())
        kotlin.system.exitProcess(0)
    }

    // ------------------------------------------------------------------ 下载与安装

    private fun startDownload(
        activity: AppCompatActivity,
        model: UpdateModel,
        dialog: androidx.appcompat.app.AlertDialog,
    ) {
        val button = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
        val dir = File(activity.cacheDir, "update").apply { mkdirs() }
        val fileName = "qy-music-${model.versionName.ifBlank { "latest" }}.apk"
        val target = File(dir, fileName)
        // 安装器返回 / 权限被拒 / 启动安装失败时，允许再次点击重试（仍不能跳过更新）
        val canRetry = {
            if (!activity.isFinishing && !activity.isDestroyed) {
                button.isEnabled = true
                button.text = "重试"
            }
        }

        activity.lifecycleScope.launch {
            // 上次已下好且校验通过，直接安装；GitHub 没有 MD5 时用签名校验兜底
            if (target.isFile && target.length() > 0L) {
                val md5Matches = model.md5.isBlank() ||
                    md5(target).equals(model.md5, ignoreCase = true)
                if (md5Matches && verifySignature(activity, target)) {
                    button.text = "安装中"
                    install(activity, target, model, canRetry)
                    return@launch
                }
                target.delete()
            }

            button.isEnabled = false
            target.delete()

            // 主线路：当前渠道提供的地址；备用线路：服务器下发的 backupUrl
            val sources = listOf(model.url, model.backupUrl)
                .filter { it.isNotBlank() }
                .map { absoluteUrl(it) }
                .distinct()

            if (sources.isEmpty()) {
                canRetry()
                toast("更新失败：没有可用的下载地址")
                return@launch
            }

            var lastError: Exception? = null
            for ((index, source) in sources.withIndex()) {
                try {
                    if (index > 0) {
                        activity.runOnUiThread { button.text = "切换备用线路…" }
                    }
                    val file = downloadToFile(source, target) { p ->
                        activity.runOnUiThread {
                            button.text = if (p >= 100) "校验中" else "下载中 $p%"
                        }
                    }

                    if (!file.isFile || file.length() <= 0L) {
                        throw IllegalStateException("下载文件为空")
                    }
                    // GitHub 不提供 MD5 时跳过 MD5，但签名校验始终保留
                    if (model.md5.isNotBlank() && !md5(file).equals(model.md5, ignoreCase = true)) {
                        file.delete()
                        throw IllegalStateException("安装包校验失败（MD5 不一致）")
                    }
                    if (!verifySignature(activity, file)) {
                        file.delete()
                        throw IllegalStateException("安装包签名与当前版本不一致")
                    }
                    button.text = "安装中"
                    install(activity, file, model, canRetry)
                    return@launch
                } catch (e: Exception) {
                    lastError = e
                    target.delete()
                }
            }

            // 强制更新：下载失败只能重试，弹窗保持不可取消
            canRetry()
            toast("更新失败：${lastError?.message ?: "未知错误"}")
        }
    }

    private suspend fun downloadToFile(
        url: String,
        target: File,
        onProgress: (Int) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val client = OkHttpClient.Builder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "QYMusic-Android")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body ?: throw IOException("空响应")
            val total = body.contentLength()
            target.parentFile?.mkdirs()
            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var read = 0L
                    var last = -1
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        read += n
                        if (total > 0) {
                            val percent = ((read * 100) / total).toInt().coerceIn(0, 100)
                            if (percent != last) {
                                last = percent
                                onProgress(percent)
                            }
                        }
                    }
                }
            }
        }
        if (!target.isFile || target.length() <= 0L) throw IOException("下载文件为空")
        target
    }

    private fun install(
        activity: AppCompatActivity,
        file: File,
        model: UpdateModel,
        onCanRetry: () -> Unit,
    ) {
        if (!XXPermissions.isGrantedPermissions(activity, Permission.REQUEST_INSTALL_PACKAGES)) {
            XXPermissions.with(activity)
                .permission(Permission.REQUEST_INSTALL_PACKAGES)
                .request { _, allGranted ->
                    if (allGranted) {
                        doInstall(activity, file, model, onCanRetry)
                    } else {
                        toast("请允许安装未知应用后重试")
                        onCanRetry()
                        offerBrowserDownload(activity, model)
                    }
                }
            return
        }
        doInstall(activity, file, model, onCanRetry)
    }

    private fun doInstall(
        activity: AppCompatActivity,
        file: File,
        model: UpdateModel,
        onCanRetry: () -> Unit,
    ) {
        runCatching {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                activity,
                "${activity.packageName}.drake.file_provider",
                file,
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
            // 用户取消系统安装后回到 App，仍停留在强制更新弹窗，可以再次点击安装
            onCanRetry()
        }.onFailure { e ->
            toast("无法启动安装器：${e.message}")
            onCanRetry()
            offerBrowserDownload(activity, model)
        }
    }

    /** 打开 GitHub 上已打包好的 APK（发版时会把安装包覆盖提交到仓库） */
    private fun offerBrowserDownload(activity: AppCompatActivity, model: UpdateModel) {        MaterialAlertDialogBuilder(activity)
            .setTitle("无法自动安装")
            .setMessage("可以用浏览器下载安装包后手动安装。")
            .setNegativeButton("取消", null)
            .setPositiveButton("浏览器下载") { _, _ ->
                runCatching {
                    activity.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(absoluteUrl(model.url)))
                    )
                }.onFailure { toast("没有找到可用的浏览器") }
            }
            .show()
    }

    private fun absoluteUrl(url: String): String =
        if (url.startsWith("http://", true) || url.startsWith("https://", true)) url
        else Api.BASE_API + url

    private fun md5(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * 安装包必须使用与当前 App 相同的签名，否则系统也装不上，这里提前判断并给出提示。
     */
    @Suppress("DEPRECATION")
    private fun verifySignature(activity: Activity, apk: File): Boolean {
        return try {
            val pm = activity.packageManager
            val target = pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNATURES)
                ?: return false
            if (target.packageName != activity.packageName) {
                return false
            }
            val current = pm.getPackageInfo(activity.packageName, PackageManager.GET_SIGNATURES)
            val a = target.signatures?.map { sha256(it.toByteArray()) }?.toSet() ?: emptySet()
            val b = current.signatures?.map { sha256(it.toByteArray()) }?.toSet() ?: emptySet()
            a.isNotEmpty() && b.isNotEmpty() && a.intersect(b).isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
