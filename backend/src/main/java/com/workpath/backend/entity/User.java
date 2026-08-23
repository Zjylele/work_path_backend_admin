package com.workpath.backend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("`user`")
public class User {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    @JsonIgnore
    private String password;

    private String nickname;

    private String role;

    /** 是否超级管理员：1=是（可授予/撤销管理员、可删除用户），0=否 */
    private Integer superAdmin;

    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

}
