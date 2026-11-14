package com.qymusic.app.model

import kotlinx.serialization.Serializable

/**
 * 网易云登录账号信息（服务端 /netease/user/account 返回）
 */
@Serializable
data class NeteaseAccount(
  val userId: Long = 0,
  val nickname: String = "",
  val avatarUrl: String = "",
  val signature: String = "",
)
