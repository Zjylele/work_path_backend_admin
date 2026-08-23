package com.workpath.backend.vo;

import lombok.Data;

@Data
public class UserVO {

    private Long id;

    private String username;

    private String nickname;

    private String role;

    /** 是否超级管理员：1=是，0=否 */
    private Integer superAdmin;

}
