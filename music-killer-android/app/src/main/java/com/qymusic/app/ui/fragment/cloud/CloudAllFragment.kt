package com.qymusic.app.ui.fragment.cloud

import androidx.core.os.bundleOf
import androidx.lifecycle.lifecycleScope
import com.drake.brv.utils.grid
import com.drake.brv.utils.models
import com.drake.brv.utils.setup
import com.drake.engine.utils.dp
import com.drake.tooltip.toast
import kotlinx.coroutines.launch
import com.qymusic.app.R
import com.qymusic.app.base.BaseMusicAppbarFragment
import com.qymusic.app.databinding.FragmentCloudAllBinding
import com.qymusic.app.model.ArtistModel
import com.qymusic.app.model.MusicModel
import com.qymusic.app.model.PlayListModel
import com.qymusic.app.model.setupMusicRv
import com.qymusic.app.ui.activity.PlatformLoginActivity
import com.qymusic.app.utils.CloudDataCache
import com.qymusic.app.utils.CloudRepository

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
            "playlists" -> binding.rvAll.grid(3, 12.dp).setup {
                addType<PlayListModel>(R.layout.item_playlist)
                R.id.item_playlist.onClick {
                    navController.navigate(
                        R.id.playlistInfoFragment,
                        bundleOf("playlist" to getModel<PlayListModel>())
                    )
                }
            }
            else -> binding.rvAll.grid(3, 12.dp).setup {
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
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                when (type) {
                    "liked" -> {
                        val cacheKey = CloudDataCache.likedKey(platform)
                        // 缓存来自预览页/启动页预取，且始终是完整列表，直接铺满列表
                        CloudDataCache.get<MusicModel>(cacheKey)?.let { cached ->
                            mainViewModel.markMusicStates(cached)
                            binding.rvAll.models = cached.toMutableList()
                            if (CloudDataCache.isFresh(cacheKey)) {
                                return@launch
                            }
                        }
                        val list = CloudRepository.liked(platform)
                        if (list.isEmpty()) {
                            return@launch
                        }
                        mainViewModel.markMusicStates(list)
                        binding.rvAll.models = list
                    }
                    "playlists" -> {
                        val cacheKey = CloudDataCache.playlistsKey(platform)
                        CloudDataCache.get<PlayListModel>(cacheKey)?.let { cached ->
                            binding.rvAll.models = cached.toMutableList()
                            if (CloudDataCache.isFresh(cacheKey)) {
                                return@launch
                            }
                        }
                        val list = CloudRepository.playlists(platform)
                        if (list.isNotEmpty()) {
                            binding.rvAll.models = list
                        }
                    }
                    else -> {
                        val cacheKey = CloudDataCache.artistsKey(platform)
                        CloudDataCache.get<ArtistModel>(cacheKey)?.let { cached ->
                            binding.rvAll.models = cached.toMutableList()
                            if (CloudDataCache.isFresh(cacheKey)) {
                                return@launch
                            }
                        }
                        val list = CloudRepository.artists(platform)
                        if (list.isNotEmpty()) {
                            binding.rvAll.models = list
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                toast(e.message ?: "加载失败")
            }
        }
    }
}
