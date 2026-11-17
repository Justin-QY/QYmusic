package com.qymusic.app.model

import kotlinx.serialization.Serializable

/** 云端账号（网易云 / QQ / 酷狗 通用） */
@Serializable
data class PlatformAccount(
  val userId: Long = 0,
  val nickname: String = "",
  val avatarUrl: String = "",
  val signature: String = "",
)
