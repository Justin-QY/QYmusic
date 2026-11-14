package com.qymusic.mapper;

import org.apache.ibatis.annotations.Mapper;
import com.qymusic.dto.UserAuthDTO;
import com.qymusic.dto.UserDTO;
import com.qymusic.entity.User;

@Mapper
public interface UserMapper {

    void add(UserDTO userDTO);

    User getByUsername(String username);

    User getByUsernameAndPwd(UserAuthDTO userAuthDTO);

    User getById(Long userId);
}
