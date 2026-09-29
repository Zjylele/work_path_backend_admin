package com.workpath.backend.service;

import com.workpath.backend.common.BusinessException;
import com.workpath.backend.common.ResultCode;
import com.workpath.backend.dto.LoginDTO;
import com.workpath.backend.dto.RegisterDTO;
import com.workpath.backend.entity.User;
import com.workpath.backend.interceptor.UserContext;
import com.workpath.backend.mapper.UserMapper;
import com.workpath.backend.util.JwtUtil;
import com.workpath.backend.vo.LoginVO;
import com.workpath.backend.vo.UserVO;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserMapper userMapper;
    private final JwtUtil jwtUtil;

    public void register(RegisterDTO dto) {
        Long count = userMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<User>()
                        .eq(User::getUsername, dto.getUsername()));
        if (count != null && count > 0) {
            throw new BusinessException(ResultCode.CONFLICT.getCode(), "用户名已存在");
        }

        User user = new User();
        user.setUsername(dto.getUsername());
        user.setPassword(BCrypt.hashpw(dto.getPassword(), BCrypt.gensalt()));
        user.setNickname(StringUtils.hasText(dto.getNickname()) ? dto.getNickname() : dto.getUsername());
        user.setRole("USER");
        user.setStatus(1);
        LocalDateTime now = LocalDateTime.now();
        user.setCreateTime(now);
        user.setUpdateTime(now);
        userMapper.insert(user);
    }

    public LoginVO login(LoginDTO dto) {
        User user = userMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<User>()
                        .eq(User::getUsername, dto.getUsername()));
        if (user == null || !BCrypt.checkpw(dto.getPassword(), user.getPassword())) {
            throw new BusinessException(ResultCode.UNAUTHORIZED.getCode(), "用户名或密码错误");
        }
        if (user.getStatus() != null && user.getStatus() == 0) {
            throw new BusinessException(ResultCode.UNAUTHORIZED.getCode(), "账号已被禁用");
        }

        String token = jwtUtil.generateToken(user.getId(), user.getUsername(), user.getRole());
        LoginVO vo = new LoginVO();
        vo.setToken(token);
        vo.setUser(toUserVO(user));
        return vo;
    }

    public UserVO getInfo() {
        return toUserVO(UserContext.get());
    }

    /** 修改当前登录用户昵称 */
    public UserVO updateProfile(String nickname) {
        User user = UserContext.get();
        user.setNickname(nickname);
        user.setUpdateTime(LocalDateTime.now());
        userMapper.updateById(user);
        return toUserVO(user);
    }

    private UserVO toUserVO(User user) {
        UserVO vo = new UserVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setNickname(user.getNickname());
        vo.setRole(user.getRole());
        vo.setSuperAdmin(user.getSuperAdmin());
        return vo;
    }

}
