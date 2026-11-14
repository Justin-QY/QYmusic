package com.qymusic.api;

import com.github.lianjiatech.retrofit.spring.boot.core.RetrofitClient;
import com.github.lianjiatech.retrofit.spring.boot.interceptor.Intercept;
import jakarta.validation.constraints.NotNull;
import retrofit2.http.GET;
import retrofit2.http.Query;
import com.qymusic.data.PageResponse;
import com.qymusic.entity.Artist;
import com.qymusic.entity.Music;
import com.qymusic.interceptor.SignInterceptor;
import com.qymusic.result.Result;
import com.qymusic.vo.ArtistPageVO;

@RetrofitClient(baseUrl = "https://www.kuwo.cn/api/www/")
@Intercept(handler = SignInterceptor.class)
public interface ArtistService {

    /**
     * 获取歌手列表
     *
     * @param category    分类
     * @param firstLetter 首字母
     * @param pageNo      页码
     * @return 歌手信息
     */
    @GET("artist/artistInfo")
    Result<ArtistPageVO> getArtistList(
            @Query("category") Integer category,
            @Query("prefix") String firstLetter,
            @Query("pn") Integer pageNo,
            @Query("rn") Integer pageSize
    );

    @GET("artist/artist")
    Result<Artist> getArtistInfo(@Query("artistid") Long artistId);

    @GET("artist/artistMusic")
    Result<PageResponse<Music>> getArtistMusicList(
            @Query("artistid") Long artistId,
            @Query("pn") Integer pageNo,
            @Query("rn") Integer pageSize);
}
