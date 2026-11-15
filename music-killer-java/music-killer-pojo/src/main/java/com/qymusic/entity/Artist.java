package com.qymusic.entity;

import lombok.experimental.Accessors;
import lombok.Data;

@Data
@Accessors(chain = true)
public class Artist {

    private Integer artistFans;

    private Integer albumNum;

    private Integer mvNum;

    private String pic;

    private Integer musicNum;

    private String pic120;

    private Integer isStar;

    private Long contentType;

    private String aartist;

    private String name;

    private String pic70;

    private Integer id;

    private String pic300;

    /** 平台原始 ID（QQ 音乐的 singerMID 等字符串 ID） */
    private String sourceId;

}