package com.qymusic.app.model

import kotlinx.serialization.Serializable

/**
 * 服务端已加载的洛雪（lx-music）v4 音源脚本
 */
@Serializable
data class LxScriptModel(
  val id: String = "",
  val name: String = "",
  val version: String = "",
  val author: String = "",
  /** builtin：随 App 服务端内置；external：用户自行导入 */
  val origin: String = "builtin",
  val sources: List<LxSourceModel> = emptyList(),
) {

  val isExternal: Boolean get() = origin == "external"

  val showName: String get() = name.ifBlank { id }

  /** “内置 / 自定义” */
  val originLabel: String get() = if (isExternal) "自定义" else "内置"

  /** 支持的音乐平台，如 “酷我 / 酷狗 / QQ” */
  val platforms: String
    get() = sources.map { it.platformName }.distinct().joinToString(" / ")

  val title: String
    get() = buildString {
      append(showName)
      if (version.isNotBlank()) {
        append(' ')
        append(version)
      }
    }
}

@Serializable
data class LxSourceModel(
  val source: String = "",
  val qualitys: List<String> = emptyList(),
) {
  val platformName: String
    get() = when (source) {
      "kw" -> "酷我"
      "kg" -> "酷狗"
      "tx" -> "QQ音乐"
      "wy" -> "网易云"
      "mg" -> "咪咕"
      "qs" -> "汽水"
      "bili" -> "B站"
      "ytb" -> "YouTube"
      "xm" -> "虾米"
      "qsvip" -> "QSVIP"
      else -> source
    }
}

/**
 * 导入脚本请求体
 */
@Serializable
data class LxImportRequest(
  val filename: String,
  val code: String,
)

/**
 * 删除脚本请求体
 */
@Serializable
data class LxDeleteRequest(
  val id: String,
)
