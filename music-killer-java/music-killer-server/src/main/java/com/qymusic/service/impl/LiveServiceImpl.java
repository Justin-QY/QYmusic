package com.qymusic.service.impl;

import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import com.qymusic.entity.LiveItem;
import com.qymusic.mapper.LiveMapper;
import com.qymusic.service.LiveService;

import java.util.List;

@Service
public class LiveServiceImpl implements LiveService {

    @Resource
    private LiveMapper liveMapper;

    @Override
    public List<LiveItem> getItems() {
        return liveMapper.getItems();
    }
}
