package com.workpath.backend.controller;

import com.workpath.backend.common.Result;
import com.workpath.backend.common.ResultCode;
import com.workpath.backend.dto.LoginDTO;
import com.workpath.backend.dto.RegisterDTO;
import com.workpath.backend.dto.UserProfileDTO;
import com.workpath.backend.service.UserService;
import com.workpath.backend.vo.LoginVO;
import com.workpath.backend.vo.UserVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "用户/认证", description = "注册、登录、当前用户信息、退出登录")
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @Operation(summary = "注册")
    @PostMapping("/register")
    public Result<Void> register(@Valid @RequestBody RegisterDTO dto) {
        userService.register(dto);
        return Result.success();
    }

    @Operation(summary = "登录")
    @PostMapping("/login")
    public Result<LoginVO> login(@Valid @RequestBody LoginDTO dto) {
        return Result.success(userService.login(dto));
    }

    @Operation(summary = "获取当前登录用户信息")
    @GetMapping("/info")
    public Result<UserVO> info() {
        return Result.success(userService.getInfo());
    }

    @Operation(summary = "修改当前登录用户昵称")
    @PutMapping("/profile")
    public Result<UserVO> updateProfile(@Valid @RequestBody UserProfileDTO dto) {
        return Result.success(userService.updateProfile(dto.getNickname()));
    }

    @Operation(summary = "退出登录")
    @PostMapping("/logout")
    public Result<Void> logout() {
        return Result.success();
    }

}
