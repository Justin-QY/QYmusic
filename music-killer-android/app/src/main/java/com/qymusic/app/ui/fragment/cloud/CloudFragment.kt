package com.qymusic.app.ui.fragment.cloud

import android.content.Intent
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.os.bundleOf
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
import com.qymusic.app.ui.activity.NeteaseLoginActivity

/**
 * 云端模块：登录网易云后查看收藏的歌手 / 歌单 / 我喜欢的音乐
 */
class CloudFragment : BaseMusicAppbarFragment<FragmentCloudBinding>(R.layout.fragment_cloud) {

    private var loading = false

    private val loginLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                refreshUi()
            }
        }

    override fun isAddScrollView(): Boolean = true

    override fun getMusicModels(): List<Any?>? = binding.rvLiked.models

    override fun initView() {
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

        binding.btnLogin.setOnClickListener {
            loginLauncher.launch(Intent(requireContext(), NeteaseLoginActivity::class.java))
        }
        binding.btnLogout.setOnClickListener {
            SPConfig.clearNeteaseLogin()
            binding.rvLiked.models = emptyList<MusicModel>()
            binding.rvPlaylists.models = emptyList<PlayListModel>()
            binding.rvArtists.models = emptyList<ArtistModel>()
            refreshUi()
            toast("已退出网易云账号")
        }
    }

    override fun initData() {
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    private fun refreshUi() {
        val logged = SPConfig.isNeteaseLoggedIn()
        binding.cardLogin.visibility = if (logged) View.GONE else View.VISIBLE
        binding.cardProfile.visibility = if (logged) View.VISIBLE else View.GONE
        binding.groupContent.visibility = if (logged) View.VISIBLE else View.GONE

        if (!logged) {
            return
        }
        binding.tvNickname.text = SPConfig.neteaseNickname().ifBlank { "网易云用户" }
        Glide.with(this)
            .load(SPConfig.neteaseAvatar())
            .transform(CircleCrop())
            .placeholder(R.mipmap.ic_launcher)
            .into(binding.imgAvatar)
        loadData()
    }

    private fun loadData() {
        if (loading) {
            return
        }
        loading = true
        val cookie = SPConfig.neteaseCookie()
        val uid = SPConfig.neteaseUid()

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val liked = async {
                    Get<List<MusicModel>>(Api.NETEASE_LIKED) {
                        addQuery("cookie", cookie)
                        addQuery("uid", uid)
                        addQuery("pageNo", 1)
                        addQuery("pageSize", 30)
                    }.await()
                }
                val playlists = async {
                    Get<List<PlayListModel>>(Api.NETEASE_PLAYLISTS) {
                        addQuery("cookie", cookie)
                        addQuery("uid", uid)
                    }.await()
                }
                val artists = async {
                    Get<List<ArtistModel>>(Api.NETEASE_ARTISTS) {
                        addQuery("cookie", cookie)
                    }.await()
                }

                binding.rvLiked.models = liked.await()
                binding.rvPlaylists.models = playlists.await()
                binding.rvArtists.models = artists.await()
            } catch (e: Exception) {
                toast(e.message ?: "加载云端数据失败")
            } finally {
                loading = false
            }
        }
    }
}
