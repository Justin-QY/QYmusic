package com.qymusic.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.lianjiatech.retrofit.spring.boot.core.RetrofitClient;
import retrofit2.http.Field;
import retrofit2.http.FieldMap;
import retrofit2.http.FormUrlEncoded;
import retrofit2.http.GET;
import retrofit2.http.Header;
import retrofit2.http.Headers;
import retrofit2.http.POST;
import retrofit2.http.Path;
import retrofit2.http.Query;
import retrofit2.http.Url;

import java.util.Map;

/**
 * 网易云音乐公开接口。
 *
 * <p>只使用网页版 /api 接口，不需要登录、不需要 weapi/eapi 加密，
 * 用于「网易云版本」的目录（搜索、排行榜、歌手、歌单、歌词）。</p>
 *
 * <p>播放地址不走这里，仍然交给服务端内嵌的洛雪音源脚本解析。</p>
 */
@RetrofitClient(baseUrl = "https://music.163.com/api/")
public interface NetEaseService {

    String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    String REFERER_HEADER = "Referer: https://music.163.com/";
    String UA_HEADER = "User-Agent: " + UA;

    /**
     * 综合搜索。
     *
     * <p>说明：旧版 /api/search/get 从海外服务器匿名调用时中文关键词会命中脏索引
     * （返回无关结果），所以改用 /api/cloudsearch/pc（同样匿名可用，且带 bitrate 信息）。
     * 参数用 FieldMap + encoded=true，避免 Retrofit 对已编码值二次编码。</p>
     *
     * @param type 1=歌曲 10=专辑 100=歌手 1000=歌单
     */
    @FormUrlEncoded
    @POST("cloudsearch/pc")
    @Headers({REFERER_HEADER, UA_HEADER})
    JsonNode search(@Field(value = "s", encoded = true) String keyword,
                    @Field("type") int type,
                    @Field("offset") int offset,
                    @Field("limit") int limit);

    /**
     * 搜索联想词
     */
    @FormUrlEncoded
    @POST("search/suggest/web")
    @Headers({REFERER_HEADER, UA_HEADER})
    JsonNode suggest(@Field(value = "s", encoded = true) String keyword);

    /**
     * 通用表单请求（保留给特殊场景，例如带 csrf_token 的调用）
     */
    @FormUrlEncoded
    @POST
    @Headers({REFERER_HEADER, UA_HEADER})
    JsonNode postForm(@Url String path, @FieldMap Map<String, String> fields);

    /**
     * 所有排行榜（飙升榜、新歌榜、原创榜、热歌榜……）
     */
    @GET("toplist")
    @Headers({REFERER_HEADER, UA_HEADER})
    JsonNode toplist();

    /**
     * 歌单详情（只取元信息和 trackIds，避免一次拉几百首歌）
     */
    @GET("v6/playlist/detail")
    @Headers({REFERER_HEADER, UA_HEADER})
    JsonNode playlistDetail(@Query("id") long id,
                            @Query("n") int trackLimit,
                            @Query("s") int padding);

    /**
     * 歌曲详情（可批量，ids 形如 [186016,186017]）
     */
    @GET("song/detail/")
    @Headers({REFERER_HEADER, UA_HEADER})
    JsonNode songDetail(@Query("ids") String ids);

    /**
     * 歌手列表
     *
     * @param cat 1001 华语男歌手 / 1002 华语女歌手 / 1003 华语组合 ……
     */
    @GET("artist/list")
    @Headers({REFERER_HEADER, UA_HEADER})
    JsonNode artistList(@Query("cat") int cat,
                        @Query("offset") int offset,
                        @Query("limit") int limit);

    /**
     * 歌手详情，hotSongs 为热门 50 首
     */
    @GET("v1/artist/{id}")
    @Headers({REFERER_HEADER, UA_HEADER})
    JsonNode artistDetail(@Path("id") long id);

    /**
     * 专辑详情
     */
    @GET("v1/album/{id}")
    @Headers({REFERER_HEADER, UA_HEADER})
    JsonNode albumDetail(@Path("id") long id);

    /**
     * 推荐歌单
     */
    @GET("personalized/playlist")
    @Headers({REFERER_HEADER, UA_HEADER})
    JsonNode personalizedPlaylist(@Query("limit") int limit);

    /**
     * 分类歌单
     *
     * @param order hot=最热 new=最新
     */
    @GET("playlist/list")
    @Headers({REFERER_HEADER, UA_HEADER})
    JsonNode playlistList(@Query("cat") String cat,
                          @Query("order") String order,
                          @Query("offset") int offset,
                          @Query("limit") int limit);

    /**
     * 精品歌单
     */
    @GET("playlist/highquality/list")
    @Headers({REFERER_HEADER, UA_HEADER})
    JsonNode highQualityPlaylist(@Query("cat") String cat,
                                 @Query("limit") int limit,
                                 @Query("lasttime") long lasttime);

    /**
     * 歌词（lrc.lyric）
     */
    @GET("song/lyric")
    @Headers({REFERER_HEADER, UA_HEADER})
    JsonNode lyric(@Query("id") long id,
                   @Query("lv") int lv,
                   @Query("kv") int kv,
                   @Query("tv") int tv);

    // ------------------------------------------------------------------ 登录用户（Cookie）

    /**
     * 当前登录账号信息（需要 Cookie 中的 MUSIC_U）
     */
    @GET("nuser/account/get")
    @Headers({REFERER_HEADER, UA_HEADER})
    JsonNode account(@Header("Cookie") String cookie);

    /**
     * 用户收藏的歌单
     */
    @GET("user/playlist")
    @Headers({REFERER_HEADER, UA_HEADER})
    JsonNode userPlaylists(@Header("Cookie") String cookie,
                           @Query("uid") long uid,
                           @Query("limit") int limit,
                           @Query("offset") int offset);

    /**
     * 用户收藏的歌手
     */
    @GET("artist/sublist")
    @Headers({REFERER_HEADER, UA_HEADER})
    JsonNode artistSublist(@Header("Cookie") String cookie);

    /**
     * 我喜欢的音乐 ID 列表
     */
    @FormUrlEncoded
    @POST("song/like/get")
    @Headers({REFERER_HEADER, UA_HEADER})
    JsonNode likedIds(@Header("Cookie") String cookie, @Field("userid") long uid);
}
