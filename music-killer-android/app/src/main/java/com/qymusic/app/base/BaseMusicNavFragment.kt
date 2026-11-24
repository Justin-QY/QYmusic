package com.qymusic.app.base

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.annotation.LayoutRes
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.databinding.DataBindingUtil
import androidx.databinding.ViewDataBinding
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.navigation.NavController
import androidx.navigation.fragment.findNavController
import androidx.navigation.ui.setupWithNavController
import com.drake.net.Get
import com.drake.net.scope.AndroidScope
import com.drake.net.utils.scope
import com.drake.tooltip.toast
import com.google.android.material.appbar.CollapsingToolbarLayout
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import com.qymusic.app.config.Api
import com.qymusic.app.config.SPConfig
import com.qymusic.app.constants.IntentActions
import com.qymusic.app.constants.IntentExtras
import com.qymusic.app.enums.MusicBridge
import com.qymusic.app.model.MusicModel
import com.qymusic.app.model.PlayInfo
import com.qymusic.app.model.download.DownloadModel
import com.qymusic.app.service.MusicService
import com.qymusic.app.ui.activity.MainActivity
import com.qymusic.app.ui.activity.MainViewModel
import com.qymusic.app.utils.SpUtils.getRequired
import com.qymusic.app.utils.startPlayback
import com.qymusic.app.utils.toBundle

/**
 * 导航基类（不包含Appbar）
 */
