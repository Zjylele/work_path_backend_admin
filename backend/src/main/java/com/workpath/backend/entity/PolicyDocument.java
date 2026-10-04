package com.workpath.backend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 制度文档。合规核查所依据的条款的容器。
 *
 * <p>version 与 status 的组合实现"文档级版本管理"（第2章 2.3.6）：
 * 同一制度更新后上传同名文档，旧版本 status 置 0 停用但保留，
 * 让当时的核查依据仍然可回溯。检索时按 status=1 过滤。</p>
 */
@Data
@TableName("policy_document")
public class PolicyDocument {

    @TableId(type = IdType.AUTO)
    private Long docId;

    private String title;

    private String version;

    private String category;

    /**
     * 制度原文（预览用）。上传时原样留存；加列前上传的存量文档为 null，
     * 预览接口会退回按条款拼合并标注来源。
     */
    private String content;

    /** 状态：1=启用，0=停用 */
    private Integer status;

    private Long uploaderId;

    private LocalDateTime createTime;

    /**
     * 非持久化：该文档的启用条款数量，列表页展示用。
     * 不加 exist=false 会被 MyBatis-Plus 当成列去 select，导致 SQL 报未知列。
     */
    @TableField(exist = false)
    private Integer clauseCount;

    /**
     * 非持久化：上传人用户名，列表展示用。
     */
    @TableField(exist = false)
    private String uploaderName;
}
