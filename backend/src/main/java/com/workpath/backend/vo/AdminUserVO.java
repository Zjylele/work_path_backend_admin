package com.workpath.backend.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AdminUserVO {

    private Long id;

    private String username;

    private String nickname;

    private String role;

    /** 是否超级管理员：1=是，0=否 */
    private Integer superAdmin;

    /** 该用户轨迹总数（删除确认弹框展示用） */
    private Long trackCount;

    private Integer status;

    private LocalDateTime createTime;

}
