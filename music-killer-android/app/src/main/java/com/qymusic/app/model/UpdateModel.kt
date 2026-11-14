package com.qymusic.app.model

import kotlinx.serialization.Serializable

/**
 * 在线更新信息（服务端 /app/update.json）
 */
@Serializable
data class UpdateModel(
  val versionName: String = "",
  val versionCode: Long = 0,
  /** 安装包地址，支持 /app/xxx.apk 这样的站内路径 */
  val url: String = "",
  val updateTime: String = "",
  /** 更新说明，支持换行 */
  val changelog: String = "",
  /** 安装包大小（字节） */
  val size: Long = 0,
  /** 安装包 MD5，用于下载后校验 */
  val md5: String = "",
  /** 是否强制更新 */
  val force: Boolean = false,
  /** 服务端允许使用的最低版本号；低于它必须更新才能继续使用 */
  val minVersionCode: Long = 0,
)
