package com.qymusic.app.ui.activity

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.media3.common.Player
import com.drake.net.utils.scope
import com.drake.net.utils.withIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.litepal.LitePal
import org.litepal.extension.deleteAll
import org.litepal.extension.findFirst
import com.qymusic.app.QYMusicApplication
import com.qymusic.app.constants.IntentActions
import com.qymusic.app.constants.IntentExtras
import com.qymusic.app.model.FavoriteModel
import com.qymusic.app.model.MusicModel
import com.qymusic.app.model.PlayHistory

class MainViewModel : ViewModel() {

  private val _bottomBarUIState = MutableStateFlow(BottomBarState(isVisible = false))
  val bottomBarUIState: StateFlow<BottomBarState> = _bottomBarUIState.asStateFlow()

  private val _musicModel =
    MutableStateFlow(MusicModel(pic = "", name = "", artist = ""))
  val musicModel = _musicModel.asStateFlow()

  /** 当前页面是否允许展示底部播放栏（由导航目的地决定） */
  private var bottomBarAllowed = true

  private val _repeatMode = MutableStateFlow(Player.REPEAT_MODE_ALL)
  val repeatMode: StateFlow<Int> = _repeatMode.asStateFlow()

  private val _isPlaying = MutableStateFlow(false)
  val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

  private val _currentPosition = MutableStateFlow(0L)
  val currentPosition: StateFlow<Long> = _currentPosition.asStateFlow()

  fun changeBottomBarVisible(isVisible: Boolean = true) {
    bottomBarAllowed = isVisible
    syncBottomBarState()
  }

  fun changeBottomBarExpand(isExpanded: Boolean = false) {
    // 没有开始播放任何歌曲时不展开播放面板
    if (isExpanded && !hasMusicStarted()) {
      return
    }
    _bottomBarUIState.update {
      BottomBarState(isVisible = it.isVisible, isExpanded = isExpanded)
    }
  }

  /**
   * 底部播放栏只在「已经切到某首歌」之后显示，播放器启动前完全隐藏，
   * 避免冷启动时停在「请选择音乐播放」的占位状态。
   */
  private fun syncBottomBarState() {
    val hasMusic = hasMusicStarted()
    _bottomBarUIState.update {
      BottomBarState(
        isVisible = bottomBarAllowed && hasMusic,
        isExpanded = hasMusic && it.isExpanded
      )
    }
  }

  private fun hasMusicStarted() = _musicModel.value.id != 0L

  suspend fun updateMusicModel(musicModel: MusicModel) {
    musicModel.isFavorite = getMusicFavorite(musicModel) != -1L
    _musicModel.value = musicModel
    syncBottomBarState()

    withIO {
      // 加入到播放历史中
      PlayHistory.from(musicModel).saveOrUpdate("musicId = ?", musicModel.id.toString())
    }
  }

  fun updateRepeatMode(@Player.RepeatMode mode: Int) {
    _repeatMode.value = mode
  }

  fun updateIsPlaying(isPlaying: Boolean = !_isPlaying.value) {
    _isPlaying.value = isPlaying
  }

  fun updateCurrentPosition(position: Long) {
    _currentPosition.value = position
  }

  suspend fun getMusicModelState(model: MusicModel) {
    model.isSelected = _musicModel.value.id == model.id
    model.isFavorite = isFavoriteMusic(model)
  }

  suspend fun getMusicFavorite(musicModel: MusicModel) = withIO {
    if (musicModel.id == 0L) return@withIO -1
    try {
      val source = musicModel.source
      val condition = if (source == "kw") {
        "${IntentExtras.MUSIC_ID} = ? and (source = ? or source is null)"
      } else {
        "${IntentExtras.MUSIC_ID} = ? and source = ?"
      }
      val favoriteModel = LitePal.where(condition, musicModel.id.toString(), source)
        .findFirst<FavoriteModel>()
      favoriteModel?.id ?: -1L
    } catch (e: Exception) {
      // 数据库结构异常（例如老版本缺少 source 列）时不能把整页列表带崩，
      // 这里退化成「未收藏」，收藏动作会在写库时再判断一次。
      -1L
    }
  }

  suspend fun isFavoriteMusic(musicModel: MusicModel): Boolean {
    return getMusicFavorite(musicModel) != -1L
  }

  fun addOrRemoveFavorite(model: MusicModel = musicModel.value) {
    if (model.id <= 0L) {
      return
    }
    scope(Dispatchers.IO) {
      try {
        if (!isFavoriteMusic(model)) {
          model.isFavorite = true
          FavoriteModel(model).save()
        } else {
          model.isFavorite = false
          val source = model.source
          val condition = if (source == "kw") {
            "${IntentExtras.MUSIC_ID} = ? and (source = ? or source is null)"
          } else {
            "${IntentExtras.MUSIC_ID} = ? and source = ?"
          }
          LitePal.deleteAll<FavoriteModel>(condition, model.id.toString(), source)
        }
      } catch (e: Exception) {
        model.isFavorite = !model.isFavorite
        android.widget.Toast.makeText(
          QYMusicApplication.context,
          "收藏失败，请稍后重试",
          android.widget.Toast.LENGTH_SHORT
        ).show()
        return@scope
      }
      QYMusicApplication.context.sendBroadcast(
        Intent(IntentActions.FAVORITE)
          .setPackage(QYMusicApplication.context.packageName)
          .putExtra(IntentExtras.MUSIC_ID, model.id)
          .putExtra(IntentExtras.FAVORITE, model.isFavorite)
      )
    }
  }

  data class BottomBarState(
    val isExpanded: Boolean = false,
    val isVisible: Boolean = true,
  )
}