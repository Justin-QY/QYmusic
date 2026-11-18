package com.qymusic.app.service;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.core.content.ContextCompat;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
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

    @Override
    public void onMediaItemTransition(@Nullable MediaItem mediaItem, int reason) {
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
                .setCache(QYMusicApplication.simpleCache)
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
    public void onDestroy() {
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
         * 这里仅对网易云歌曲生效。任何异常都返回 null，交给后面的落雪脚本兜底。</p>
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
            // QQ 音乐匿名只给空 vkey，播放必须带登录 Cookie，提前给出明确提示
            if ("qq".equals(source) && (qqCookie == null || qqCookie.isEmpty())) {
                throw new IOException("QQ 音乐播放需要先登录，请到「云端」登录 QQ 音乐");
            }
            String playUrl = null;
            CachedUrl cached = URL_CACHE.get(cacheKey);
            if (cached != null && cached.expireAt > System.currentTimeMillis()) {
                playUrl = cached.url;
            }

            if (playUrl == null) {
                // 第三方音源（ChKSz API）优先：仅对网易云歌曲生效，失败自动回落
                playUrl = resolveFromThirdParty(source, id, bridge);

                if (playUrl == null) {
                    UrlRequest urlRequest = Net.get(Api.PLAY_INFO);
                    urlRequest.setConverter(new JavaSerializationConverter());
                    urlRequest.addQuery("id", id, false);
                    urlRequest.addQuery("bridge", bridge, false);
                    urlRequest.addQuery("source", source, false);
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

                    if (playInfo == null || playInfo.getUrl() == null || playInfo.getUrl().trim().isEmpty()) {
                        // 服务端解析不到可用音源时给出明确提示，而不是播放错误的音频
                        throw new IOException("该歌曲暂无可用音源，请换一首试试");
                    }
                    playUrl = playInfo.getUrl().trim();
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
                    .setKey(source + "-" + id + "-v3") // 音源+ID 作为缓存 Key，避免不同音源同 ID 串歌
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
