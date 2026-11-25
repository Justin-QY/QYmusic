package com.qymusic.app.service;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.core.content.ContextCompat;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.ResolvingDataSource;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.util.EventLogger;
import androidx.media3.session.CommandButton;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;
import androidx.media3.session.SessionCommand;
import androidx.media3.session.SessionCommands;
import androidx.media3.session.SessionResult;

import com.drake.net.Net;
import com.drake.net.request.UrlRequest;
import com.drake.tooltip.ToastKt;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import org.json.JSONObject;
import org.litepal.LitePal;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import com.qymusic.app.QYMusicApplication;
import com.qymusic.app.R;
import com.qymusic.app.config.Api;
import com.qymusic.app.config.SPConfig;
import com.qymusic.app.constants.IntentActions;
import com.qymusic.app.constants.IntentExtras;
import com.qymusic.app.model.FavoriteModel;
import com.qymusic.app.model.MusicModel;
import com.qymusic.app.model.PlayInfo;
import com.qymusic.app.ui.activity.MainActivity;
import com.qymusic.app.utils.PlaybackStateStore;
import com.qymusic.app.utils.PlayUtilsKt;
import com.qymusic.app.utils.lyric.SuperIslandLyrics;
import com.qymusic.app.utils.converter.JavaSerializationConverter;

/**
 * 音乐服务
 */
public class MusicService extends MediaSessionService implements Player.Listener {

    private static final String TAG = "MusicService";

    // 自定义命令
    private static final String CUSTOM_COMMAND_TOGGLE_FAVORITE = "TOGGLE_FAVORITE";
    private static final String CUSTOM_COMMAND_SHOW_LYRICS = "SHOW_LYRICS";

    private ExoPlayer mExoPlayer;

    private MediaSession mediaSession;

    private final EventLogger mEventLogger = new EventLogger();

    // 收藏状态
    private boolean isFavorite = false;

    private MusicBroadcastReceiver musicBroadcastReceiver;

    /** 播放中记录进度的间隔 */
    private static final long SAVE_POSITION_INTERVAL_MS = 5_000L;

    /** 提前结束检测：短于这个时长的音频不判断，避免误伤铃声 / 短音频 */
    private static final long MIN_DURATION_FOR_EARLY_END_MS = 120_000L;

    /** 提前结束检测需要最后一刻的播放位置，按秒跟踪 */
    private static final long TRACK_POSITION_INTERVAL_MS = 1_000L;

    private final Handler stateSaveHandler = new Handler(Looper.getMainLooper());

    /** 已经自动恢复过的歌曲（source|id），同一首歌只重试一次，防止死循环 */
    private final java.util.Set<String> truncationRecoveryDone =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 已经因为播放错误重连过的（source|id|错误码），防止死循环 */
    private final java.util.Set<String> errorRecoveryDone =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 最近一次观察到的播放位置，用于判断歌曲是否「没放完」 */
    private volatile long trackedPositionMs = 0L;

    /** 上一个播放项；AUTO 切歌时它就是刚刚结束的那一首 */
    private MediaItem lastTransitionItem;
    private int lastTransitionIndex = C.INDEX_UNSET;

    /** 正在恢复上次队列：期间的播放器回调不要回写落盘，避免把恢复的进度覆盖成 0 */
    private boolean restoringState = false;

    private final Runnable trackPositionTask = new Runnable() {
        @Override
        public void run() {
            if (mExoPlayer == null) {
                return;
            }
            trackedPositionMs = Math.max(0L, mExoPlayer.getCurrentPosition());
            if (mExoPlayer.isPlaying()) {
                stateSaveHandler.postDelayed(this, TRACK_POSITION_INTERVAL_MS);
            }
        }
    };

    private final Runnable savePositionTask = new Runnable() {
        @Override
        public void run() {
            if (mExoPlayer == null) {
                return;
            }
            PlaybackStateStore.savePosition(mExoPlayer);
            if (mExoPlayer.isPlaying()) {
                stateSaveHandler.postDelayed(this, SAVE_POSITION_INTERVAL_MS);
            }
        }
    };

