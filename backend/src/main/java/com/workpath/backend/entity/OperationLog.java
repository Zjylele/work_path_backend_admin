package com.workpath.backend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 操作日志（审计留痕，只追加不删除）
 * 记录管理端管理类操作与用户登录/登出，字段均为快照（删除用户后日志仍保留）
 */
@Data
@TableName("operation_log")
public class OperationLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 操作人用户 id（登录失败时为 null） */
    private Long userId;

    /** 操作人用户名（快照） */
    private String username;

    /** 动作码：LOGIN_SUCCESS / LOGIN_FAIL / LOGOUT / USER_DISABLE / USER_ENABLE /
     *  USER_DELETE / PASSWORD_RESET / ROLE_GRANT / ROLE_REVOKE / TRACK_DELETE */
    private String action;

    /** 目标类型：USER / TRACK */
    private String targetType;

    /** 目标 id */
    private Long targetId;

    /** 目标名称快照（如用户名 / 轨迹 id） */
    private String targetName;

    /** 补充描述 */
    private String detail;

    /** 结果：1=成功，0=失败 */
    private Integer result;

    private LocalDateTime createTime;

}
