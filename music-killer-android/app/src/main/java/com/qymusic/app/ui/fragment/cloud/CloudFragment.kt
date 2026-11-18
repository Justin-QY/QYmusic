package com.qymusic.app.ui.fragment.cloud

import android.content.Intent
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.os.bundleOf
import androidx.core.view.MenuProvider
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

/**
 * 云端模块：支持网易云 / QQ 音乐 / 酷狗概念版 三个平台，可同时绑定，
 * 切换平台显示该平台的我喜欢的音乐与收藏歌单。
 */
class CloudFragment : BaseMusicAppbarFragment<FragmentCloudBinding>(R.layout.fragment_cloud),
    MenuProvider {

    private companion object {
        const val DATA_TTL_MS = 60_000L
        const val QR_TIMEOUT_MS = 180_000L
    }

    private var platform = PlatformLoginActivity.PLATFORM_NETEASE
    private var firstResume = true

    /** 当前平台数据缓存，避免每次 onResume 都重新请求 */
    private var loadedPlatform: String? = null
    private var loadedAt = 0L

    /** 当前在跑的数据请求；切平台时取消上一个，避免旧结果覆盖新平台 */
    private var loadJob: kotlinx.coroutines.Job? = null
    private var loadPlatform: String? = null

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
            if (!isLogged(platform)) {
                // 自动切到已登录的平台
                listOf(
                    PlatformLoginActivity.PLATFORM_NETEASE,
                    PlatformLoginActivity.PLATFORM_QQ,
                    PlatformLoginActivity.PLATFORM_KUGOU,
                ).firstOrNull { isLogged(it) }?.let { platform = it }
            }
        }
        refreshUi()
    }

    private fun selectPlatform(target: String) {
        if (target == platform) {
            return
        }
        platform = target
        refreshUi()
    }

    private fun refreshUi() {
        val logged = isLogged(platform)
        binding.cardLogin.visibility = if (logged) View.GONE else View.VISIBLE
        binding.cardProfile.visibility = if (logged) View.VISIBLE else View.GONE
        binding.groupContent.visibility = if (logged) View.VISIBLE else View.GONE

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
        if (menuItem.itemId == R.id.settings_cloud) {
            navController.navigate(R.id.action_setting)
            return true
        }
        return false
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
}
