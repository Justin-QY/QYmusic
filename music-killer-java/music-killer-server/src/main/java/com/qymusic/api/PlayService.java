package com.qymusic.api;

import com.github.lianjiatech.retrofit.spring.boot.core.RetrofitClient;
import retrofit2.http.GET;
import retrofit2.http.QueryMap;
import com.qymusic.entity.PlayInfo;
import com.qymusic.result.Result;

import java.util.Map;

@RetrofitClient(baseUrl = "https://mobi.kuwo.cn/")
public interface PlayService {

    @GET("mobi.s")
    Result<PlayInfo> getPlayInfo(@QueryMap Map<String, String> map);

}