    @SuppressLint("UnsafeOptInUsageError")
    private class MediaSessionCallback implements MediaSession.Callback {

        @Override
        public MediaSession.ConnectionResult onConnect(MediaSession session, MediaSession.ControllerInfo controller) {
            // 创建可用的自定义命令
            SessionCommands sessionCommands = new SessionCommands.Builder()
                    .add(new SessionCommand(CUSTOM_COMMAND_TOGGLE_FAVORITE, Bundle.EMPTY))
                    .add(new SessionCommand(CUSTOM_COMMAND_SHOW_LYRICS, Bundle.EMPTY))
                    .build();

            return new MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailableSessionCommands(sessionCommands)
                    .build();
        }

        @NonNull
        @Override
        public ListenableFuture<SessionResult> onCustomCommand(
                @NonNull MediaSession session,
                @NonNull MediaSession.ControllerInfo controller,
                @NonNull SessionCommand customCommand,
                @NonNull Bundle args) {

            // 处理自定义命令
            switch (customCommand.customAction) {
                case CUSTOM_COMMAND_TOGGLE_FAVORITE:
                    handleToggleFavorite();
                    break;
                case CUSTOM_COMMAND_SHOW_LYRICS:
                    handleShowLyrics();
                    break;
            }

            return Futures.immediateFuture(new SessionResult(SessionResult.RESULT_SUCCESS));
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        initEXOPlayer();
        restorePlaybackState();

        musicBroadcastReceiver = new MusicBroadcastReceiver();
        IntentFilter filter = new IntentFilter(IntentActions.FAVORITE);
        filter.addAction(IntentActions.SUPER_ISLAND_SETTING);
        ContextCompat.registerReceiver(this, musicBroadcastReceiver, filter,
                ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    @Nullable
    @Override
    public MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) {
        if (mediaSession == null) {
            initEXOPlayer();
        }
        return mediaSession;
    }

    /**
     * 恢复上次的播放队列和进度。
     *
     * 只恢复队列并暂停在当前曲目，不自动播放：进程被杀后重新打开 App，
     * 底部播放栏能看到上一首，点播放即可继续。
     */
    private void restorePlaybackState() {
        if (mExoPlayer == null || mExoPlayer.getMediaItemCount() > 0) {
            return;
        }
        PlaybackStateStore.Snapshot snapshot = PlaybackStateStore.load();
        if (snapshot == null) {
            return;
        }
        String bridge = SPConfig.currentBridge();
        List<MediaItem> items = new ArrayList<>();
        for (MusicModel model : snapshot.getItems()) {
            if (model == null || model.getId() == 0L) {
                continue;
            }
            items.add(PlayUtilsKt.toMediaItem(model, bridge));
        }
        if (items.isEmpty()) {
            return;
        }
        int index = Math.max(0, Math.min(snapshot.getIndex(), items.size() - 1));
        long position = Math.max(0L, snapshot.getPosition());
        restoringState = true;
        try {
            mExoPlayer.setMediaItems(items, index, position);
        } finally {
            restoringState = false;
        }
        // 上次是「在播」状态（进程被系统清理 / 服务被重建）：接着播，
        // 否则用户切几次后台回来就变成暂停，感觉像歌被吃掉了。
        if (snapshot.getPlaying()) {
            mExoPlayer.play();
        }
        Log.i(TAG, "已恢复上次播放: " + items.size() + " 首, index=" + index
                + ", position=" + position + ", playing=" + snapshot.getPlaying());
    }

    @Override
    public void onMediaItemTransition(@Nullable MediaItem mediaItem, int reason) {
        if (mExoPlayer != null && !restoringState) {
            PlaybackStateStore.save(mExoPlayer);
        }
        // 自动切歌时检查上一首是否「没放完」：试听片段 / 被缓存成片段的歌曲会在这里恢复
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
            checkEarlyEnd(lastTransitionItem, lastTransitionIndex);
        }
        lastTransitionItem = mediaItem;
        lastTransitionIndex = mExoPlayer == null ? C.INDEX_UNSET : mExoPlayer.getCurrentMediaItemIndex();
        if (mediaItem == null) {
            return;
        }

        MediaMetadata mediaMetadata = mediaItem.mediaMetadata;
        MusicModel model = PlayUtilsKt.getMusicInfo(mediaMetadata);

        if (model == null) {
            return;
        }

        if (model.isFavorite() != isFavorite) {
            isFavorite = model.isFavorite();
            setCustomLayout();
        }
    }

