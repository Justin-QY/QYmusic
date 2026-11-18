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
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Player
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
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

  /** 迷你播放栏实际高度（布局完成后测量，避免被底部导航裁切） */
  private var playerBarHeight = 72.dp

  /** 底部导航栏高度（首页 / 云端） */
  private val bottomNavHeight = 56.dp

  private var navigationBarInset = 0

  /** 底部播放栏当前是否可见（没有开始播放歌曲时隐藏） */
  private var bottomBarVisible = false

  /** 底部导航栏是否显示：只剩一个模块时隐藏整条导航 */
  private var bottomNavVisible = true

  /** 当前页面是否是一级模块（首页/云端），子页面不显示底栏 */
  private var currentIsModule = true

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
    // 协议正文是 HTML，交给 HtmlCompat 渲染：保留标题加粗与段落换行
    view.tvTerms.text = androidx.core.text.HtmlCompat.fromHtml(
      getString(R.string.terms_content),
      androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY
    )
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
      // 只有一级模块显示底部导航，设置/详情等子页面隐藏
      currentIsModule = com.qymusic.app.config.NavModules.find(destination.id) != null
      binding.bottomNav.visibility =
        if (bottomNavVisible && currentIsModule) View.VISIBLE else View.GONE
      applyBottomMetrics()
      // 返回到已被隐藏的模块时（例如设置页返回云端），自动切到仍显示的模块
      binding.bottomNav.post { ensureVisibleDestination(controller) }
    }

    // 底部导航：按注册表动态生成（可在设置里隐藏模块）
    rebuildBottomNav(navController)

    // 通用设置里的「默认启动页」：冷启动完成后跳到目标模块
    applyDefaultStartPage(navController, savedInstanceState)
  }

  /**
   * 冷启动后按「默认启动页」跳转（设置 → 通用设置）。
   *
   * 只处理从桌面正常启动：savedInstanceState 为空、ACTION_MAIN、无 data、非最近任务恢复，
   * 因此深链、通知、分享进入仍走原有路由，旋转屏幕 / 切换主题引起的重建也不会重复跳转。
   * 跳转时把原起始页一起清出返回栈，避免按返回又回到首页。
   */
  private fun applyDefaultStartPage(
    navController: androidx.navigation.NavController,
    savedInstanceState: Bundle?
  ) {
    if (savedInstanceState != null) {
      return
    }
    val launchIntent = intent ?: return
    if (launchIntent.action != Intent.ACTION_MAIN || launchIntent.data != null) {
      return
    }
    if (launchIntent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) {
      return
    }

    // NavModules.startPage() 会在所选模块被隐藏时回落到第一个可见模块
    val targetId = com.qymusic.app.config.NavModules.startPage().id
    if (navController.currentDestination?.id == targetId) {
      return
    }
    runCatching {
      val options = androidx.navigation.NavOptions.Builder()
        .setPopUpTo(navController.graph.startDestinationId, true)
        .setLaunchSingleTop(true)
        .build()
      navController.navigate(targetId, null, options)
    }
  }

  /**
   * 根据 NavModules 注册表生成底部导航；设置页隐藏模块后重建 Activity 生效。
   *
   * 只剩一个模块时整条导航栏隐藏（单入口没有切换意义），内容区补回被导航占用的高度。
   */
  private fun rebuildBottomNav(navController: androidx.navigation.NavController) {
    // 老版本可能把模块全部隐藏，兜底为全部显示，避免出现空导航
    val visible = com.qymusic.app.config.NavModules.visible()
      .ifEmpty { com.qymusic.app.config.NavModules.ALL }
    bottomNavVisible = visible.size > 1
    binding.bottomNav.visibility = if (bottomNavVisible) View.VISIBLE else View.GONE

    val menu = binding.bottomNav.menu
    menu.clear()
    visible.forEach { module ->
      menu.add(0, module.id, 0, module.title).setIcon(module.icon)
    }
    binding.bottomNav.setupWithNavController(navController)

    // 当前停留在被隐藏的模块（例如隐藏首页后仍停在首页）时，切到第一个可见模块
    ensureVisibleDestination(navController)
    // 导航状态恢复可能晚于 onCreate，再补一次检查
    binding.bottomNav.post { ensureVisibleDestination(navController) }

    applyBottomMetrics()
  }

  /**
   * 如果当前页面是被隐藏的底部导航模块，跳到第一个可见模块。
   * 设置页、歌单详情等普通页面不受影响。
   */
  private fun ensureVisibleDestination(navController: androidx.navigation.NavController) {
    val visible = com.qymusic.app.config.NavModules.visible()
      .ifEmpty { com.qymusic.app.config.NavModules.ALL }
    val current = navController.currentDestination?.id
    val currentIsHiddenModule = com.qymusic.app.config.NavModules.find(current ?: -1) != null &&
      visible.none { it.id == current }
    if (current != null && !currentIsHiddenModule) {
      return
    }
    runCatching {
      val options = androidx.navigation.NavOptions.Builder()
        .setPopUpTo(navController.graph.startDestinationId, true)
        .setLaunchSingleTop(true)
        .build()
      navController.navigate(visible.first().id, null, options)
    }
  }

  /**
   * 按「导航栏是否显示 + 系统导航条高度 + 迷你播放栏高度」重新计算底部留白。
   *
   * 导航栏隐藏时不再给 56dp 预留，否则内容底部会多出一条空白。
   */
  private fun applyBottomMetrics() {
    if (!this::bottomBarBehavior.isInitialized) {
      return
    }
    val navReserve = if (bottomNavVisible && currentIsModule) bottomNavHeight else 0
    bottomBarBehavior.peekHeight = playerBarHeight + navReserve + navigationBarInset
    binding.bottomBar.updatePadding(bottom = navReserve + navigationBarInset)
    binding.navHostFragment.updateLayoutParams<ViewGroup.MarginLayoutParams> {
      bottomMargin = navReserve + navigationBarInset
    }
    binding.navHostFragment.updatePadding(
      bottom = if (bottomBarVisible) playerBarHeight else 0
    )
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
    // 播放栏要盖在底部导航之上，否则导航会压住播放控件
    bottomBar.elevation = 12.dp.toFloat()
    binding.bottomNav.elevation = 0f
    binding.playBar.post {
      if (binding.playBar.height > 0) {
        playerBarHeight = binding.playBar.height
        applyBottomMetrics()
      }
    }
    // 用根布局接收 insets：播放栏隐藏时也能正确给底部导航和内容留出空间
    ViewCompat.setOnApplyWindowInsetsListener(binding.coordinator) { v, insets ->
      val navigationBarHeight =
        insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
      navigationBarInset = navigationBarHeight
      val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
      binding.bottomNav.updatePadding(bottom = navigationBarHeight)
      applyBottomMetrics()
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
      bottom = if (state.isVisible) playerBarHeight else 0
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
