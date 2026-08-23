package com.workpath.backend.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class UserRoleDTO {

    /** 目标角色：ADMIN=管理员 / USER=普通用户 */
    @NotBlank(message = "角色不能为空")
    private String role;

}