abstract class BaseMusicNavFragment<V : ViewDataBinding>(@LayoutRes contentLayoutId: Int = 0) :
  Fragment(contentLayoutId) {

  companion object {
    private const val KEY_FIRST_LOAD = "first_load"
  }

  private var cachedBinding: V? = null

  /**
   * 缓存 ViewBinding，避免每次访问都重新走 DataBindingUtil.bind 查找。
   */
  open val binding: V
    get() = cachedBinding ?: DataBindingUtil.bind<V>(requireView())!!.also { cachedBinding = it }

  protected var isFirstLoad = true
    private set

  protected var isFirstResume = true
    private set

  val activity get() = requireActivity() as MainActivity

  var _mediaController: MediaController? = null
  val mediaController get() = _mediaController!!

  val _downloadService get() = activity._downloadService

  val downloadService get() = _downloadService!!

  val mainViewModel by activityViewModels<MainViewModel>()

  protected val musicModel get() = mainViewModel.musicModel.value

  private var playJob: AndroidScope? = null

  /** MediaController 还在连接中时暂存的播放请求，连上后自动执行 */
  private var pendingPlayRequest: ((MediaController) -> Unit)? = null

  protected lateinit var navController: NavController

  private lateinit var musicBroadReceiver: MusicBroadReceiver

  /**
   * 页码
   */
  var pageNo = 1

  var musicList: MutableList<Any>? = null

  abstract fun initView()

  abstract fun initData()

  /**
   * 第一次页面加载时调用
   */
  protected open fun onFirstLoad() {
    isFirstLoad = false
  }

  protected open fun onFirstResume() {
    isFirstResume = false
  }

  /**
   * 是否开启 MediaController
   *
   * @return true 开启，false 不开启
   */
  protected open fun openMediaController(): Boolean {
    return false
  }

  /**
   * 创建 MediaController 时回调
   */
  protected open fun onCreateMediaController(controller: MediaController) {
  }

  @Suppress("UNCHECKED_CAST")
  private fun eachMusicModels(block: (MusicModel) -> Boolean) {
    val model = musicList?.getOrNull(0)
    val models = if (model is MusicModel) {
      musicList as List<MusicModel>
    } else getMusicModels() as? List<MusicModel>
    models?.forEach {
      if (block.invoke(it)) {
        return@forEach
      }
    }
  }

  /**
   * 播放的MusicModel改变时回调
   */
  protected open fun onMusicModelChanged(musicModel: MusicModel) {
    eachMusicModels {
      if (it.id == musicModel.id && it.source == musicModel.source) {
        it.isSelected = true
      } else if (it.isSelected) {
        it.isSelected = false
        return@eachMusicModels true
      }
      return@eachMusicModels false
    }
  }

  /**
   * 监听音乐收藏状态改变时回调
   */
  protected open fun onMusicFavoriteChanged(musicId: Long, isFavorite: Boolean) {
    if (musicId == -1L) return
    eachMusicModels {
      if (it.id == musicId) {
        it.isFavorite = isFavorite
        return@eachMusicModels true
      }
      return@eachMusicModels false
    }
  }

  /**
   * 获取音乐实体集合，返回此集合将自动处理选中当前播放的音乐
   */
  protected open fun getMusicModels(): List<Any?>? {
    return null
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    navController = findNavController()
    /*savedInstanceState?.let {
      isFirstLoad = it.getBoolean(KEY_FIRST_LOAD, true)
    }*/

    if (openMediaController()) {
      val sessionToken = SessionToken(
        requireContext(),
        ComponentName(requireContext(), MusicService::class.java)
      )
      val controllerFuture = MediaController.Builder(requireContext(), sessionToken).buildAsync()
      controllerFuture.addListener(
        {
          val controller = runCatching { controllerFuture.get() }.getOrNull()
          if (controller == null || lifecycle.currentState == Lifecycle.State.DESTROYED) {
            // 页面在连接完成前已被销毁：直接释放，避免留下无主连接
            controller?.release()
            return@addListener
          }
          _mediaController = controller
          onCreateMediaController(controller)
          // 连接完成前用户已经点了播放：现在补执行，避免第一下点击被吞掉
          pendingPlayRequest?.let { request ->
            pendingPlayRequest = null
            request(controller)
          }
        },
        MoreExecutors.directExecutor()
      )
    }
  }

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)

    initView()
    initData()

    if (isFirstLoad) {
      onFirstLoad()
    }

    if (openMediaController()) {
      viewLifecycleOwner.lifecycleScope.launch {
        mainViewModel.musicModel.collect {
          this@BaseMusicNavFragment.onMusicModelChanged(it)
        }
      }

      val intentFilter = IntentFilter(IntentActions.FAVORITE)
      musicBroadReceiver = MusicBroadReceiver()
      ContextCompat.registerReceiver(
        requireContext(),
        musicBroadReceiver,
        intentFilter,
        ContextCompat.RECEIVER_NOT_EXPORTED
      )
    }
  }

  override fun onResume() {
    super.onResume()
    if (isFirstResume) {
      onFirstResume()
      return
    }
  }

  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    // outState.putBoolean(KEY_FIRST_LOAD, isFirstLoad)
  }

  override fun onDestroyView() {
    cachedBinding = null
    super.onDestroyView()
    if (::musicBroadReceiver.isInitialized) {
      requireContext().unregisterReceiver(musicBroadReceiver)
    }
  }

  override fun onDestroy() {
    // 先取消可能在等网络的播放任务，再释放会话，避免用到已释放的 controller
    playJob?.cancel()
    // 每个页面的 MediaController 都必须释放，否则会话连接会越积越多
    _mediaController?.release()
    _mediaController = null
    super.onDestroy()
  }

  /**
   * 设置 AppBar
   */
  fun setAppbar(toolbar: Toolbar, toolbarLayout: CollapsingToolbarLayout) {
    (requireActivity() as AppCompatActivity).setSupportActionBar(toolbar)
    toolbarLayout.setupWithNavController(toolbar, navController)
  }

  fun setToolbar(toolbar: Toolbar) {
    (requireActivity() as AppCompatActivity).setSupportActionBar(toolbar)
    toolbar.setupWithNavController(navController)
  }

  /**
   * 当前实际使用的音质：开启「移动网络省流量」且正在用流量时强制 128k。
   */
  private fun effectiveBridge(): String {
    val bridge = SPConfig.currentBridge()
    if (!SPConfig.isDataSaver()) {
      return bridge
    }
    return try {
      val cm = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
      val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
      if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) {
        MusicBridge.MP3_128K.level
      } else {
        bridge
      }
    } catch (e: Exception) {
      bridge
    }
  }

  private suspend fun getPlayInfo(musicModel: MusicModel) = coroutineScope {
    // 播放任务前先取消
    var currentBridge = effectiveBridge()
    if (currentBridge == MusicBridge.FLAC_2000K.level && !musicModel.hasLossless) {
      // 如果当前选择的是无损音质，但是音频不支持无损，强制使用下一等级音质
      currentBridge = MusicBridge.MP3_320K.level
    }
    Get<PlayInfo>(Api.PLAY_INFO) {
      addQuery("id", musicModel.id) // 歌曲 id
      addQuery("bridge", currentBridge) // 音质
      addQuery("source", musicModel.source)
      addQuery("sourceId", musicModel.sourceId)
      if (musicModel.source == "qq") {
        addQuery("qqCookie", SPConfig.qqCookie())
      }
      if (musicModel.source == "kg") {
        addQuery("kgCookie", SPConfig.kugouCookie())
      }
      addQuery("name", musicModel.showName)
      addQuery("singer", musicModel.artist)
      addQuery("album", musicModel.album)
      addQuery("duration", musicModel.duration)
    }.await()
  }

  /**
   * 构建播放地址
   */
  private fun MediaItem.Builder.createPlayUrl(musicModel: MusicModel): MediaItem.Builder {
    if (musicModel.localPath.isNotEmpty()) {
      return setUri("file://" + musicModel.localPath)
    }
    // 播放任务前先取消
    var currentBridge = effectiveBridge()
    if (currentBridge == MusicBridge.FLAC_2000K.level && !musicModel.hasLossless) {
      // 如果当前选择的是无损音质，但是音频不支持无损，强制使用下一等级音质
      currentBridge = MusicBridge.MP3_320K.level
    }
    return setUri(
      Uri.Builder()
        .scheme("http")
        .authority("localhost")
        .appendQueryParameter("id", musicModel.id.toString())
        .appendQueryParameter("bridge", currentBridge)
        .appendQueryParameter("source", musicModel.source)
        .appendQueryParameter("sourceId", musicModel.sourceId)
        .apply {
          if (musicModel.source == "qq") {
            appendQueryParameter("qqCookie", SPConfig.qqCookie())
          }
          if (musicModel.source == "kg") {
            appendQueryParameter("kgCookie", SPConfig.kugouCookie())
          }
        }
        .appendQueryParameter("name", musicModel.showName)
        .appendQueryParameter("singer", musicModel.artist)
        .appendQueryParameter("album", musicModel.album)
        .appendQueryParameter("duration", musicModel.duration.toString())
        .build()
    )
  }

  /**
   * 构建 MedialItem 对象
   */
  private fun createMediaItem(musicModel: MusicModel): MediaItem {
    val pic = musicModel.pic120.ifEmpty { musicModel.pic }
    return MediaItem.Builder()
      .createPlayUrl(musicModel)
      .setMediaId(musicModel.id.toString())
      .setMediaMetadata(
        MediaMetadata.Builder()
          // 通知栏 / 锁屏 / 车机都读这里的标题和歌手
          .setTitle(musicModel.name)
          .setArtist(musicModel.artist)
          .setAlbumTitle(musicModel.album)
          .setArtworkUri(pic.toUri())
          .setIsPlayable(true)
          .setExtras(musicModel.toBundle())
          .build()
      )
      .build()
  }

  /**
   * 重新播放当前音频
   */
  protected fun rePlay() {
    _mediaController ?: return
    val musicModel = mainViewModel.musicModel.value
    if (musicModel.id == 0L) {
      // 判断当前没有添加音播播放
      return
    }

    playJob?.cancel()
    playJob = scope {
      // 当前媒体的索引位置
      val currentMediaItemIndex = mediaController.currentMediaItemIndex
      // 如果当前媒体没有索引，视为还未播放
      if (currentMediaItemIndex == C.INDEX_UNSET) {
        // 当前没有播放，添加到播放队列中
        addPlay(musicModel)
        return@scope
      }
      // 获取到当前的媒体音频
      val mediaItem = mediaController.currentMediaItem ?: return@scope
      // 不要再先 stop()：stop() 会把播放器打到 IDLE，紧接着的播放命令会被忽略，
      // 表现为「点了播放没反应，要再点一次」。直接替换地址再播即可。
      val newMediaItem = mediaItem.buildUpon().createPlayUrl(musicModel).build()
      mediaController.replaceMediaItem(currentMediaItemIndex, newMediaItem)
      mediaController.startPlayback()
    }
  }

  protected fun replacePlay(musicModel: MusicModel) {
    _mediaController ?: return
    if (musicModel.id == 0L) {
      return
    }
    playJob?.cancel()
    playJob = scope {
      mainViewModel.updateMusicModel(musicModel)
      val mediaItem = createMediaItem(musicModel)
      val index = mediaController.currentMediaItemIndex
      if (index == C.INDEX_UNSET) {
        addPlay(musicModel)
        return@scope
      }
      mediaController.replaceMediaItem(index, mediaItem)
      mediaController.startPlayback()
    }
  }

  protected fun addDownloadPlay(
    downloadModel: DownloadModel,
    immediate: Boolean = true,
    onError: ((Throwable) -> Unit)? = null
  ) {
    addPlay(
      MusicModel(
        pic = downloadModel.cover,
        name = downloadModel.name,
        id = downloadModel.musicId,
        artist = downloadModel.artist,
        localPath = downloadModel.localPath
      ), immediate, onError
    )
  }

  /**
   * 添加到播放队列
   *
   * @param musicModel 音乐对象
   * @param immediate 是否切换到播放
   * @param onError 错误回调
   */
  fun addPlay(
    musicModel: MusicModel,
    immediate: Boolean = true,
    onError: ((Throwable) -> Unit)? = null
  ) {
    if (_mediaController == null) {
      // Controller 连接是异步的：先挂起请求，连上后自动播放。
      // 原来这里直接 return，冷启动时点歌第一下会完全没反应。
      pendingPlayRequest = { addPlay(musicModel, immediate, onError) }
      return
    }
    val controller = mediaController

    for (i in 0 until controller.mediaItemCount) {
      if (controller.getMediaItemAt(i).mediaId == musicModel.id.toString()) {
        if (immediate) {
          controller.seekTo(i, 0)
          controller.startPlayback()
        } else {
          // 移动到最后
          controller.moveMediaItem(i, controller.mediaItemCount - 1)
        }
        return
      }
    }

    playJob?.cancel()
    playJob = scope {
      val mediaItem = createMediaItem(musicModel)
      // 先记下插入位置：MediaController 的命令是异步下发的，addMediaItem 之后
      // 本地 mediaItemCount 还没更新，再减 1 会 seek 到上一首甚至 -1。
      val newIndex = controller.mediaItemCount
      controller.addMediaItem(mediaItem)

      if (immediate) {
        controller.seekTo(newIndex, 0)
        controller.startPlayback()
        // 直接同步当前歌曲：底部播放栏默认是隐藏的，展开面板里的 MusicPlayFragment
        // 此时可能还没被创建，不能只依赖它的 onMediaItemTransition 回调。
        mainViewModel.updateMusicModel(musicModel)
      }
    }.catch { error ->
      Log.e("Base", error.toString())
      onError?.invoke(error) ?: toast(error.message)
    }
  }

  /**
   * 添加播放列表到播放队列中
   *
   * @param list 播放列表
   * @param clear 是否清空当前的播放队列
   */
  fun addPlaylist(list: List<Any?>, clear: Boolean = true) {
    if (list.isEmpty()) return
    if (_mediaController == null) {
      pendingPlayRequest = { addPlaylist(list, clear) }
      return
    }
    val controller = mediaController

    val mediaItems = list.map { createMediaItem(it as MusicModel) }
    if (clear) {
      playJob?.cancel()
      // setMediaItems 会整体替换队列；不要再先 stop()：
      // stop() 会把播放器打到 IDLE，紧接着的播放命令会被忽略，
      // 这正是「点了歌第一下不播，再点一下播放键才播」的原因。
      controller.setMediaItems(mediaItems)
      controller.seekToDefaultPosition()
      controller.startPlayback()
      // 整单播放时同步第一首歌，保证底部播放栏能显示
      (list.firstOrNull() as? MusicModel)?.let { first ->
        scope { mainViewModel.updateMusicModel(first) }
      }
    } else {
      controller.addMediaItems(mediaItems)
    }
  }

  inner class MusicBroadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
      when (intent.action) {
        IntentActions.FAVORITE -> {
          // 收藏可能来自通知栏（MusicService 直接写库），内存缓存需要失效
          com.qymusic.app.utils.FavoriteRepository.invalidateSongFavorites()
          val musicId = intent.getLongExtra(IntentExtras.MUSIC_ID, -1L)
          val isFavorite = intent.getBooleanExtra(IntentExtras.FAVORITE, false)
          if (musicId == musicModel.id) {
            musicModel.isFavorite = isFavorite
          }
          onMusicFavoriteChanged(musicId, isFavorite)
        }
      }
    }
  }
}