    @Override
    public void onIsPlayingChanged(boolean isPlaying) {
        if (mExoPlayer == null) {
            return;
        }
        // 播放 / 暂停切换时只记录进度和播放标志，不重新序列化整个队列：
        // 队列内容只在切歌时变化，onIsPlayingChanged 里做全量 capture 会让主线程
        // 反射读取几百首歌的元数据，切换前后台时会明显卡一下。
        if (!restoringState) {
            PlaybackStateStore.savePosition(mExoPlayer);
        }
        stateSaveHandler.removeCallbacks(savePositionTask);
        stateSaveHandler.removeCallbacks(trackPositionTask);
        if (isPlaying) {
            stateSaveHandler.postDelayed(savePositionTask, SAVE_POSITION_INTERVAL_MS);
            trackedPositionMs = Math.max(0L, mExoPlayer.getCurrentPosition());
            stateSaveHandler.postDelayed(trackPositionTask, TRACK_POSITION_INTERVAL_MS);
        }
    }

    /**
     * 歌曲没放完就自动切下一首时的兜底。
     *
     * <p>常见原因：音源只返回了 30~60 秒的试听片段，或者旧版本把片段缓存成了完整音频。
     * 这里与歌曲元数据里的时长对比，明显偏短就清掉该曲缓存、重新解析并重播一次；
     * 同一首歌只重试一次，避免音源真的不完整时反复循环。</p>
     */
    private void checkEarlyEnd(@Nullable MediaItem finishedItem, int finishedIndex) {
        if (finishedItem == null || mExoPlayer == null || finishedIndex == C.INDEX_UNSET) {
            return;
        }
        MusicModel model = PlayUtilsKt.getMusicInfo(finishedItem.mediaMetadata);
        if (model == null || model.getId() == 0L) {
            return;
        }
        long durationValue = model.getDuration();
        // 目录接口返回秒；兼容可能返回毫秒的历史数据
        long expectedMs = durationValue > 3600L ? durationValue : durationValue * 1000L;
        if (expectedMs < MIN_DURATION_FOR_EARLY_END_MS) {
            return;
        }
        long playedMs = trackedPositionMs;
        long endThresholdMs = expectedMs * 7L / 10L;
        if (playedMs <= 0L || playedMs >= endThresholdMs) {
            return;
        }
        if (expectedMs - playedMs < 30_000L) {
            return;
        }
        String retryKey = model.getSource() + "|" + model.getId();
        if (!truncationRecoveryDone.add(retryKey)) {
            Log.w(TAG, "歌曲再次提前结束，已重试过 id=" + model.getId());
            ToastKt.toast("该歌曲音源不完整，请换一首试试");
            return;
        }

        String bridge = bridgeOf(finishedItem);
        Log.w(TAG, "歌曲提前结束，疑似试听片段 id=" + model.getId()
                + " played=" + playedMs + "ms expected=" + expectedMs + "ms bridge=" + bridge);
        // 不要在播放器回调里直接改播放队列，交给消息队列，避免回调重入
        stateSaveHandler.post(() -> recoverTruncated(model, finishedIndex, bridge));
    }

