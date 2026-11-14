package com.qymusic.mapper;

import org.apache.ibatis.annotations.Mapper;
import com.qymusic.entity.Image;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
* @description 针对表【image(图片集合)】的数据库操作Mapper
* @createDate 2025-10-31 14:46:36
* @Entity com.qymusic.entity.Image
*/
@Mapper
public interface ImageMapper extends BaseMapper<Image> {

}




