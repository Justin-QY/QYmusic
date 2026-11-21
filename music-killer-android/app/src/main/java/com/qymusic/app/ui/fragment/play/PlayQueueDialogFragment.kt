package com.qymusic.app.ui.fragment.play

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.drake.brv.utils.bindingAdapter
import com.drake.brv.utils.linear
import com.drake.brv.utils.models
import com.drake.brv.utils.setup
import com.drake.engine.base.EngineBottomSheetDialogFragment
import com.drake.engine.utils.ScreenUtils
import com.drake.tooltip.toast
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.launch
import com.qymusic.app.R
import com.qymusic.app.constants.IntentActions
import com.qymusic.app.constants.IntentExtras
import com.qymusic.app.databinding.FragmentPlayQueueBinding
import com.qymusic.app.model.MusicModel
import com.qymusic.app.service.MusicService
import com.qymusic.app.ui.activity.MainViewModel
import com.qymusic.app.utils.getMusicInfo

/**
 * 播放队列。
 *
 * 支持：
 * - 点击切歌；
 * - 长按拖动调整播放顺序（直接同步到 MediaController）；
 * - 左右滑动或点删除按钮移除歌曲；
 * - 收藏；
 * - 当前播放项高亮并自动滚动到可见位置。
 */
class PlayQueueDialogFragment : EngineBottomSheetDialogFragment<FragmentPlayQueueBinding>(),
  Player.Listener {

  private lateinit var mediaController: MediaController

  private val mainViewModel by activityViewModels<MainViewModel>()

  private lateinit var musicReceiver: MusicBroadReceiver

  private lateinit var touchHelper: ItemTouchHelper

  override fun onCreateView(
    inflater: LayoutInflater,
    container: ViewGroup?,
    savedInstanceState: Bundle?
  ): View? {
    return inflater.inflate(R.layout.fragment_play_queue, container, false)
  }

  override fun onStart() {
    super.onStart()
    setBackgroundTransparent()
    behavior.peekHeight = ScreenUtils.getScreenHeight() / 2
  }

  override fun initData() {
    val sessionToken = SessionToken(
      requireContext(),
      ComponentName(requireContext(), MusicService::class.java)
    )
    val controllerFuture = MediaController.Builder(requireContext(), sessionToken).buildAsync()
    controllerFuture.addListener(
      {
        mediaController = controllerFuture.get()
        mediaController.removeListener(this)
        mediaController.addListener(this)

        viewLifecycleOwner.lifecycleScope.launch {
          reloadQueue(scrollToCurrent = true)
        }
      },
      MoreExecutors.directExecutor()
    )
  }

  override fun initView() {
    binding.btnClose.setOnClickListener {
      // 既可能由导航图（dialog destination）打开，也可能由工具栏菜单直接 show() 打开，
      // 后者不在 NavHost 里，findNavController() 会直接抛异常，这里做兜底 dismiss。
      val nav = runCatching { findNavController() }.getOrNull()
      if (nav?.currentDestination?.id == R.id.playQueueFragment) {
        nav.navigateUp()
      } else {
        dismissAllowingStateLoss()
      }
    }

    binding.btnExpand.setOnClickListener {
      behavior.state = if (behavior.state != BottomSheetBehavior.STATE_EXPANDED)
        BottomSheetBehavior.STATE_EXPANDED
      else BottomSheetBehavior.STATE_HALF_EXPANDED
    }

    binding.playListRv.linear().setup {
      addType<MusicModel>(R.layout.item_queue_music)

      R.id.item_music.onClick {
        if (!::mediaController.isInitialized) {
          return@onClick
        }
        val model = getModel<MusicModel>()
        if (model.isSelected) {
          return@onClick
        }
        mediaController.seekToDefaultPosition(modelPosition)
      }

      R.id.btn_favorite.onClick {
        mainViewModel.addOrRemoveFavorite(getModel())
      }

      R.id.btn_delete.onClick {
        removeAt(modelPosition)
      }
    }.apply {
      singleMode = true
    }

    attachTouchHelper()

    musicReceiver = MusicBroadReceiver()
    ContextCompat.registerReceiver(
      requireContext(),
      musicReceiver,
      IntentFilter(IntentActions.FAVORITE),
      ContextCompat.RECEIVER_NOT_EXPORTED
    )
  }

  /** 从 MediaController 重新读取队列，用于首次进入 */
  private suspend fun reloadQueue(scrollToCurrent: Boolean) {
    if (!::mediaController.isInitialized) {
      return
    }
    val list = mutableListOf<MusicModel>()
    for (i in 0 until mediaController.mediaItemCount) {
      val mediaItem = mediaController.getMediaItemAt(i)
      val musicModel = mediaItem.mediaMetadata.getMusicInfo() ?: MusicModel(name = "未知音乐")
      musicModel.isSelected = false
      musicModel.isFavorite = mainViewModel.isFavoriteMusic(musicModel)
      list.add(musicModel)
    }
    binding.playListRv.models = list
    syncCurrentSelection(scrollToCurrent)
  }

  // ------------------------------------------------------------------ 拖动 / 删除

  private fun attachTouchHelper() {
    val callback = object : ItemTouchHelper.SimpleCallback(
      ItemTouchHelper.UP or ItemTouchHelper.DOWN,
      ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,
    ) {
      override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder,
      ): Boolean {
        val from = viewHolder.bindingAdapterPosition
        val to = target.bindingAdapterPosition
        if (!::mediaController.isInitialized ||
          from == RecyclerView.NO_POSITION ||
          to == RecyclerView.NO_POSITION ||
          from !in queueModels().indices ||
          to !in queueModels().indices
        ) {
          return false
        }
        val models = queueModels()
        mediaController.moveMediaItem(from, to)
        models.add(to, models.removeAt(from))
        recyclerView.adapter?.notifyItemMoved(from, to)
        return true
      }

      override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
        val position = viewHolder.bindingAdapterPosition
        if (position == RecyclerView.NO_POSITION) {
          return
        }
        removeAt(position)
      }

      override fun getSwipeDirs(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
      ): Int {
        // 至少保留一首，避免把正在播放的歌滑掉后队列清空
        return if (::mediaController.isInitialized && mediaController.mediaItemCount <= 1) {
          0
        } else {
          super.getSwipeDirs(recyclerView, viewHolder)
        }
      }

      override fun isLongPressDragEnabled(): Boolean = true

      override fun clearView(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
      ) {
        super.clearView(recyclerView, viewHolder)
        recyclerView.post { syncCurrentSelection(scroll = false) }
      }
    }
    touchHelper = ItemTouchHelper(callback).also {
      it.attachToRecyclerView(binding.playListRv)
    }
  }

  private fun removeAt(position: Int) {
    if (!::mediaController.isInitialized ||
      position !in queueModels().indices
    ) {
      return
    }
    if (mediaController.mediaItemCount <= 1) {
      // 滑动被拦截时的兜底：点删除按钮也不允许清空整个队列
      toast("播放队列至少保留一首")
      binding.playListRv.adapter?.notifyDataSetChanged()
      return
    }

    mediaController.removeMediaItem(position)
    queueModels().removeAt(position)
    binding.playListRv.adapter?.notifyItemRemoved(position)
    toast("已从播放队列移除")
    binding.playListRv.post { syncCurrentSelection(scroll = false) }
  }

  // ------------------------------------------------------------------ 选中态

  @Suppress("UNCHECKED_CAST")
  private fun queueModels(): MutableList<MusicModel> =
    binding.playListRv.models as MutableList<MusicModel>

  /**
   * 同步高亮 + 可选滚动到当前播放项。
   *
   * 拖动 / 删除后 MediaController 的内部索引会变化，这里统一按最新索引刷新。
   */
  private fun syncCurrentSelection(scroll: Boolean) {
    if (!::mediaController.isInitialized) {
      return
    }
    val models = queueModels()
    if (models.isEmpty()) {
      return
    }
    val current = mediaController.currentMediaItemIndex
    models.forEachIndexed { index, model ->
      model.isSelected = index == current
    }
    binding.playListRv.bindingAdapter.notifyDataSetChanged()

    if (current in models.indices) {
      runCatching { binding.playListRv.bindingAdapter.setChecked(current, true) }
      if (scroll) {
        (binding.playListRv.layoutManager as? LinearLayoutManager)
          ?.scrollToPositionWithOffset(current, 0)
      }
    }
  }

  override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
    if (!::mediaController.isInitialized) {
      return
    }
    binding.playListRv.post {
      syncCurrentSelection(scroll = true)
    }
  }

  override fun onDestroyView() {
    super.onDestroyView()
    if (::mediaController.isInitialized)
      mediaController.removeListener(this)

    if (::musicReceiver.isInitialized)
      requireContext().unregisterReceiver(musicReceiver)
  }

  private inner class MusicBroadReceiver : BroadcastReceiver() {

    @Suppress("UNCHECKED_CAST")
    override fun onReceive(context: Context, intent: Intent) {
      when (intent.action) {
        IntentActions.FAVORITE -> {
          val musicId = intent.getLongExtra(IntentExtras.MUSIC_ID, -1L)
          val isFavorite = intent.getBooleanExtra(IntentExtras.FAVORITE, false)
          if (!::mediaController.isInitialized) {
            return
          }
          val models = queueModels()
          models.forEach {
            if (it.id == musicId) {
              it.isFavorite = isFavorite
              return@forEach
            }
          }
        }
      }
    }
  }
}
