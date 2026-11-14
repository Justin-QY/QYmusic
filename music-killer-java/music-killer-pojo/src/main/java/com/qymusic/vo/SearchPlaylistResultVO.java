package com.qymusic.vo;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Data;
import com.qymusic.entity.PlayList;

import java.util.List;

@Data
public class SearchPlaylistResultVO {

    private Integer total;

    @JsonAlias("list")
    private List<PlayList> data;

}
