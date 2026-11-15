package com.qymusic.app.ui.fragment.cloud

import androidx.core.os.bundleOf
import androidx.lifecycle.lifecycleScope
import com.drake.brv.utils.grid
import com.drake.brv.utils.models
import com.drake.brv.utils.setup
import com.drake.engine.utils.dp
import com.drake.net.Get
import com.drake.tooltip.toast
import kotlinx.coroutines.launch
import com.qymusic.app.R
import com.qymusic.app.base.BaseMusicAppbarFragment
import com.qymusic.app.config.Api
import com.qymusic.app.config.SPConfig
import com.qymusic.app.databinding.FragmentCloudAllBinding
import com.qymusic.app.model.ArtistModel
import com.qymusic.app.model.MusicModel
import com.qymusic.app.model.PlayListModel
import com.qymusic.app.model.setupMusicRv
import com.qymusic.app.ui.activity.PlatformLoginActivity

/**
 * 云端「查看全部」列表：喜欢 / 歌单 / 歌手
 */
class CloudAllFragment : BaseMusicAppbarFragment<FragmentCloudAllBinding>(R.layout.fragment_cloud_all) {

    private val type get() = requireArguments().getString("type").orEmpty()
    private val platform get() = requireArguments().getString("platform")
        ?: PlatformLoginActivity.PLATFORM_NETEASE

    override fun isAddScrollView(): Boolean = false

    override fun openMediaController(): Boolean = type == "liked"

    override fun getMusicModels(): List<Any?>? = binding.rvAll.models

    override fun initView() {
        when (type) {
            "liked" -> binding.rvAll.setupMusicRv(this)
            "playlists" -> binding.rvAll.grid(3).setup {
                addType<PlayListModel>(R.layout.item_playlist)
                R.id.item_playlist.onClick {
                    navController.navigate(
                        R.id.playlistInfoFragment,
                        bundleOf("playlist" to getModel<PlayListModel>())
                    )
                }
            }
            else -> binding.rvAll.grid(3).setup {
                addType<ArtistModel>(R.layout.item_grid_artist)
                R.id.item_artist.onClick {
                    navController.navigate(
                        R.id.artistInfoFragment,
                        bundleOf("artist" to getModel<ArtistModel>())
                    )
                }
            }
        }
        load()
    }

    override fun initData() {
    }

    private fun load() {
        val cookie = when (platform) {
            PlatformLoginActivity.PLATFORM_QQ -> SPConfig.qqCookie()
            PlatformLoginActivity.PLATFORM_KUWO -> SPConfig.kuwoCookie()
            else -> SPConfig.neteaseCookie()
        }
        val uid = when (platform) {
            PlatformLoginActivity.PLATFORM_QQ -> SPConfig.qqUin().toLongOrNull() ?: 0L
            PlatformLoginActivity.PLATFORM_KUWO -> SPConfig.kuwoUid()
            else -> SPConfig.neteaseUid()
        }
        val isNetease = platform == PlatformLoginActivity.PLATFORM_NETEASE

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                when (type) {
                    "liked" -> {
                        val url = when (platform) {
                            PlatformLoginActivity.PLATFORM_QQ -> Api.QQ_LIKED
                            PlatformLoginActivity.PLATFORM_KUWO -> Api.KUWO_LIKED
                            else -> Api.NETEASE_LIKED
                        }
                        val list = Get<List<MusicModel>>(url) {
                            addQuery("cookie", cookie)
                            if (isNetease) {
                                addQuery("uid", uid)
                                addQuery("pageNo", 1)
                                addQuery("pageSize", 300)
                            } else {
                                addQuery("limit", 300)
                            }
                        }.await()
                        binding.rvAll.models = list
                    }
                    "playlists" -> {
                        val url = when (platform) {
                            PlatformLoginActivity.PLATFORM_QQ -> Api.QQ_PLAYLISTS
                            PlatformLoginActivity.PLATFORM_KUWO -> Api.KUWO_PLAYLISTS
                            else -> Api.NETEASE_PLAYLISTS
                        }
                        val list = Get<List<PlayListModel>>(url) {
                            addQuery("cookie", cookie)
                            if (isNetease) {
                                addQuery("uid", uid)
                            }
                        }.await()
                        binding.rvAll.models = list
                    }
                    else -> {
                        val list = Get<List<ArtistModel>>(Api.NETEASE_ARTISTS) {
                            addQuery("cookie", cookie)
                        }.await()
                        binding.rvAll.models = list
                    }
                }
            } catch (e: Exception) {
                toast(e.message ?: "加载失败")
            }
        }
    }
}
