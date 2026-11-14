package com.qymusic.service;

import com.qymusic.dto.UserAuthDTO;
import com.qymusic.entity.User;

public interface UserService {

    User getByUsername(String username);

    void add(UserAuthDTO userAuthDTO);

    User getByUsernameAndPwd(UserAuthDTO userAuthDTO);

    User getById(Long userId);
}
