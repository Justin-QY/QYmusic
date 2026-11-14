package com.qymusic.app.utils

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.drake.engine.utils.AppUtils
import com.drake.net.Get
import com.drake.net.component.Progress
import com.drake.net.interfaces.ProgressListener
import com.drake.tooltip.toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.hjq.permissions.Permission
import com.hjq.permissions.XXPermissions
import kotlinx.coroutines.launch
import com.qymusic.app.config.Api
import com.qymusic.app.model.UpdateModel
import java.io.File
import java.security.MessageDigest

/**
 * App 在线更新。
 *
 * <p>流程：拉取 /app/update.json → 比较 versionCode → 弹窗 → 下载 APK →
 * 校验 MD5 与签名 → 调系统安装器。</p>
 *
 * <p>服务端只暴露版本信息和安装包，不参与签名校验，所以这里必须自己校验，
 * 避免被中间人替换成恶意安装包。</p>
 */
object AppUpdater {

    private const val PREFS = "app_update"
    private const val KEY_LAST_CHECK = "last_check"
    private const val CHECK_INTERVAL = 6 * 60 * 60 * 1000L
    private const val UPDATE_JSON = "/app/update.json"

    /**
     * @param manual 手动检查：忽略 6 小时节流，失败/已最新都会给提示
     */
    fun check(activity: AppCompatActivity, manual: Boolean = false) {
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!manual && now - prefs.getLong(KEY_LAST_CHECK, 0L) < CHECK_INTERVAL) {
            return
        }
        prefs.edit().putLong(KEY_LAST_CHECK, now).apply()