    /** 清理这首曲目的缓存 / 解析结果，并重新播放一次 */
    private void recoverTruncated(MusicModel model, int finishedIndex, String bridge) {
        if (mExoPlayer == null) {
            return;
        }
        int count = mExoPlayer.getMediaItemCount();
        if (count <= 0) {
            return;
        }
        int target = Math.max(0, Math.min(finishedIndex, count - 1));
        // 队列可能已经变化，确认目标位置还是这首歌再替换
        if (!String.valueOf(model.getId()).equals(mExoPlayer.getMediaItemAt(target).mediaId)) {
            Log.w(TAG, "队列已变化，取消提前结束恢复 id=" + model.getId());
            return;
        }
        try {
            QYMusicApplication.getSimpleCache(this)
                    .removeResource(PlayUtilsKt.mediaCacheKey(model.getSource(),
                            String.valueOf(model.getId()), bridge));
            // 旧版本的缓存 key（不带音质、版本更低），可能缓存过试听片段
            QYMusicApplication.getSimpleCache(this)
                    .removeResource(model.getSource() + "-" + model.getId() + "-v3");
            QYMusicApplication.getSimpleCache(this)
                    .removeResource(model.getSource() + "-" + model.getId() + "-v2");
        } catch (Exception e) {
            Log.w(TAG, "清理音频缓存失败: " + e.getMessage());
        }
        ApiUriResolve.clearCachedUrl(model.getSource(), String.valueOf(model.getId()), bridge);

        MediaItem fresh = PlayUtilsKt.toMediaItem(model, bridge);
        mExoPlayer.replaceMediaItem(target, fresh);
        mExoPlayer.seekTo(target, 0);
        mExoPlayer.play();
        ToastKt.toast("音源不完整，正在重新加载");
    }

    /** 读取播放项上的实际音质；取不到时回落到当前设置 */
    private String bridgeOf(MediaItem item) {
        Uri uri = item.requestMetadata.mediaUri;
        String bridge = uri == null ? null : uri.getQueryParameter("bridge");
        if (bridge == null || bridge.isEmpty()) {
            bridge = SPConfig.currentBridge();
        }
        return bridge;
    }

    @Override
    public void onPlaybackStateChanged(int playbackState) {
        if (mExoPlayer == null) {
            return;
        }
        // 播放结束 / 被打回 IDLE 时保存最后的进度
        if (!restoringState
                && (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE)) {
            PlaybackStateStore.save(mExoPlayer);
        }
    }

    /**
     * 播放出错（网络抖动、解析地址过期）时自动重连一次。
     *
     * <p>之前出错后播放器直接落到 IDLE，界面看起来就是「歌自己暂停了」。
     * 这里清掉该曲的解析地址缓存，从原进度重新 prepare + seek + play，
     * 同一首歌的同一个错误码只重试一次，避免网络真的不通时来回重试。</p>
     */
    @Override
    public void onPlayerError(@NonNull PlaybackException error) {
        if (mExoPlayer == null || restoringState) {
            return;
        }
        int index = mExoPlayer.getCurrentMediaItemIndex();
        long position = Math.max(0L, mExoPlayer.getCurrentPosition());
        MediaItem item = mExoPlayer.getCurrentMediaItem();
        MusicModel model = item == null ? null : PlayUtilsKt.getMusicInfo(item.mediaMetadata);
        Log.e(TAG, "播放错误 code=" + error.getErrorCodeName()
                + " id=" + (model == null ? "-" : model.getId()) + " index=" + index, error);
        if (model == null || model.getId() == 0L || index == C.INDEX_UNSET) {
            return;
        }
        String key = model.getSource() + "|" + model.getId() + "|" + error.errorCode;
        if (!errorRecoveryDone.add(key)) {
            ToastKt.toast("播放失败，请检查网络后重试");
            return;
        }
        String bridge = bridgeOf(item);
        stateSaveHandler.post(() -> retryAfterError(model, index, position, bridge));
    }

