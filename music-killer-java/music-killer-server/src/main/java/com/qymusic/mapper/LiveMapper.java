package com.qymusic.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import com.qymusic.entity.LiveItem;

import java.util.List;

@Mapper
public interface LiveMapper {

    @Select("select * from live_item")
    List<LiveItem> getItems();

}