        activity.lifecycleScope.launch {
            val model = runCatching {
                Get<UpdateModel>(Api.BASE_API + UPDATE_JSON).await()
            }.getOrElse { e ->
                if (manual) {
                    toast("检查更新失败：${e.message ?: "网络异常"}")
                }
                return@launch
            }

            val current = AppUtils.getAppVersionCode().toLong()
            if (model.versionCode <= current) {
                if (manual) {
                    toast("当前已是最新版本")
                }
                return@launch
            }
            showDialog(activity, model)
        }
    }

    /**
     * 启动时检查更新。
     *
     * <p>如果服务端下发了最低版本（minVersionCode）且当前版本低于它，就弹不可取消的
     * 强制更新弹窗：不更新无法继续使用，同时服务端也会拒绝返回业务数据。</p>
     */
    fun checkOnStart(activity: AppCompatActivity) {
        activity.lifecycleScope.launch {
            val model = runCatching {
                Get<UpdateModel>(Api.BASE_API + UPDATE_JSON).await()
            }.getOrNull() ?: return@launch

            val current = AppUtils.getAppVersionCode().toLong()
            if (model.versionCode <= current) {
                return@launch
            }

            val forced = model.force || model.minVersionCode > current
            if (!forced) {
                // 普通更新沿用 6 小时节流，避免每次启动都打扰
                val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val now = System.currentTimeMillis()
                if (now - prefs.getLong(KEY_LAST_CHECK, 0L) < CHECK_INTERVAL) {
                    return@launch
                }
                prefs.edit().putLong(KEY_LAST_CHECK, now).apply()
            }

            showDialog(activity, model, forced)
        }
    }

    private fun showDialog(activity: AppCompatActivity, model: UpdateModel, forced: Boolean = model.force) {
        val message = buildString {
            if (forced) {
                append("当前版本已停止服务支持，必须更新后才能继续使用。\n\n")
            }
            if (model.size > 0) {
                append("安装包：").append(model.size.formatBytes()).append('\n')
            }
            if (model.updateTime.isNotBlank()) {
                append("发布时间：").append(model.updateTime).append('\n')
            }
            if (this.isNotEmpty()) {
                append('\n')
            }
            append(model.changelog.ifBlank { "修复问题并优化体验。" })
        }

        val builder = MaterialAlertDialogBuilder(activity)
            .setTitle(if (forced) "请更新到 ${model.versionName}" else "发现新版本 ${model.versionName}")
            .setMessage(message)
            .setCancelable(!forced)

        if (!forced) {
            builder.setNegativeButton("稍后", null)
        }
        builder.setPositiveButton("立即更新", null)

        val dialog = builder.create()
        dialog.show()
        // 强制更新时屏蔽返回键和点击外部
        if (forced) {
            dialog.setCancelable(false)
            dialog.setCanceledOnTouchOutside(false)
        }
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            startDownload(activity, model, dialog)
        }
    }

    private fun startDownload(
        activity: AppCompatActivity,
        model: UpdateModel,
        dialog: androidx.appcompat.app.AlertDialog,
    ) {
        val button = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
        val dir = File(activity.cacheDir, "update").apply { mkdirs() }
        val fileName = "qy-music-${model.versionName.ifBlank { "latest" }}.apk"
        val target = File(dir, fileName)

        activity.lifecycleScope.launch {
            // 上次已下好且校验通过，直接安装
            if (target.isFile && model.md5.isNotBlank() &&
                md5(target).equals(model.md5, ignoreCase = true)
            ) {
                button.text = "安装中"
                install(activity, target, model)
                return@launch
            }

            button.isEnabled = false
            target.delete()

            // 主线路：自有服务器；备用线路：GitHub Release（主线路失败时自动切换）
            val sources = listOf(model.url, model.backupUrl)
                .filter { it.isNotBlank() }
                .map { absoluteUrl(it) }
                .distinct()

            var lastError: Exception? = null
            for ((index, source) in sources.withIndex()) {
                try {
                    if (index > 0) {
                        activity.runOnUiThread { button.text = "切换备用线路…" }
                    }
                    val file = Get<File>(source) {
                        setDownloadDir(dir)
                        setDownloadFileName(fileName)
                        addDownloadListener(object : ProgressListener() {
                            override fun onProgress(p: Progress) {
                                activity.runOnUiThread {
                                    button.text = if (p.finish) "校验中" else "下载中 ${p.progress()}"
                                }
                            }
                        })
                    }.await()

                    if (!file.isFile || file.length() <= 0L) {
                        throw IllegalStateException("下载文件为空")
                    }
                    if (model.md5.isNotBlank() && !md5(file).equals(model.md5, ignoreCase = true)) {
                        file.delete()
                        throw IllegalStateException("安装包校验失败（MD5 不一致）")
                    }
                    if (!verifySignature(activity, file)) {
                        file.delete()
                        throw IllegalStateException("安装包签名与当前版本不一致")
                    }
                    button.text = "安装中"
                    install(activity, file, model)
                    return@launch
                } catch (e: Exception) {
                    lastError = e
                    target.delete()
                }
            }

            button.isEnabled = true
            button.text = "重试"
            toast("更新失败：${lastError?.message ?: "未知错误"}")
        }
    }

    private fun install(activity: AppCompatActivity, file: File, model: UpdateModel) {
        if (!XXPermissions.isGrantedPermissions(activity, Permission.REQUEST_INSTALL_PACKAGES)) {
            XXPermissions.with(activity)
                .permission(Permission.REQUEST_INSTALL_PACKAGES)
                .request { _, allGranted ->
                    if (allGranted) {
                        doInstall(activity, file, model)
                    } else {
                        toast("请允许安装未知应用后重试")
                        offerBrowserDownload(activity, model)
                    }
                }
            return
        }
        doInstall(activity, file, model)
    }

    private fun doInstall(activity: AppCompatActivity, file: File, model: UpdateModel) {
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
        }.onFailure { e ->
            toast("无法启动安装器：${e.message}")
            offerBrowserDownload(activity, model)
        }
    }

    private fun offerBrowserDownload(activity: AppCompatActivity, model: UpdateModel) {
        MaterialAlertDialogBuilder(activity)
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
