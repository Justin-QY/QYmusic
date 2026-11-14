package com.qymusic.app.model

import kotlinx.serialization.Serializable
import com.qymusic.app.utils.formatCN

@Serializable
data class PlayListModel (
  val desc: String = "",
  val id: Long = 0,
  val img: String = "",
  val img700: String = "",
  val info: String = "",
  val listencnt: Long = 0,
  val name: String = "",
  val total: Long = 0,
  val uname: String = "",
  val userName: String = "",
  val tag: String = "",
  val musicList: List<MusicModel> = emptyList()
): java.io.Serializable {

  val listenCountStr: String get() = listencnt.formatCN()

}