    /** 清掉过期的解析地址后，从原位置重新连接 */
    private void retryAfterError(MusicModel model, int index, long position, String bridge) {
        if (mExoPlayer == null || mExoPlayer.getCurrentMediaItemIndex() != index) {
            return;
        }
        ApiUriResolve.clearCachedUrl(model.getSource(), String.valueOf(model.getId()), bridge);
        try {
            mExoPlayer.prepare();
            mExoPlayer.seekTo(index, position);
            mExoPlayer.play();
            ToastKt.toast("播放中断，正在重新连接");
        } catch (Exception e) {
            Log.w(TAG, "播放错误恢复失败: " + e.getMessage());
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    private void initEXOPlayer() {
        if (mExoPlayer != null) {
            destroyPlayer();
        }

        // 配置 HTTP 数据源工厂
        DefaultHttpDataSource.Factory httpDataSourceFactory = new DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true);

        // 配置缓存数据源工厂，将 HTTP 数据源作为上游
        CacheDataSource.Factory cacheDataSourceFactory = new CacheDataSource.Factory()
                .setCache(QYMusicApplication.getSimpleCache(this))
                .setUpstreamDataSourceFactory(httpDataSourceFactory)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);

        // 自定义 DataSource.Factory，使用 DefaultDataSource 包装 CacheDataSource (作为 baseDataSource 用于 HTTP)
        DefaultMediaSourceFactory mediaSourceFactory = getDefaultMediaSourceFactory(cacheDataSourceFactory);

        mExoPlayer = new ExoPlayer.Builder(this)
                .setHandleAudioBecomingNoisy(true) // 耳机拔出时停止播�?
                .setWakeMode(C.WAKE_MODE_NETWORK) // 保持唤醒
                .setMediaSourceFactory(mediaSourceFactory)
                .build();

        mExoPlayer.removeListener(this);
        mExoPlayer.addListener(this);

        // mExoPlayer.setPlayWhenReady(true); // 设置准备后自动播放

        mediaSession = new MediaSession.Builder(this, mExoPlayer)
                .setCallback(new MediaSessionCallback())
                .setSessionActivity(PendingIntent.getActivity(this, 0,
                        new Intent(this, MainActivity.class),
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
                .build();

        // 设置自定义布局（通知栏按钮）
        setCustomLayout();

        // 澎湃 OS 超级岛歌词（开关关闭时内部直接 no-op）
        SuperIslandLyrics.refresh(this, mExoPlayer);

        mExoPlayer.removeAnalyticsListener(mEventLogger);
        mExoPlayer.addAnalyticsListener(mEventLogger);
    }

    @OptIn(markerClass = UnstableApi.class)
    @NonNull
    private DefaultMediaSourceFactory getDefaultMediaSourceFactory(CacheDataSource.Factory cacheDataSourceFactory) {
        DataSource.Factory dataSourceFactory = new DataSource.Factory() {
            @NonNull
            @Override
            public DataSource createDataSource() {
                // DefaultDataSource 会自动处理 file, asset, content 等协议
                // 对于 http/https，它会使用我们传入的 baseDataSource (即 cacheDataSource)
                return new DefaultDataSource(MusicService.this, cacheDataSourceFactory.createDataSource());
            }
        };

        // 在上面包装 ResolvingDataSource.Factory，用于自定义 HTTP 解析
        ResolvingDataSource.Factory resolvingDataSourceFactory = new ResolvingDataSource.Factory(dataSourceFactory, new ApiUriResolve());
        return new DefaultMediaSourceFactory(resolvingDataSourceFactory);
    }

    /**
     * 设置通知栏自定义按钮布局
     */
    @SuppressWarnings("deprecation")
    private void setCustomLayout() {
        // 创建收藏按钮
        CommandButton favoriteButton = new CommandButton.Builder()
                .setIconResId(isFavorite ? R.drawable.baseline_favorite_24 : R.drawable.baseline_favorite_border_24)
                .setDisplayName(getString(R.string.favorite))
                .setSessionCommand(new SessionCommand(CUSTOM_COMMAND_TOGGLE_FAVORITE, Bundle.EMPTY))
                .build();

        // 创建歌词按钮
        CommandButton lyricsButton = new CommandButton.Builder()
                .setIconResId(R.drawable.baseline_lyrics_24)
                .setDisplayName(getString(R.string.lyrics))
                .setSessionCommand(new SessionCommand(CUSTOM_COMMAND_SHOW_LYRICS, Bundle.EMPTY))
                .build();

        // 设置到MediaSession
        ImmutableList<CommandButton> customLayout = ImmutableList.of(
                favoriteButton,
                lyricsButton
        );

        mediaSession.setCustomLayout(customLayout);
    }

    /**
     * 处理收藏/取消收藏
     */
    private void handleToggleFavorite() {
        MediaItem currentMediaItem = mExoPlayer.getCurrentMediaItem();
        if (currentMediaItem == null) {
            return;
        }

        String musicId = currentMediaItem.mediaId;

        new Thread(() -> {
            FavoriteModel favoriteModel = LitePal.where("musicId = ?", musicId)
                    .findFirst(FavoriteModel.class);

            MediaMetadata mediaMetadata = currentMediaItem.mediaMetadata;

            if (favoriteModel == null) {
                MusicModel model = PlayUtilsKt.getMusicInfo(mediaMetadata);
                new FavoriteModel(model).save();
                isFavorite = true;
            } else {
                LitePal.deleteAll(FavoriteModel.class, "musicId = ?", musicId);
                isFavorite = false;
            }

            Log.d(TAG, "Toggle favorite: " + isFavorite);

            sendBroadcast(new Intent("com.qymusic.app.favorite")
                    .setPackage(getPackageName())
                    .putExtra("musicId", Long.parseLong(musicId))
                    .putExtra("isFavorite", isFavorite));
            // 更新通知栏按钮状态
            // new Handler(Looper.getMainLooper()).post(this::setCustomLayout);

        }).start();
    }

    /**
     * 处理显示歌词
     */
    private void handleShowLyrics() {
        Log.d(TAG, "Show lyrics clicked");

        // 创建Intent打开MainActivity并显示歌词
        Intent intent = new Intent(this, MainActivity.class);
        intent.setAction("ACTION_SHOW_LYRICS");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);

        ToastKt.toast("打开歌词");

        // TODO: 可以通过广播或其他方式通知Activity显示歌词界面
    }

    /**
     * 销毁播放器
     */
    private void destroyPlayer() {
        if (mExoPlayer != null && mExoPlayer.isCommandAvailable(Player.COMMAND_RELEASE)) {
            mExoPlayer.release();
        }
        if (mediaSession != null) {
            mediaSession.release();
            mediaSession = null;
        }
        mExoPlayer = null;
    }

    @Override
    public void onTaskRemoved(@Nullable Intent rootIntent) {
        // 从最近任务划掉时先保存队列，再走 Media3 默认逻辑
        if (mExoPlayer != null) {
            PlaybackStateStore.save(mExoPlayer);
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        if (mExoPlayer != null) {
            PlaybackStateStore.save(mExoPlayer);
        }
        stateSaveHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
        SuperIslandLyrics.detach();
        destroyPlayer();
        unregisterReceiver(musicBroadcastReceiver);
    }

    @UnstableApi
    private static class ApiUriResolve implements ResolvingDataSource.Resolver {

        /** 播放地址本地缓存，避免每次 seek 都要再请求一次服务端 */
        private static final Map<String, CachedUrl> URL_CACHE = new ConcurrentHashMap<>();
        private static final long URL_TTL = 3 * 60 * 1000L;
        private static final int MAX_URL_CACHE = 200;

        private static final class CachedUrl {
            final String url;
            final long expireAt;

            CachedUrl(String url, long expireAt) {
                this.url = url;
                this.expireAt = expireAt;
            }
        }

        private static final OkHttpClient THIRD_PARTY_CLIENT = new OkHttpClient.Builder()
                .connectTimeout(6, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .build();

        /**
         * 第三方音源（ChKSz API）解析。
         *
         * <p>只在设置页开启并配置了 apikey 时生效；目前 ChKSz 只提供网易云 / QQ / 酷狗，
         * 这里仅对网易云歌曲生效。任何异常都返回 null，由调用方给出无音源提示。</p>
         */
        private static String resolveFromThirdParty(String source, String id, String bridge) {
            if (!"wy".equals(source)) {
                return null;
            }
            if (!SPConfig.isThirdPartyEnabled()) {
                return null;
            }
            String key = SPConfig.thirdPartyKey();
            if (key == null || key.trim().isEmpty()) {
                return null;
            }
            try {
                HttpUrl url = HttpUrl.get(SPConfig.THIRD_PARTY_BASE + "/api/163_music")
                        .newBuilder()
                        .addQueryParameter("id", id == null ? "" : id)
                        .addQueryParameter("level", mapThirdPartyLevel(bridge))
                        .addQueryParameter("apikey", key.trim())
                        .build();
                Request request = new Request.Builder()
                        .url(url)
                        .header("User-Agent", "QY-MUSIC")
                        .build();
                try (Response response = THIRD_PARTY_CLIENT.newCall(request).execute()) {
                    if (!response.isSuccessful() || response.body() == null) {
                        return null;
                    }
                    JSONObject json = new JSONObject(response.body().string());
                    if (json.optInt("code", 0) != 200) {
                        return null;
                    }
                    JSONObject data = json.optJSONObject("data");
                    String urlText = data == null ? "" : data.optString("url", "");
                    if (urlText == null || urlText.trim().isEmpty()) {
                        return null;
                    }
                    Log.i(TAG, "第三方音源解析成功: " + id);
                    return urlText.trim();
                }
            } catch (Exception e) {
                Log.w(TAG, "第三方音源解析失败: " + e.getMessage());
                return null;
            }
        }

        /**
         * 请求服务端解析播放地址。
         *
         * <p>服务端没有可用音源时返回 null，由调用方决定是否用第三方音源兜底。</p>
         */
        private static String requestServerPlayUrl(String id, String bridge, String source,
                                                   String sourceId, String qqCookie, String kgCookie,
                                                   String name, String singer, String album,
                                                   String duration, boolean officialEnabled,
                                                   boolean lxEnabled) {
            UrlRequest urlRequest = Net.get(Api.PLAY_INFO);
            urlRequest.setConverter(new JavaSerializationConverter());
            urlRequest.addQuery("id", id, false);
            urlRequest.addQuery("bridge", bridge, false);
            urlRequest.addQuery("source", source, false);
            // 播放音源顺序：官方 → 落雪 → 第三方，关闭的来源让服务端跳过
            if (!officialEnabled) {
                urlRequest.addQuery("native", "0", false);
            }
            if (!lxEnabled) {
                urlRequest.addQuery("lx", "0", false);
            }
            if (sourceId != null && !sourceId.isEmpty()) {
                urlRequest.addQuery("sourceId", sourceId, false);
            }
            if (qqCookie != null && !qqCookie.isEmpty()) {
                urlRequest.addQuery("qqCookie", qqCookie, false);
            }
            if (kgCookie != null && !kgCookie.isEmpty()) {
                urlRequest.addQuery("kgCookie", kgCookie, false);
            }
            if (name != null && !name.isEmpty()) {
                urlRequest.addQuery("name", name, false);
            }
            if (singer != null && !singer.isEmpty()) {
                urlRequest.addQuery("singer", singer, false);
            }
            if (album != null && !album.isEmpty()) {
                urlRequest.addQuery("album", album, false);
            }
            if (duration != null && !duration.isEmpty()) {
                urlRequest.addQuery("duration", duration, false);
            }
            PlayInfo playInfo = urlRequest.execute(PlayInfo.class);

            Log.i(TAG, "playInfo: " + playInfo);

            if (playInfo == null || playInfo.getUrl() == null
                    || playInfo.getUrl().trim().isEmpty()) {
                return null;
            }
            return playInfo.getUrl().trim();
        }

        /** 清除某首歌的解析地址缓存（疑似试听片段需要重新解析时调用） */
        static void clearCachedUrl(String source, String id, String bridge) {
            URL_CACHE.remove(source + "|" + id + "|" + bridge);
        }

        /** 把 App 的音质档位映射成 ChKSz 的 level */
        private static String mapThirdPartyLevel(String bridge) {
            if (bridge == null) {
                return "standard";
            }
            switch (bridge) {
                case "2000kflac":
                    return "lossless";
                case "320kmp3":
                case "192kmp3":
                    return "exhigh";
                default:
                    return "standard";
            }
        }

        @NonNull
        @Override
        public DataSpec resolveDataSpec(DataSpec dataSpec) throws IOException {

            if (!"http".equals(dataSpec.uri.getScheme())) {
                return dataSpec;
            }

            String id = dataSpec.uri.getQueryParameter("id");
            String bridge = dataSpec.uri.getQueryParameter("bridge");
            String source = dataSpec.uri.getQueryParameter("source");
            if (source == null || source.isEmpty()) {
                source = "wy";
            }
            String name = dataSpec.uri.getQueryParameter("name");
            String singer = dataSpec.uri.getQueryParameter("singer");
            String album = dataSpec.uri.getQueryParameter("album");
            String duration = dataSpec.uri.getQueryParameter("duration");
            String sourceId = dataSpec.uri.getQueryParameter("sourceId");
            String qqCookie = dataSpec.uri.getQueryParameter("qqCookie");
            String kgCookie = dataSpec.uri.getQueryParameter("kgCookie");

            String cacheKey = source + "|" + id + "|" + bridge;
            String playUrl = null;
            CachedUrl cached = URL_CACHE.get(cacheKey);
            if (cached != null && cached.expireAt > System.currentTimeMillis()) {
                playUrl = cached.url;
            }

            if (playUrl == null) {
                // 顺序固定：官方 → 落雪 → 第三方 API，三个来源各自可开关
                boolean officialEnabled = SPConfig.isOfficialSourceEnabled();
                boolean lxEnabled = SPConfig.isLxSourceEnabled();
                boolean thirdPartyEnabled = SPConfig.isThirdPartyEnabled();

                if (officialEnabled || lxEnabled) {
                    playUrl = requestServerPlayUrl(id, bridge, source, sourceId, qqCookie,
                            kgCookie, name, singer, album, duration, officialEnabled, lxEnabled);
                }

                // 第三方音源（ChKSz API）仅对网易云歌曲生效
                if (playUrl == null && thirdPartyEnabled) {
                    playUrl = resolveFromThirdParty(source, id, bridge);
                }

                if (playUrl == null) {
                    if (!officialEnabled && !lxEnabled && !thirdPartyEnabled) {
                        throw new IOException("请至少在播放音源设置里启用一个音源");
                    }
                    // 服务端解析不到可用音源时给出明确提示，而不是播放错误的音频
                    throw new IOException("该歌曲暂无可用音源，请换一首试试");
                }

                if (URL_CACHE.size() > MAX_URL_CACHE) {
                    URL_CACHE.clear();
                }
                URL_CACHE.put(cacheKey, new CachedUrl(playUrl, System.currentTimeMillis() + URL_TTL));
            }

            // 关键：必须用 buildUpon() 保留原始 DataSpec 的 position/length，
            // 否则每次 seek 都会从第 0 字节开始读取，表现为「拖动进度条后又从头播放」。
            return dataSpec.buildUpon()
                    .setUri(Uri.parse(playUrl))
                    // 音源 + ID + 音质作为缓存 Key：避免不同音源同 ID 串歌、不同音质复用旧缓存
                    .setKey(PlayUtilsKt.mediaCacheKey(source, id, bridge == null ? "" : bridge))
                    .build();
        }
    }

    public class MusicBroadcastReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) {
                return;
            }
            switch (action) {
                case IntentActions.FAVORITE:
                    MediaItem currentMediaItem = mExoPlayer.getCurrentMediaItem();
                    if (currentMediaItem == null) {
                        return;
                    }
                    long musicId = intent.getLongExtra(IntentExtras.MUSIC_ID, -1L);
                    boolean favorite = intent.getBooleanExtra(IntentExtras.FAVORITE, false);
                    long currentMusicId = Long.parseLong(currentMediaItem.mediaId);
                    Log.i(TAG, "onReceive: favorite: " + favorite + ", musicId: " + musicId);
                    if (currentMusicId == musicId) {
                        isFavorite = favorite;
                        setCustomLayout();
                    }
                    break;
                case IntentActions.SUPER_ISLAND_SETTING:
                    // 歌词设置页切换了超级岛开关，立即接入或退出，不用等下次播放
                    SuperIslandLyrics.refresh(MusicService.this, mExoPlayer);
                    break;
            }
        }
    }
}
