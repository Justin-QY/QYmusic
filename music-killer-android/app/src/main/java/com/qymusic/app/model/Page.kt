package com.qymusic.app.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Page<T>(
  val total: Long = 0,
  val data: List<T>,
  @SerialName("pn")
  var page: Int = 1,
)
