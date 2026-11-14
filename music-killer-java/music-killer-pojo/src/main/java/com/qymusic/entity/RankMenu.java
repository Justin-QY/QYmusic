package com.qymusic.entity;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.experimental.Accessors;
import lombok.Data;

@Data
@Accessors(chain = true)
public class RankMenu {

    private String name;

    private List<Item> list;

    @Data
    @Accessors(chain = true)
    public static class Item {

        /**
         * 榜单 id。客户端模型是 Long，必须输出数字而不是字符串，
         * 否则 kotlinx.serialization 解码会失败。
         */
        @JsonAlias("sourceid")
        private Long sourceId;

        private String intro;

        private String name;

        private String id;

        private String source;

        private String pic;

        private String pub;

    }
}