package com.qymusic.app.ui.activity

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.ComponentName
import android.content.DialogInterface
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Player
import androidx.navigation.fragment.NavHostFragment
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.drake.engine.adapter.FragmentAdapter
import com.drake.engine.base.EngineActivity
import com.drake.engine.utils.AppUtils
import com.drake.engine.utils.dp
import com.drake.net.Get
import com.drake.net.component.Progress
import com.drake.net.interfaces.ProgressListener
import com.drake.net.utils.scope
import com.drake.net.utils.scopeNet
import com.drake.tooltip.toast
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayoutMediator
import com.hjq.permissions.Permission
import com.hjq.permissions.XXPermissions
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.qymusic.app.R
import com.qymusic.app.config.SPConfig
import com.qymusic.app.config.shouldShowBottomNav
import com.qymusic.app.databinding.ActivityMainBinding
import com.qymusic.app.databinding.DialogTermsBinding
import com.qymusic.app.download.DownloadService
import com.qymusic.app.model.UpdateModel
import com.qymusic.app.ui.fragment.play.LyricsFragment
import com.qymusic.app.ui.fragment.play.MusicPlayFragment
import com.qymusic.app.utils.AppUpdater
import com.qymusic.app.utils.fixNestedScroll
import com.qymusic.app.utils.json
import java.io.File

class MainActivity : EngineActivity<ActivityMainBinding>(R.layout.activity_main), Player.Listener {

  private val mainViewModel by viewModels<MainViewModel>()

  private lateinit var bottomBarBehavior: BottomSheetBehavior<LinearLayout>

  private lateinit var windowInsetsController: WindowInsetsControllerCompat

  /**
   * 是否是亮色状态栏
   */
  private var isLightStatusBar = true

  private val bottomBarHeight = 68.dp

  /** 底部播放栏当前是否可见（没有开始播放歌曲时隐藏） */
  private var bottomBarVisible = false

  var _downloadService: DownloadService? = null

  private val onBackPressedCallback = object : OnBackPressedCallback(false) {
    override fun handleOnBackPressed() {
      if (bottomBarBehavior.state != BottomSheetBehavior.STATE_COLLAPSED) {
        // 按下返回按钮时，如果底栏是展开的状态，就将底栏设置为折叠的状态
        mainViewModel.changeBottomBarExpand(false)
      }
    }
  }

  private var serviceBound = false

