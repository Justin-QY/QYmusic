package com.qymusic.app.utils

import android.content.Context
import java.io.File

/**
 * 本地落雪（LX Music v4）脚本存储。
 *
 * 只保存在 App 私有目录 `filesDir/lx_scripts`，不会上传服务器，
 * 也不会参与服务端音源解析；用于用户自行备份 / 管理脚本。
 */
object LxLocalScriptStore {

  private const val DIR_NAME = "lx_scripts"
  private const val MAX_SIZE = 1024 * 1024 // 1MB

  data class LocalScript(
    val fileName: String,
    val name: String,
    val version: String,
    val size: Long,
  ) {
    val title: String get() = if (version.isBlank()) name else "$name $version"
    val sizeLabel: String get() = when {
      size >= 1024 * 1024 -> String.format("%.1f MB", size / 1024f / 1024f)
      size >= 1024 -> String.format("%.0f KB", size / 1024f)
      else -> "$size B"
    }
  }

  fun dir(context: Context): File =
    File(context.applicationContext.filesDir, DIR_NAME).apply { mkdirs() }

  fun list(context: Context): List<LocalScript> = runCatching {
    dir(context).listFiles()
      ?.filter { it.isFile }
      ?.map { file ->
        val code = runCatching { file.readText() }.getOrDefault("")
        val (name, version) = parseMeta(code, file.nameWithoutExtension)
        LocalScript(file.name, name, version, file.length())
      }
      ?.sortedByDescending { it.name }
      ?: emptyList()
  }.getOrDefault(emptyList())

  fun import(context: Context, fileName: String, code: String): Result<LocalScript> = runCatching {
    require(code.isNotBlank()) { "脚本内容为空" }
    require(code.toByteArray().size <= MAX_SIZE) { "脚本不能超过 1MB" }
    require(looksLikeLxScript(code)) { "不像是落雪 LX 脚本（缺少 info / musicUrl）" }

    val safeName = sanitize(fileName).ifBlank { "custom" }.let {
      if (it.endsWith(".js", ignoreCase = true)) it else "$it.js"
    }
    val target = File(dir(context), safeName)
    target.writeText(code)
    val (name, version) = parseMeta(code, target.nameWithoutExtension)
    LocalScript(target.name, name, version, target.length())
  }

  fun delete(context: Context, fileName: String): Boolean = runCatching {
    File(dir(context), fileName).delete()
  }.getOrDefault(false)

  fun read(context: Context, fileName: String): String? = runCatching {
    File(dir(context), fileName).readText()
  }.getOrNull()

  private fun looksLikeLxScript(code: String): Boolean =
    code.contains("musicUrl") || code.contains("info")

  private fun parseMeta(code: String, fallbackName: String): Pair<String, String> {
    val name = Regex("""name\s*[:=]\s*['"]([^'"]+)['"]""")
      .find(code)?.groupValues?.getOrNull(1)?.trim().orEmpty()
    val version = Regex("""version\s*[:=]\s*['"]([^'"]+)['"]""")
      .find(code)?.groupValues?.getOrNull(1)?.trim().orEmpty()
    return (name.ifBlank { fallbackName }) to version
  }

  private fun sanitize(raw: String): String =
    raw.trim().replace(Regex("""[\\/:*?"<>|]"""), "_")
}
