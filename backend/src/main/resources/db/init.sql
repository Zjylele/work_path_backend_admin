-- ============================================================
-- 阶段二：工作轨迹系统 建库建表脚本（MySQL 8）
-- 库名：work_path
-- 使用方式：在 DataGrip 中直接打开本文件并执行即可
-- ============================================================

CREATE DATABASE IF NOT EXISTS work_path
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

USE work_path;

-- ------------------------------------------------------------
-- 用户表
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `user` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '用户ID',
  `username`    VARCHAR(50)  NOT NULL COMMENT '用户名',
  `password`    VARCHAR(100) NOT NULL COMMENT 'BCrypt 加密后的密码',
  `nickname`    VARCHAR(50)  DEFAULT NULL COMMENT '昵称',
  `role`        VARCHAR(20)  NOT NULL DEFAULT 'USER' COMMENT '角色：USER=手机端,ADMIN=管理端',
  `super_admin` TINYINT      NOT NULL DEFAULT 0 COMMENT '是否超级管理员：1=是（可授予/撤销管理员、可删除用户）',
  `status`      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1=正常,0=禁用',
  `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '用户表';

-- ------------------------------------------------------------
-- 轨迹主表（与移动端 sqflite 的 track 结构对应，userId 使用 BIGINT）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `track` (
  `id`               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '轨迹ID',
  `user_id`          BIGINT       NOT NULL COMMENT '所属用户ID',
  `client_track_id`  VARCHAR(64)  DEFAULT NULL COMMENT '移动端本地轨迹UUID（上传幂等键）',
  `start_time`       DATETIME     NOT NULL COMMENT '开始时间',
  `end_time`         DATETIME     DEFAULT NULL COMMENT '结束时间',
  `status`           TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1=进行中,2=已完成',
  `distance`         DOUBLE       DEFAULT NULL COMMENT '距离（米）',
  `create_time`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_client_track` (`user_id`, `client_track_id`),
  KEY `idx_track_user` (`user_id`),
  KEY `idx_track_start_time` (`start_time`),
  CONSTRAINT `fk_track_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '轨迹表';

-- ------------------------------------------------------------
-- 轨迹点表（与移动端 sqflite 的 track_point 结构对应，trackId 使用 BIGINT）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `track_point` (
  `id`          BIGINT   NOT NULL AUTO_INCREMENT COMMENT '点ID',
  `track_id`    BIGINT   NOT NULL COMMENT '所属轨迹ID',
  `latitude`    DOUBLE   NOT NULL COMMENT '纬度',
  `longitude`   DOUBLE   NOT NULL COMMENT '经度',
  `record_time` DATETIME NOT NULL COMMENT '记录时间',
  PRIMARY KEY (`id`),
  KEY `idx_track_point_track` (`track_id`, `record_time`),
  CONSTRAINT `fk_track_point_track` FOREIGN KEY (`track_id`) REFERENCES `track` (`id`) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '轨迹点表';

-- ------------------------------------------------------------
-- 操作日志表（审计留痕，只追加不删除；字段为快照，删除用户后日志仍保留）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `operation_log` (
  `id`          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '日志ID',
  `user_id`     BIGINT      DEFAULT NULL COMMENT '操作人用户ID（登录失败时为 NULL）',
  `username`    VARCHAR(50) DEFAULT NULL COMMENT '操作人用户名（快照）',
  `action`      VARCHAR(30) NOT NULL COMMENT '动作码：LOGIN_SUCCESS/LOGIN_FAIL/LOGOUT/USER_DISABLE/USER_ENABLE/USER_DELETE/PASSWORD_RESET/ROLE_GRANT/ROLE_REVOKE/TRACK_DELETE',
  -- 目标类型：USER/TRACK 等短码，也可放表名（M6 的 model_provider_config 有 21 字符，
  -- 早期用 VARCHAR(20) 会越界且被 record() 的 catch 吞掉，导致审计日志静默丢失）
  `target_type` VARCHAR(30) DEFAULT NULL COMMENT '目标类型：USER/TRACK/MODEL_CONFIG',
  `target_id`   BIGINT      DEFAULT NULL COMMENT '目标ID',
  `target_name` VARCHAR(100) DEFAULT NULL COMMENT '目标名称快照',
  `detail`      VARCHAR(255) DEFAULT NULL COMMENT '补充描述',
  `result`      TINYINT     NOT NULL DEFAULT 1 COMMENT '结果：1=成功,0=失败',
  `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
  PRIMARY KEY (`id`),
  KEY `idx_log_create_time` (`create_time`),
  KEY `idx_log_username` (`username`),
  KEY `idx_log_action` (`action`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '操作日志表';

-- ------------------------------------------------------------
-- 初始化内置账号
-- admin  / 123456  -> ADMIN + 超级管理员  （管理端，唯一超管）
-- zhangsan / 123456 -> USER   （手机端测试账号）
-- 两个密码均为 BCrypt 加密后的 "123456"
-- ------------------------------------------------------------
INSERT INTO `user` (`username`, `password`, `nickname`, `role`, `super_admin`, `status`)
VALUES
  ('admin', '$2a$10$y4mdzUDZvXe8xD6l7WFAuOMXGujNYHqEduQJaBifHpLjjuJfMLUJi', '管理员', 'ADMIN', 1, 1),
  ('zhangsan', '$2a$10$y4mdzUDZvXe8xD6l7WFAuOMXGujNYHqEduQJaBifHpLjjuJfMLUJi', '张三', 'USER', 0, 1);
