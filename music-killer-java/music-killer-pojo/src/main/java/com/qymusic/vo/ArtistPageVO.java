package com.qymusic.vo;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Data;
import com.qymusic.entity.Artist;

import java.util.List;

@Data
public class ArtistPageVO {

    private Long total;

    @JsonAlias("artistList")
    private List<Artist> data;

}