  private val serviceConnection = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
      val binder = service as DownloadService.DownloadBinder
      _downloadService = binder.getService()
      serviceBound = true
    }

    override fun onServiceDisconnected(name: ComponentName?) {
      serviceBound = false
      _downloadService = null
    }
  }

  override fun init() {
    super.init()
    onBackPressedDispatcher.addCallback(this, onBackPressedCallback)

    bindService(Intent(this, DownloadService::class.java), serviceConnection, BIND_AUTO_CREATE)

    // 首次启动必须先同意用户协议与隐私政策，否则直接退出
    if (!SPConfig.isTermsAgreed()) {
      showTermsDialog()
      return
    }

    requestRuntimePermissions()
    scheduleUpdateCheck()
  }

  /**
   * 用户协议与隐私政策弹窗（首次启动展示，不同意直接退出进程）
   */
  private fun showTermsDialog() {
    val view = DialogTermsBinding.inflate(layoutInflater)
    val dialog = Dialog(this)
    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
    dialog.setContentView(view.root)
    dialog.setCancelable(false)
    dialog.setCanceledOnTouchOutside(false)
    dialog.window?.apply {
      setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
      // 加深遮罩，避免从弹窗圆角外透视到下面的内容
      setDimAmount(0.75f)
      val width = (resources.displayMetrics.widthPixels * 0.92f).toInt()
      setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    // 「同意并继续」倒计时 5 秒才可点击，避免未阅读就点同意；
    // 「不同意并退出」保持立即可点。
    var remain = 5
    view.btnAgree.isEnabled = false
    view.btnAgree.text = "同意并继续（${remain}s）"
    lifecycleScope.launch {
      while (remain > 0) {
        delay(1000)
        remain--
        if (remain > 0) {
          view.btnAgree.text = "同意并继续（${remain}s）"
        }
      }
      view.btnAgree.isEnabled = true
      view.btnAgree.text = "同意并继续"
    }

    view.btnAgree.setOnClickListener {
      SPConfig.setTermsAgreed(true)
      dialog.dismiss()
      requestRuntimePermissions()
      scheduleUpdateCheck()
    }
    view.btnDisagree.setOnClickListener {
      dialog.dismiss()
      exitApp()
    }
    dialog.show()
  }

  /** 不同意协议：结束所有界面并杀死进程 */
  private fun exitApp() {
    try {
      finishAffinity()
    } catch (ignored: Exception) {
    }
    android.os.Process.killProcess(android.os.Process.myPid())
    kotlin.system.exitProcess(0)
  }

  private fun requestRuntimePermissions() {
    if (!XXPermissions.isGrantedPermissions(this, Permission.MANAGE_EXTERNAL_STORAGE)) {
      MaterialAlertDialogBuilder(this)
        .setTitle("提示")
        .setMessage("需要存储权限，App才能将下载文件保存到外部储存，如果不授权将保存在Android文件夹内")
        .setPositiveButton("授权") { dialog, which ->
          XXPermissions.with(this).permission(
            Permission.MANAGE_EXTERNAL_STORAGE,
          )
            .request(null)
        }
        .setNegativeButton("取消", null)
        .show()
    }

    if (!XXPermissions.isGrantedPermissions(this, Permission.POST_NOTIFICATIONS)) {
      MaterialAlertDialogBuilder(this)
        .setTitle("展示通知")
        .setMessage("需要通知权限，App才能发送下载时的通知")
        .setPositiveButton("授权") { dialog, which ->
          XXPermissions.with(this).permission(
            Permission.POST_NOTIFICATIONS
          ).request(null)
        }
        .setNegativeButton("取消", null)
        .show()
    }
  }

  private fun scheduleUpdateCheck() {
    // 启动后尽快检查更新：服务端下发最低版本时会弹出不可取消的强制更新弹窗
    lifecycleScope.launch {
      delay(600)
      AppUpdater.checkOnStart(this@MainActivity)
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    WindowCompat.setDecorFitsSystemWindows(window, false)

    val navHostFragment = supportFragmentManager
      .findFragmentById(R.id.nav_host_fragment) as NavHostFragment

    val navController = navHostFragment.navController

    windowInsetsController = WindowCompat.getInsetsController(window, binding.root)

    navController.addOnDestinationChangedListener { controller, destination, arguments ->
      mainViewModel.changeBottomBarVisible(destination.shouldShowBottomNav())
    }
  }

  override fun onClick(v: View) {
    super.onClick(v)
    when (v.id) {
      R.id.play_bar -> {
        mainViewModel.changeBottomBarExpand(true)
      }

      R.id.btn_close -> {
        mainViewModel.changeBottomBarExpand(false)
      }

      R.id.btn_play -> {
        mainViewModel.updateIsPlaying()
      }

      R.id.btn_favorite -> {
        mainViewModel.addOrRemoveFavorite()
      }
    }
  }

  @SuppressLint("ClickableViewAccessibility")
  override fun initView() {

    val titles = arrayOf("歌曲", "歌词")
    val fragments = listOf(MusicPlayFragment(), LyricsFragment())
    val vpMusic = binding.vpMusic

    vpMusic.apply {
      offscreenPageLimit = 2
      adapter = FragmentAdapter(fragments)
      fixNestedScroll()
    }

    TabLayoutMediator(binding.tab, vpMusic) { tab, position ->
      tab.text = titles[position]
    }.attach()

    val bottomBar = binding.bottomBar
    bottomBarBehavior = BottomSheetBehavior.from(bottomBar)
    ViewCompat.setOnApplyWindowInsetsListener(bottomBar) { v, insets ->
      val navigationBarHeight =
        insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
      val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
      bottomBarBehavior.peekHeight = bottomBarHeight + navigationBarHeight
      binding.navHostFragment.updatePadding(
        bottom = if (bottomBarVisible) bottomBarBehavior.peekHeight else 0
      )
      val lp = binding.mainPlayer.layoutParams as ViewGroup.MarginLayoutParams
      lp.topMargin = -(bottomBarHeight - statusBarHeight - 10.dp)
      insets
    }

    bottomBarBehavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
      override fun onSlide(view: View, offset: Float) {
        binding.mainPlayer.translationZ = if (offset == 0f) -10f else 10f
        binding.mainPlayer.alpha = offset
        binding.playBar.alpha = 1 - offset
      }

      override fun onStateChanged(v: View, state: Int) {
        // 如果是展开的状态
        if (state == BottomSheetBehavior.STATE_EXPANDED) {
          // 同步状态给 ViewModel
          mainViewModel.changeBottomBarExpand(true)
          // 开启返回监听
          onBackPressedCallback.isEnabled = true
          // 如果当前不是亮色状态栏
          if (!windowInsetsController.isAppearanceLightStatusBars) {
            // 设置亮色状态栏
            windowInsetsController.isAppearanceLightStatusBars = true
            isLightStatusBar = false
          } else {
            isLightStatusBar = true
          }
        } else if (state == BottomSheetBehavior.STATE_COLLAPSED) {
          mainViewModel.changeBottomBarExpand(false)
          onBackPressedCallback.isEnabled = false
          if (isLightStatusBar != windowInsetsController.isAppearanceLightStatusBars) {
            windowInsetsController.isAppearanceLightStatusBars = isLightStatusBar
          }
        }
      }
    })
  }

  override fun initData() {
    binding.m = mainViewModel
    binding.lifecycleOwner = this

    lifecycleScope.launch {
      // 监听底栏的展开与折叠、是否显示
      launch {
        mainViewModel.bottomBarUIState.collect { uIState ->
          applyBottomBarState(uIState)
        }
      }
      // 迷你播放栏的歌曲信息：不依赖 DataBinding 的 StateFlow 支持，直接同步
      launch {
        mainViewModel.musicModel.collect { model ->
          binding.musicName.text = model.name
          binding.musicArtist.text = model.artist
          binding.btnFavorite.isSelected = model.isFavorite
          val pic = model.showPic
          if (pic is Int) {
            Glide.with(this@MainActivity).load(pic)
              .transform(RoundedCorners(6.dp))
              .into(binding.musicCover)
          } else {
            Glide.with(this@MainActivity).load(pic as String)
              .transform(RoundedCorners(6.dp))
              .into(binding.musicCover)
          }
        }
      }
      // 播放/暂停按钮图标
      launch {
        mainViewModel.isPlaying.collect { playing ->
          binding.btnPlay.setImageResource(
            if (playing) R.drawable.baseline_pause_24 else R.drawable.baseline_play_arrow_24
          )
        }
      }
    }
  }

  /**
   * 应用底部播放栏状态：没有播放歌曲时整条隐藏，底部内容也不再留出空位。
   */
  private fun applyBottomBarState(state: MainViewModel.BottomBarState) {
    bottomBarVisible = state.isVisible
    binding.bottomBar.visibility = if (state.isVisible) View.VISIBLE else View.GONE
    binding.navHostFragment.updatePadding(
      bottom = if (state.isVisible) bottomBarBehavior.peekHeight else 0
    )

    if (state.isVisible) {
      bottomBarBehavior.state =
        if (state.isExpanded) BottomSheetBehavior.STATE_EXPANDED else BottomSheetBehavior.STATE_COLLAPSED
    } else {
      if (bottomBarBehavior.state != BottomSheetBehavior.STATE_COLLAPSED) {
        bottomBarBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
      }
    }
  }
}
