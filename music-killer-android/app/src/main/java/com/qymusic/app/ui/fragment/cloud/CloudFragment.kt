package com.qymusic.app.ui.fragment.cloud

import android.content.Intent
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.os.bundleOf
import androidx.core.view.MenuProvider
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CircleCrop
import com.drake.brv.utils.grid
import com.drake.brv.utils.models
import com.drake.brv.utils.setup
import com.drake.engine.utils.dp
import com.drake.net.Get
import com.drake.tooltip.toast
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import com.qymusic.app.R
import com.qymusic.app.base.BaseMusicAppbarFragment
import com.qymusic.app.config.Api
import com.qymusic.app.config.SPConfig
import com.qymusic.app.databinding.FragmentCloudBinding
import com.qymusic.app.model.ArtistModel
import com.qymusic.app.model.MusicModel
import com.qymusic.app.model.PlayListModel
import com.qymusic.app.model.setupMusicRv
import com.qymusic.app.ui.activity.PlatformLoginActivity
import com.qymusic.app.utils.SleepTimerDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 云端模块：支持网易云 / QQ 音乐 / 酷狗概念版 三个平台，可同时绑定，
 * 切换平台显示该平台的我喜欢的音乐与收藏歌单。
 */
class CloudFragment : BaseMusicAppbarFragment<FragmentCloudBinding>(R.layout.fragment_cloud),
    MenuProvider {

    private companion object {
        const val DATA_TTL_MS = 60_000L
        const val QR_TIMEOUT_MS = 180_000L
        const val PLAY_QUEUE_TAG = "play_queue_dialog"

        /** 酷狗签到卡默认副标题，接口未返回或查询失败时兜底 */
        const val KUGOU_VIP_HINT = "每天可领取畅听 VIP 与概念版升级奖励"
    }

    private var platform = PlatformLoginActivity.PLATFORM_NETEASE
    private var firstResume = true

    /** 当前平台数据缓存，避免每次 onResume 都重新请求 */
    private var loadedPlatform: String? = null
    private var loadedAt = 0L

    /** 当前在跑的数据请求；切平台时取消上一个，避免旧结果覆盖新平台 */
    private var loadJob: kotlinx.coroutines.Job? = null
    private var loadPlatform: String? = null

    /** 酷狗签到卡副标题缓存（会员状态 / 今日签到状态） */
    private var kugouVipStatusText: String? = null
    private var kugouVipStatusCookie: String? = null
    private var kugouVipStatusAt = 0L
    private var kugouVipStatusJob: kotlinx.coroutines.Job? = null

    private var loginDialog: android.app.Dialog? = null
    private var qrJob: kotlinx.coroutines.Job? = null

    private val loginLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                invalidateData()
                refreshUi()
            }
        }

    override fun isAddScrollView(): Boolean = true

    override fun openMediaController(): Boolean = true

    override fun getMusicModels(): List<Any?>? = binding.rvLiked.models

    override fun initView() {
        requireActivity().addMenuProvider(this, viewLifecycleOwner, Lifecycle.State.RESUMED)
        binding.rvLiked.setupMusicRv(this)

        binding.rvPlaylists.grid(3).setup {
            addType<PlayListModel>(R.layout.item_playlist)
            R.id.item_playlist.onClick {
                navController.navigate(
                    R.id.playlistInfoFragment,
                    bundleOf("playlist" to getModel<PlayListModel>())
                )
            }
        }

        binding.rvArtists.grid(3).setup {
            addType<ArtistModel>(R.layout.item_grid_artist)
            R.id.item_artist.onClick {
                navController.navigate(
                    R.id.artistInfoFragment,
                    bundleOf("artist" to getModel<ArtistModel>())
                )
            }
        }

        binding.chipNetease.setOnClickListener { selectPlatform(PlatformLoginActivity.PLATFORM_NETEASE) }
        binding.chipQq.setOnClickListener { selectPlatform(PlatformLoginActivity.PLATFORM_QQ) }
        binding.chipKuwo.setOnClickListener { selectPlatform(PlatformLoginActivity.PLATFORM_KUGOU) }

        binding.btnLogin.setOnClickListener { showLoginDialog() }
        binding.btnLogout.setOnClickListener { logout() }
        binding.btnGoSetting.setOnClickListener {
            navController.navigate(R.id.action_setting)
        }
        binding.btnKugouCheckin.setOnClickListener { checkKugouVip() }
        binding.tvMoreLiked.setOnClickListener { openAll("liked") }
        binding.tvMorePlaylists.setOnClickListener { openAll("playlists") }
        binding.tvMoreArtists.setOnClickListener { openAll("artists") }
    }

    override fun initData() {
    }

    override fun onResume() {
        super.onResume()
        if (firstResume) {
            firstResume = false
            val enabled = SPConfig.enabledCloudPlatforms()
            if (platform !in enabled) {
                platform = enabled.first()
            }
            if (!isLogged(platform)) {
                // 自动切到已登录且已启用的平台（按保存的排序取第一个）
                enabled.firstOrNull { isLogged(it) }
                    ?.let { platform = it }
            }
        }
        refreshUi()
    }

    private fun selectPlatform(target: String) {
        if (target == platform) {
            return
        }
        // 设置里被关闭的平台即使芯片还残留在布局里也不响应
        if (!SPConfig.isCloudPlatformEnabled(target)) {
            return
        }
        platform = target
        refreshUi()
    }

    private fun refreshUi() {
        val platforms = SPConfig.enabledCloudPlatforms()
        val hasPlatform = platforms.isNotEmpty()

        // 当前平台被关闭时自动切到第一个启用平台
        if (hasPlatform && platform !in platforms) {
            platform = platforms.first()
            loadJob?.cancel()
            invalidateData()
        }

        binding.togglePlatform.isVisible = hasPlatform
        binding.cardNoPlatform.isVisible = !hasPlatform
        orderChips(platforms)

        if (!hasPlatform) {
            binding.cardLogin.isVisible = false
            binding.cardProfile.isVisible = false
            binding.cardKugouVip.isVisible = false
            binding.groupContent.isVisible = false
            binding.rvLiked.models = emptyList<MusicModel>()
            binding.rvPlaylists.models = emptyList<PlayListModel>()
            binding.rvArtists.models = emptyList<ArtistModel>()
            return
        }

        val logged = isLogged(platform)
        binding.cardLogin.visibility = if (logged) View.GONE else View.VISIBLE
        binding.cardProfile.visibility = if (logged) View.VISIBLE else View.GONE
        binding.groupContent.visibility = if (logged) View.VISIBLE else View.GONE

        // 酷狗签到卡：仅当前平台为酷狗且已登录时显示
        val showKugouVip = logged && platform == PlatformLoginActivity.PLATFORM_KUGOU
        binding.cardKugouVip.isVisible = showKugouVip
        if (showKugouVip) {
            binding.tvKugouVipState.text = kugouVipStateText()
            refreshKugouVipStatus()
        } else {
            binding.tvKugouVipState.text = KUGOU_VIP_HINT
        }

        styleChip(binding.chipNetease, platform == PlatformLoginActivity.PLATFORM_NETEASE)
        styleChip(binding.chipQq, platform == PlatformLoginActivity.PLATFORM_QQ)
        styleChip(binding.chipKuwo, platform == PlatformLoginActivity.PLATFORM_KUGOU)
        binding.btnLogin.text = "登录" + platformName()

        // 登录卡片文案跟随当前平台，避免切到 QQ/酷狗后仍显示「登录网易云账号」
        binding.tvLoginCardTitle.text = "登录" + platformName() + "账号"
        binding.tvLoginCardDesc.text = if (platform == PlatformLoginActivity.PLATFORM_NETEASE) {
            "登录后可查看你收藏的歌手、歌单，以及「我喜欢的音乐」。登录凭据只保存在本机。"
        } else {
            "登录后可查看收藏的歌单与「我喜欢的音乐」。登录凭据只保存在本机。"
        }

        val isNetease = platform == PlatformLoginActivity.PLATFORM_NETEASE
        // 收藏歌手只有网易云有，整行（标题 + 查看全部）一起隐藏
        binding.rowArtists.visibility = if (isNetease) View.VISIBLE else View.GONE

        if (!logged) {
            binding.rvLiked.models = emptyList<MusicModel>()
            binding.rvPlaylists.models = emptyList<PlayListModel>()
            binding.rvArtists.models = emptyList<ArtistModel>()
            return
        }

        binding.tvNickname.text = nickname().ifBlank { platformName() + "用户" }
        binding.tvPlatformState.text = platformName() + "账号已登录"
        Glide.with(this)
            .load(avatar())
            .transform(CircleCrop())
            .placeholder(R.mipmap.ic_launcher)
            .into(binding.imgAvatar)
        loadData()
    }

    /**
     * 按「模块与插件」页保存的顺序重排芯片：只把已启用的平台放入容器，
     * 顺序没变化时不动视图，避免每次 onResume 都重建。
     */
    private fun orderChips(platforms: Set<String>) {
        val container = binding.togglePlatform
        val chips = linkedMapOf(
            SPConfig.CLOUD_NETEASE to binding.chipNetease,
            SPConfig.CLOUD_QQ to binding.chipQq,
            SPConfig.CLOUD_KUGOU to binding.chipKuwo,
        )
        val ordered = platforms.mapNotNull { chips[it] }
        val current = (0 until container.childCount).map { container.getChildAt(it) }
        if (current == ordered) {
            return
        }
        container.removeAllViews()
        ordered.forEach { chip ->
            (chip.parent as? android.view.ViewGroup)?.removeView(chip)
            chip.visibility = View.VISIBLE
            chip.layoutParams = android.widget.LinearLayout.LayoutParams(
                0,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                1f
            )
            container.addView(chip)
        }
    }

    private fun styleChip(view: android.widget.TextView, selected: Boolean) {
        view.setBackgroundResource(
            if (selected) R.drawable.bg_segment_selected else android.R.color.transparent
        )
        val attr = if (selected) {
            com.google.android.material.R.attr.colorOnPrimary
        } else {
            com.google.android.material.R.attr.colorOnSurfaceVariant
        }
        view.setTextColor(com.google.android.material.color.MaterialColors.getColor(view, attr))
    }

    private fun loadData(force: Boolean = false) {
        val target = platform
        val now = System.currentTimeMillis()
        if (!force && target == loadedPlatform && now - loadedAt < DATA_TTL_MS) {
            return
        }
        // 同一平台已有请求在跑就不重复发；换平台时取消上一个请求
        if (target == loadPlatform && loadJob?.isActive == true) {
            return
        }
        loadJob?.cancel()
        loadPlatform = target

        val cookie = cookieOf(target)
        val uid = uidOf(target)

        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val likedUrl = when (target) {
                    PlatformLoginActivity.PLATFORM_QQ -> Api.QQ_LIKED
                    PlatformLoginActivity.PLATFORM_KUGOU -> Api.KUGOU_LIKED
                    else -> Api.NETEASE_LIKED
                }
                val playlistsUrl = when (target) {
                    PlatformLoginActivity.PLATFORM_QQ -> Api.QQ_PLAYLISTS
                    PlatformLoginActivity.PLATFORM_KUGOU -> Api.KUGOU_PLAYLISTS
                    else -> Api.NETEASE_PLAYLISTS
                }
                val isNetease = target == PlatformLoginActivity.PLATFORM_NETEASE

                val liked = async {
                    Get<List<MusicModel>>(likedUrl) {
                        addQuery("cookie", cookie)
                        if (isNetease) {
                            addQuery("uid", uid)
                            addQuery("pageNo", 1)
                            addQuery("pageSize", 30)
                        } else {
                            addQuery("limit", 30)
                        }
                    }.await()
                }
                val playlists = async {
                    Get<List<PlayListModel>>(playlistsUrl) {
                        addQuery("cookie", cookie)
                        if (isNetease) {
                            addQuery("uid", uid)
                        }
                    }.await()
                }
                val artists = if (isNetease) {
                    async {
                        Get<List<ArtistModel>>(Api.NETEASE_ARTISTS) {
                            addQuery("cookie", cookie)
                        }.await()
                    }
                } else {
                    null
                }

                // 请求期间用户切了平台则丢弃旧结果（正常情况下 Job 已被取消）
                if (platform != target) {
                    return@launch
                }
                binding.rvLiked.models = liked.await().take(5)
                binding.rvPlaylists.models = playlists.await().take(6)
                binding.rvArtists.models = artists?.await().orEmpty().take(6)
                loadedPlatform = target
                loadedAt = System.currentTimeMillis()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                toast(e.message ?: "加载云端数据失败")
            }
        }
    }

    private fun invalidateData() {
        loadedPlatform = null
        loadedAt = 0L
    }

    private fun openAll(type: String) {
        navController.navigate(
            R.id.cloudAllFragment,
            bundleOf("type" to type, "platform" to platform)
        )
    }

    private fun logout() {
        when (platform) {
            PlatformLoginActivity.PLATFORM_QQ -> SPConfig.clearQqLogin()
            PlatformLoginActivity.PLATFORM_KUGOU -> SPConfig.clearKugouLogin()
            else -> SPConfig.clearNeteaseLogin()
        }
        // 清掉 WebView 登录态，否则下次点登录会直接回到原账号
        val cookieManager = android.webkit.CookieManager.getInstance()
        cookieManager.removeAllCookies(null)
        cookieManager.flush()
        android.webkit.WebStorage.getInstance().deleteAllData()

        loadJob?.cancel()
        invalidateData()
        binding.rvLiked.models = emptyList<MusicModel>()
        binding.rvPlaylists.models = emptyList<PlayListModel>()
        binding.rvArtists.models = emptyList<ArtistModel>()
        refreshUi()
        toast("已退出" + platformName() + "账号")
    }

    private fun platformName(p: String = platform): String = when (p) {
        PlatformLoginActivity.PLATFORM_QQ -> "QQ音乐"
        PlatformLoginActivity.PLATFORM_KUGOU -> "酷狗"
        else -> "网易云"
    }

    private fun isLogged(p: String): Boolean = when (p) {
        PlatformLoginActivity.PLATFORM_QQ -> SPConfig.isQqLoggedIn()
        PlatformLoginActivity.PLATFORM_KUGOU -> SPConfig.isKugouLoggedIn()
        else -> SPConfig.isNeteaseLoggedIn()
    }

    private fun cookieOf(p: String): String = when (p) {
        PlatformLoginActivity.PLATFORM_QQ -> SPConfig.qqCookie()
        PlatformLoginActivity.PLATFORM_KUGOU -> SPConfig.kugouCookie()
        else -> SPConfig.neteaseCookie()
    }

    private fun uidOf(p: String): Long = when (p) {
        PlatformLoginActivity.PLATFORM_QQ -> SPConfig.qqUin().toLongOrNull() ?: 0L
        PlatformLoginActivity.PLATFORM_KUGOU -> SPConfig.kugouUid()
        else -> SPConfig.neteaseUid()
    }

    private fun nickname(): String = when (platform) {
        PlatformLoginActivity.PLATFORM_QQ -> SPConfig.qqNickname()
        PlatformLoginActivity.PLATFORM_KUGOU -> SPConfig.kugouNickname()
        else -> SPConfig.neteaseNickname()
    }

    private fun avatar(): String = when (platform) {
        PlatformLoginActivity.PLATFORM_QQ -> SPConfig.qqAvatar()
        PlatformLoginActivity.PLATFORM_KUGOU -> SPConfig.kugouAvatar()
        else -> SPConfig.neteaseAvatar()
    }

    /**
     * 登录弹窗：扫码 / 网页登录 / Cookie 三种方式。
     */
    private fun showLoginDialog() {
        // 只允许一个登录弹窗，避免连点叠出多个二维码轮询
        if (loginDialog?.isShowing == true) {
            return
        }
        // 弹窗打开期间锁定平台，防止切平台后把 Cookie 存到别的账号上
        val target = platform

        val view = com.qymusic.app.databinding.DialogLoginBinding.inflate(layoutInflater)
        val dialog = android.app.Dialog(requireContext())
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.setContentView(view.root)
        dialog.window?.apply {
            setBackgroundDrawable(
                android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
            )
            // 底部弹出：圆角由布局背景负责，宽度铺满屏幕
            setGravity(android.view.Gravity.BOTTOM)
            setLayout(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setDimAmount(0.45f)
        }

        view.tvLoginTitle.text = "登录" + platformName(target)

        view.toggleLoginMethod.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) {
                return@addOnButtonCheckedListener
            }
            view.groupQr.visibility = if (checkedId == R.id.btn_method_qr) View.VISIBLE else View.GONE
            view.groupWeb.visibility = if (checkedId == R.id.btn_method_web) View.VISIBLE else View.GONE
            view.groupCookie.visibility = if (checkedId == R.id.btn_method_cookie) View.VISIBLE else View.GONE
            if (checkedId == R.id.btn_method_qr) {
                loadQr(view, dialog, target)
            } else {
                qrJob?.cancel()
            }
        }
        view.toggleLoginMethod.check(R.id.btn_method_qr)

        view.btnQrRefresh.setOnClickListener { loadQr(view, dialog, target) }
        view.btnOpenWeb.setOnClickListener {
            dialog.dismiss()
            loginLauncher.launch(
                Intent(requireContext(), PlatformLoginActivity::class.java)
                    .putExtra(PlatformLoginActivity.EXTRA_PLATFORM, target)
            )
        }
        view.btnSaveCookie.setOnClickListener {
            val cookie = view.etCookie.text.toString().trim()
            if (cookie.isBlank()) {
                toast("Cookie 不能为空")
                return@setOnClickListener
            }
            view.btnSaveCookie.isEnabled = false
            view.btnSaveCookie.text = "正在验证…"
            saveManualCookie(target, cookie) { ok ->
                view.btnSaveCookie.isEnabled = true
                view.btnSaveCookie.text = "保存 Cookie"
                if (ok) {
                    dialog.dismiss()
                }
            }
        }
        view.btnLoginCancel.setOnClickListener { dialog.dismiss() }

        loginDialog = dialog
        dialog.setOnDismissListener {
            qrJob?.cancel()
            qrJob = null
            if (loginDialog === dialog) {
                loginDialog = null
            }
        }
        dialog.show()
    }

    private fun loadQr(
        view: com.qymusic.app.databinding.DialogLoginBinding,
        dialog: android.app.Dialog,
        target: String,
    ) {
        qrJob?.cancel()
        view.imgQr.setImageDrawable(null)
        view.tvQrStatus.text = "正在获取二维码…"
        val isQq = target == PlatformLoginActivity.PLATFORM_QQ
        val isKugou = target == PlatformLoginActivity.PLATFORM_KUGOU
        val deadline = System.currentTimeMillis() + QR_TIMEOUT_MS
        qrJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val data = Get<com.qymusic.app.model.QrCreate>(
                    when {
                        isQq -> Api.QR_QQ
                        isKugou -> Api.QR_KUGOU
                        else -> Api.QR_NETEASE
                    }
                ).await()
                val bitmap = decodeQr(data.image)
                if (bitmap == null) {
                    view.tvQrStatus.text = "二维码解析失败，请刷新"
                    return@launch
                }
                view.imgQr.setImageBitmap(bitmap)
                view.tvQrStatus.text = "请使用" + platformName(target) + "扫码"

                val id = if (isQq) data.qrsig else data.key
                if (id.isBlank()) {
                    view.tvQrStatus.text = "二维码信息缺失，请刷新"
                    return@launch
                }
                var failures = 0
                while (true) {
                    if (System.currentTimeMillis() > deadline) {
                        view.tvQrStatus.text = "二维码已超时，请刷新"
                        return@launch
                    }
                    kotlinx.coroutines.delay(2000)
                    val poll = try {
                        Get<com.qymusic.app.model.QrPoll>(
                            when {
                                isQq -> Api.QR_QQ_POLL
                                isKugou -> Api.QR_KUGOU_POLL
                                else -> Api.QR_NETEASE_POLL
                            }
                        ) {
                            addQuery(if (isQq) "qrsig" else "key", id)
                        }.await()
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // 轮询偶发失败（切网/超时）不直接中断，连续 3 次才提示
                        failures++
                        if (failures >= 3) {
                            view.tvQrStatus.text = "网络异常，请刷新二维码"
                            return@launch
                        }
                        continue
                    }
                    failures = 0
                    when (poll.status) {
                        "success" -> {
                            view.tvQrStatus.text = "登录成功"
                            saveManualCookie(target, poll.cookie) { ok ->
                                if (ok) {
                                    dialog.dismiss()
                                } else {
                                    view.tvQrStatus.text = "登录信息无效，请刷新二维码"
                                }
                            }
                            return@launch
                        }
                        "scanned" -> view.tvQrStatus.text = "已扫码，请在手机上确认"
                        "expired" -> {
                            view.tvQrStatus.text = "二维码已过期，请刷新"
                            return@launch
                        }
                        "failed" -> {
                            view.tvQrStatus.text = "登录已取消"
                            return@launch
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                view.tvQrStatus.text = "二维码获取失败：" + (e.message ?: "网络异常")
            }
        }
    }

    private fun decodeQr(base64: String): android.graphics.Bitmap? = try {
        val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    } catch (e: Exception) {
        null
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        menuInflater.inflate(R.menu.menu_cloud, menu)
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        when (menuItem.itemId) {
            R.id.search -> {
                navController.navigate(R.id.action_search)
                return true
            }

            R.id.sleep_timer -> {
                SleepTimerDialog.show(requireContext())
                return true
            }

            R.id.play_queue -> {
                showPlayQueue()
                return true
            }

            R.id.settings_cloud -> {
                navController.navigate(R.id.action_setting)
                return true
            }
        }
        return false
    }

    /** 播放队列：直接展示已有的 PlayQueueDialogFragment，不重复实现队列页 */
    private fun showPlayQueue() {
        val fragmentManager = requireActivity().supportFragmentManager
        if (fragmentManager.findFragmentByTag(PLAY_QUEUE_TAG) != null) {
            return
        }
        com.qymusic.app.ui.fragment.play.PlayQueueDialogFragment()
            .show(fragmentManager, PLAY_QUEUE_TAG)
    }

    /**
     * 用 Cookie 换账号信息并保存；扫码与手动 Cookie 共用。
     * 成功后会失效缓存并刷新界面。
     */
    private fun saveManualCookie(
        target: String,
        cookie: String,
        onResult: (Boolean) -> Unit = {},
    ) {
        if (!isAdded) {
            onResult(false)
            return
        }
        val api = when (target) {
            PlatformLoginActivity.PLATFORM_QQ -> Api.QQ_ACCOUNT
            PlatformLoginActivity.PLATFORM_KUGOU -> Api.KUGOU_ACCOUNT
            else -> Api.NETEASE_ACCOUNT
        }
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // 账号信息接口偶发失败（QQ 资料接口/酷狗网关风控）时，只要 Cookie 里有 uid
                // 也先把登录态保存下来，避免“刚登录就提示过期”，播放/歌单仍可继续用
                val account = try {
                    Get<com.qymusic.app.model.PlatformAccount>(api) {
                        addQuery("cookie", cookie)
                    }.await()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }

                if (account != null) {
                    when (target) {
                        PlatformLoginActivity.PLATFORM_QQ -> SPConfig.saveQqLogin(
                            cookie, account.userId.toString(), account.nickname, account.avatarUrl
                        )
                        PlatformLoginActivity.PLATFORM_KUGOU -> SPConfig.saveKugouLogin(
                            cookie, account.userId, account.nickname, account.avatarUrl
                        )
                        else -> SPConfig.saveNeteaseLogin(
                            cookie, account.userId, account.nickname, account.avatarUrl
                        )
                    }
                } else {
                    val uid = uidFromCookie(target, cookie)
                    if (uid <= 0L) {
                        toast("Cookie 无效或已过期")
                        onResult(false)
                        return@launch
                    }
                    when (target) {
                        PlatformLoginActivity.PLATFORM_QQ -> SPConfig.saveQqLogin(
                            cookie, uid.toString(), "QQ用户", ""
                        )
                        PlatformLoginActivity.PLATFORM_KUGOU -> SPConfig.saveKugouLogin(
                            cookie, uid, "酷狗用户", ""
                        )
                        else -> {
                            toast("Cookie 无效或已过期")
                            onResult(false)
                            return@launch
                        }
                    }
                }

                invalidateData()
                // 登录的是当前选中的平台才立刻刷新，否则等切过去再加载
                if (platform == target) {
                    refreshUi()
                }
                toast("登录成功" + if (account?.nickname.isNullOrBlank()) "" else "：" + account.nickname)
                onResult(true)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                toast("Cookie 无效或已过期")
                onResult(false)
            }
        }
    }

    /** 从 Cookie 中兜底解析 uid（QQ 的 uin/musicid、酷狗的 userid/KugooID） */
    private fun uidFromCookie(target: String, cookie: String): Long {
        val keys = when (target) {
            PlatformLoginActivity.PLATFORM_QQ -> listOf("uin", "musicid")
            PlatformLoginActivity.PLATFORM_KUGOU -> listOf("userid", "KugooID")
            else -> listOf("userId", "userid")
        }
        for (part in cookie.split(";")) {
            val kv = part.trim().split("=", limit = 2)
            if (kv.size != 2) {
                continue
            }
            if (keys.none { it.equals(kv[0].trim(), ignoreCase = true) }) {
                continue
            }
            val digits = kv[1].filter { it.isDigit() }
            val uid = digits.toLongOrNull() ?: 0L
            if (uid > 0L) {
                return uid
            }
        }
        return 0L
    }

    // ------------------------------------------------------------------ 酷狗签到领 VIP

    /** 副标题：优先展示上次查询到的会员 / 签到状态，Cookie 变化后回到默认文案 */
    private fun kugouVipStateText(): String {
        val cookie = SPConfig.kugouCookie()
        val cached = kugouVipStatusText
        return if (cookie.isNotBlank() && cookie == kugouVipStatusCookie && !cached.isNullOrBlank()) {
            cached
        } else {
            KUGOU_VIP_HINT
        }
    }

    /** 进入云端页时静默刷新一次签到状态（60 秒内不重复请求，失败保留默认文案） */
    private fun refreshKugouVipStatus() {
        val cookie = SPConfig.kugouCookie()
        if (cookie.isBlank()) {
            return
        }
        val now = System.currentTimeMillis()
        if (cookie == kugouVipStatusCookie && now - kugouVipStatusAt < DATA_TTL_MS) {
            return
        }
        if (kugouVipStatusJob?.isActive == true) {
            return
        }
        kugouVipStatusJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val raw = Get<String>(Api.KUGOU_VIP_STATUS) {
                    addQuery("cookie", cookie)
                }.await().orEmpty()
                if (vipError(raw) != null) {
                    return@launch
                }
                val summary = vipSummary(raw).replace("\n", " · ").take(120)
                if (summary.isBlank()) {
                    return@launch
                }
                kugouVipStatusText = summary
                kugouVipStatusCookie = cookie
                kugouVipStatusAt = System.currentTimeMillis()
                if (isAdded && binding.cardKugouVip.isVisible) {
                    binding.tvKugouVipState.text = summary
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // 静默失败：接口异常时保留默认文案，点击签到时再给出明确提示
            }
        }
    }

    /**
     * 签到入口：先查 /kg/vip/status，再弹确认框；确认后调用 /kg/vip/checkin。
     * 服务端返回结构可能随酷狗侧调整，这里沿用宽松解析，未知字段直接展示原始数据。
     */
    private fun checkKugouVip() {
        if (!isAdded || platform != PlatformLoginActivity.PLATFORM_KUGOU) {
            return
        }
        val cookie = SPConfig.kugouCookie()
        if (cookie.isBlank() || !SPConfig.isKugouLoggedIn()) {
            showKugouVipDialog("登录已失效", "酷狗登录信息已失效，请重新登录酷狗概念版账号后再签到。")
            return
        }
        val button = binding.btnKugouCheckin
        if (!button.isEnabled) {
            return
        }
        button.isEnabled = false
        button.text = "查询中…"
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val raw = Get<String>(Api.KUGOU_VIP_STATUS) {
                    addQuery("cookie", cookie)
                }.await().orEmpty()
                val error = vipError(raw)
                if (error != null) {
                    showKugouVipDialog("查询失败", error)
                    return@launch
                }
                val summary = vipSummary(raw)
                if (summary.isNotBlank()) {
                    val compact = summary.replace("\n", " · ").take(120)
                    kugouVipStatusText = compact
                    kugouVipStatusCookie = cookie
                    kugouVipStatusAt = System.currentTimeMillis()
                    if (binding.cardKugouVip.isVisible) {
                        binding.tvKugouVipState.text = compact
                    }
                }
                if (!isAdded) {
                    return@launch
                }
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("酷狗签到领 VIP")
                    .setMessage(summary.ifBlank { "点击「立即签到」领取今日 VIP 权益。" })
                    .setNegativeButton("取消", null)
                    .setPositiveButton("立即签到") { _, _ -> submitKugouCheckin(cookie) }
                    .show()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                showKugouVipDialog("登录已失效", "酷狗 Cookie 已失效，请重新登录酷狗账号后再签到。")
            } finally {
                resetKugouCheckinButton()
            }
        }
    }

    private fun submitKugouCheckin(cookie: String) {
        if (!isAdded) {
            return
        }
        binding.btnKugouCheckin.isEnabled = false
        binding.btnKugouCheckin.text = "签到中…"
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val raw = Get<String>(Api.KUGOU_VIP_CHECKIN) {
                    addQuery("cookie", cookie)
                }.await().orEmpty()
                val error = vipError(raw)
                if (error != null) {
                    showKugouVipDialog("签到失败", error)
                    return@launch
                }
                val summary = vipSummary(raw)
                if (summary.isNotBlank() && binding.cardKugouVip.isVisible) {
                    binding.tvKugouVipState.text = summary.replace("\n", " · ").take(120)
                }
                showKugouVipDialog(
                    "签到成功",
                    summary.ifBlank { "今日签到完成，VIP 权益已到账。" }
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                showKugouVipDialog("登录已失效", "酷狗 Cookie 已失效，请重新登录酷狗账号后再签到。")
            } finally {
                resetKugouCheckinButton()
            }
        }
    }

    private fun resetKugouCheckinButton() {
        if (!isAdded) {
            return
        }
        binding.btnKugouCheckin.isEnabled = true
        binding.btnKugouCheckin.text = "立即签到"
    }

    private fun showKugouVipDialog(title: String, message: String) {
        if (!isAdded) {
            return
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setMessage(message.ifBlank { "暂无数据" })
            .setPositiveButton("知道了", null)
            .show()
    }

    /** 服务端 code 非成功时返回提示文案，成功（或没有 code 字段）返回 null */
    private fun vipError(raw: String): String? {
        val json = runCatching { org.json.JSONObject(raw.trim()) }.getOrNull() ?: return null
        if (!json.has("code")) {
            return null
        }
        val code = json.optInt("code", 0)
        if (code == 200 || code == 0) {
            return null
        }
        return json.optString("msg")
            .ifBlank { json.optString("message") }
            .ifBlank { "Cookie 已失效或未登录，请重新登录酷狗。" }
    }

    /**
     * 把 VIP 状态 / 签到结果整理成可读文本。
     * 字段名不固定，逐个尝试常见命名，全部落空时直接展示原始数据。
     */
    private fun vipSummary(raw: String): String {
        val text = raw.trim()
        if (text.isEmpty()) {
            return ""
        }
        val json = runCatching { org.json.JSONObject(text) }.getOrNull()
            ?: return text.take(300)
        val rawData = json.opt("data")
        if (rawData is String && rawData.isNotBlank()) {
            return rawData.take(300)
        }
        val data = rawData as? org.json.JSONObject ?: json

        fun field(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
            if (!data.has(key)) {
                return@firstNotNullOfOrNull null
            }
            val value = data.opt(key) ?: return@firstNotNullOfOrNull null
            val valueText = if (value is Boolean) {
                if (value) "是" else "否"
            } else {
                value.toString()
            }
            valueText.takeIf { it.isNotBlank() && it != "null" }
        }

        val lines = mutableListOf<String>()
        field("nickname", "nickName", "userName", "nick", "user")?.let { lines += "账号：$it" }
        field(
            "isVip", "vip", "isMember", "vipStatus", "member", "vipType", "status", "state"
        )?.let { lines += "会员状态：$it" }
        field(
            "vipExpire", "vipExpireTime", "expireTime", "vipEndTime", "endTime",
            "expireAt", "vipEndDate", "expireDate"
        )?.let { lines += "到期时间：$it" }
        field(
            "isSign", "todaySign", "todaySignIn", "signed", "signStatus",
            "checkin", "checkinStatus", "isCheckin"
        )?.let { lines += "签到状态：$it" }
        field(
            "reward", "rewardDesc", "rewardName", "vipDays", "days",
            "giftName", "gift", "score", "points", "coin"
        )?.let { lines += "本次奖励：$it" }
        field("msg", "message", "tips", "desc")?.let { lines += it }

        return if (lines.isEmpty()) data.toString().take(300) else lines.joinToString("\n")
    }
}
