package com.qymusic.app.model

import kotlinx.serialization.Serializable

/** 扫码创建结果：网易云返回 key，QQ 返回 qrsig；image 为 PNG Base64 */
@Serializable
data class QrCreate(
  val key: String = "",
  val qrsig: String = "",
  val image: String = "",
)

/** 扫码轮询结果 */
@Serializable
data class QrPoll(
  val status: String = "waiting",
  val cookie: String = "",
)
