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
 * 云端模块：支持网易云 / QQ 音乐 / 酷我 三个平台，可同时绑定，
 * 切换平台显示该平台的我喜欢的音乐与收藏歌单。
 */
class CloudFragment : BaseMusicAppbarFragment<FragmentCloudBinding>(R.layout.fragment_cloud),
    MenuProvider {

    private var platform = PlatformLoginActivity.PLATFORM_NETEASE
    private var loading = false
    private var firstResume = true

    private val loginLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK) {
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
        binding.chipKuwo.setOnClickListener { selectPlatform(PlatformLoginActivity.PLATFORM_KUWO) }

        binding.btnLogin.setOnClickListener {
            loginLauncher.launch(
                Intent(requireContext(), PlatformLoginActivity::class.java)
                    .putExtra(PlatformLoginActivity.EXTRA_PLATFORM, platform)
            )
        }
        binding.btnLogout.setOnClickListener { logout() }
        binding.tvManualCookie.setOnClickListener { showManualCookieDialog() }
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
                    PlatformLoginActivity.PLATFORM_KUWO,
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
        styleChip(binding.chipKuwo, platform == PlatformLoginActivity.PLATFORM_KUWO)
        binding.btnLogin.text = "登录" + platformName()

        val isNetease = platform == PlatformLoginActivity.PLATFORM_NETEASE
        binding.tvArtistsTitle.visibility = if (isNetease) View.VISIBLE else View.GONE
        binding.rvArtists.visibility = if (isNetease) View.VISIBLE else View.GONE

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

    private fun loadData() {
        if (loading) {
            return
        }
        loading = true
        val cookie = cookieOf(platform)
        val uid = uidOf(platform)

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val likedUrl = when (platform) {
                    PlatformLoginActivity.PLATFORM_QQ -> Api.QQ_LIKED
                    PlatformLoginActivity.PLATFORM_KUWO -> Api.KUWO_LIKED
                    else -> Api.NETEASE_LIKED
                }
                val playlistsUrl = when (platform) {
                    PlatformLoginActivity.PLATFORM_QQ -> Api.QQ_PLAYLISTS
                    PlatformLoginActivity.PLATFORM_KUWO -> Api.KUWO_PLAYLISTS
                    else -> Api.NETEASE_PLAYLISTS
                }
                val isNetease = platform == PlatformLoginActivity.PLATFORM_NETEASE

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

                binding.rvLiked.models = liked.await().take(5)
                binding.rvPlaylists.models = playlists.await().take(6)
                if (artists != null) {
                    binding.rvArtists.models = artists.await().take(6)
                }
            } catch (e: Exception) {
                toast(e.message ?: "加载云端数据失败")
            } finally {
                loading = false
            }
        }
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
            PlatformLoginActivity.PLATFORM_KUWO -> SPConfig.clearKuwoLogin()
            else -> SPConfig.clearNeteaseLogin()
        }
        // 清掉 WebView 登录态，否则下次点登录会直接回到原账号
        val cookieManager = android.webkit.CookieManager.getInstance()
        cookieManager.removeAllCookies(null)
        cookieManager.flush()
        android.webkit.WebStorage.getInstance().deleteAllData()

        binding.rvLiked.models = emptyList<MusicModel>()
        binding.rvPlaylists.models = emptyList<PlayListModel>()
        binding.rvArtists.models = emptyList<ArtistModel>()
        refreshUi()
        toast("已退出" + platformName() + "账号")
    }

    private fun platformName(): String = when (platform) {
        PlatformLoginActivity.PLATFORM_QQ -> "QQ音乐"
        PlatformLoginActivity.PLATFORM_KUWO -> "酷我"
        else -> "网易云"
    }

    private fun isLogged(p: String): Boolean = when (p) {
        PlatformLoginActivity.PLATFORM_QQ -> SPConfig.isQqLoggedIn()
        PlatformLoginActivity.PLATFORM_KUWO -> SPConfig.isKuwoLoggedIn()
        else -> SPConfig.isNeteaseLoggedIn()
    }

    private fun cookieOf(p: String): String = when (p) {
        PlatformLoginActivity.PLATFORM_QQ -> SPConfig.qqCookie()
        PlatformLoginActivity.PLATFORM_KUWO -> SPConfig.kuwoCookie()
        else -> SPConfig.neteaseCookie()
    }

    private fun uidOf(p: String): Long = when (p) {
        PlatformLoginActivity.PLATFORM_QQ -> SPConfig.qqUin().toLongOrNull() ?: 0L
        PlatformLoginActivity.PLATFORM_KUWO -> SPConfig.kuwoUid()
        else -> SPConfig.neteaseUid()
    }

    private fun nickname(): String = when (platform) {
        PlatformLoginActivity.PLATFORM_QQ -> SPConfig.qqNickname()
        PlatformLoginActivity.PLATFORM_KUWO -> SPConfig.kuwoNickname()
        else -> SPConfig.neteaseNickname()
    }

    private fun avatar(): String = when (platform) {
        PlatformLoginActivity.PLATFORM_QQ -> SPConfig.qqAvatar()
        PlatformLoginActivity.PLATFORM_KUWO -> SPConfig.kuwoAvatar()
        else -> SPConfig.neteaseAvatar()
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
     * 网页登录被风控/打不开时的手动 Cookie 兜底。
     */
    private fun showManualCookieDialog() {
        val density = resources.displayMetrics.density
        val input = android.widget.EditText(requireContext()).apply {
            hint = "粘贴浏览器请求头里的完整 Cookie"
            minLines = 3
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
        }
        val container = android.widget.FrameLayout(requireContext()).apply {
            val padding = (20 * density).toInt()
            setPadding(padding, (8 * density).toInt(), padding, 0)
            addView(
                input,
                android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(platformName() + " Cookie")
            .setMessage("在浏览器登录 " + platformName() + " 后，复制任意请求的 Cookie 粘贴到这里。只保存在本机。")
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                val cookie = input.text.toString().trim()
                if (cookie.isBlank()) {
                    toast("Cookie 不能为空")
                    return@setPositiveButton
                }
                saveManualCookie(cookie)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun saveManualCookie(cookie: String) {
        val api = when (platform) {
            PlatformLoginActivity.PLATFORM_QQ -> Api.QQ_ACCOUNT
            PlatformLoginActivity.PLATFORM_KUWO -> Api.KUWO_ACCOUNT
            else -> Api.NETEASE_ACCOUNT
        }
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val account = Get<com.qymusic.app.model.PlatformAccount>(api) {
                    addQuery("cookie", cookie)
                }.await()
                when (platform) {
                    PlatformLoginActivity.PLATFORM_QQ -> SPConfig.saveQqLogin(
                        cookie, account.userId.toString(), account.nickname, account.avatarUrl
                    )
                    PlatformLoginActivity.PLATFORM_KUWO -> SPConfig.saveKuwoLogin(
                        cookie, account.userId, account.nickname, account.avatarUrl
                    )
                    else -> SPConfig.saveNeteaseLogin(
                        cookie, account.userId, account.nickname, account.avatarUrl
                    )
                }
                refreshUi()
                toast("登录成功")
            } catch (e: Exception) {
                toast("Cookie 无效或已过期")
            }
        }
    }
}
