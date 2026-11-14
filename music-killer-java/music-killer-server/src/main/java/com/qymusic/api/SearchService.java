package com.qymusic.api;

import com.github.lianjiatech.retrofit.spring.boot.core.RetrofitClient;
import com.github.lianjiatech.retrofit.spring.boot.interceptor.Intercept;
import retrofit2.http.*;
import com.qymusic.interceptor.SignInterceptor;
import com.qymusic.result.Result;
import com.qymusic.vo.SearchAlbumResultVO;
import com.qymusic.vo.SearchArtistResultVO;
import com.qymusic.vo.SearchMusicResultVO;
import com.qymusic.vo.SearchPlaylistResultVO;

import java.util.Map;

/**
 * 搜索服务
 */
@RetrofitClient(baseUrl = "https://www.kuwo.cn/")
@Intercept(handler = SignInterceptor.class)
public interface SearchService {

    /**
     * 获取搜索建议列表
     *
     * @param key 搜索关键字
     * @return 搜索关键字列表
     */
    @GET("openapi/v1/www/search/searchKey")
    Result<String[]> getSearchKey(@Query("key") String key);


    /**
     * 根据关键字搜索歌曲
     *
     * @param map 搜索参数
     * @return 歌曲列表
     */
    @GET("search/searchMusicBykeyWord")
    SearchMusicResultVO searchMusicByKeyWord(@QueryMap Map<String, Object> map);

    /**
     * 根据关键字搜索专辑
     * @param keyword 关键字
     * @param pageNo 页码
     * @param pageSize 页大小
     * @return 专辑列表
     */
    @GET("api/www/search/searchAlbumBykeyWord")
    @Headers("Referer: https://www.kuwo.cn/search/album")
    Result<SearchAlbumResultVO> searchAlbumByKeyword(
            @Query("key") String keyword,
            @Query("pn") Integer pageNo,
            @Query("rn") Integer pageSize);

    /**
     * 根据关键字搜索歌单
     * @param keyword 关键字
     * @param pageNo 页码
     * @param pageSize 页大小
     * @return 歌单列表
     */
    @GET("api/www/search/searchPlayListBykeyWord")
    @Headers("Referer: https://www.kuwo.cn/search/playlist")
    Result<SearchPlaylistResultVO> searchPlaylistByKeyword(
            @Query("key") String keyword,
            @Query("pn") Integer pageNo,
            @Query("rn") Integer pageSize);

    /**
     * 根据关键字搜索歌手
     * @param keyword 关键字
     * @param pageNo 页码
     * @param pageSize 页大小
     * @return 歌手列表
     */
    @GET("api/www/search/searchArtistBykeyWord")
    @Headers("Referer: https://www.kuwo.cn/search/singers")
    Result<SearchArtistResultVO> searchArtistByKeyword(
            @Query("key") String keyword,
            @Query("pn") Integer pageNo,
            @Query("rn") Integer pageSize);
}
