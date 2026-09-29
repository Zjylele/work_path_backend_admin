-- ============================================================
-- 阶段三：AI 能力新增表
-- 说明：M4 轨迹智能分析、M5 合规核查与整改工单、M6 制度知识库
-- 兼容性约束：只做 ADD COLUMN 与 CREATE TABLE，不删改既有表定义
-- ============================================================

-- ------------------------------------------------------------
-- 0. 模型接入配置表（W0 新增）
--    用途：语言模型 / 向量化 / 逆地理编码三类的密钥与参数运行时可配
--    安全约束：api_key 入库，接口一律脱敏返回；uk_purpose 保证每用途仅一条生效配置
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `model_provider_config` (
  `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '配置ID',
  `purpose`      VARCHAR(30)  NOT NULL COMMENT '用途：CHAT=语言模型,EMBEDDING=向量化,GEO=逆地理编码',
  `provider_name` VARCHAR(50) NOT NULL COMMENT '服务商标注，如 deepseek/siliconflow/amap',
  `base_url`     VARCHAR(200) NOT NULL COMMENT '接口地址',
  `model_name`   VARCHAR(100) NOT NULL COMMENT '模型标识',
  `api_key`      VARCHAR(500) NOT NULL COMMENT '接口密钥（入库，接口层脱敏）',
  `extra_config` VARCHAR(1000) DEFAULT NULL COMMENT '附加配置 JSON，如 {"dimension":1024}',
  `enabled`      TINYINT      NOT NULL DEFAULT 1 COMMENT '是否启用：1=启用,0=停用',
  `status`       TINYINT      NOT NULL DEFAULT 0 COMMENT '连通性：0=未验证,1=通过,2=失败',
  `last_error`   VARCHAR(500) DEFAULT NULL COMMENT '最近一次连接测试的失败原因',
  `update_time`  DATETIME     DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `create_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_purpose` (`purpose`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI模型接入配置';

-- ------------------------------------------------------------
-- 1. M6 制度知识库
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `policy_document` (
  `doc_id`      BIGINT       NOT NULL AUTO_INCREMENT COMMENT '文档ID',
  `title`       VARCHAR(200) NOT NULL COMMENT '文档标题',
  `version`     VARCHAR(20)  DEFAULT 'v1.0' COMMENT '版本号',
  `category`    VARCHAR(50)  DEFAULT NULL COMMENT '分类',
  `status`      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1=启用,0=停用',
  `uploader_id` BIGINT       DEFAULT NULL COMMENT '上传人ID',
  `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  PRIMARY KEY (`doc_id`),
  KEY `idx_policy_doc_category` (`category`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='制度文档表';

-- 条款向量以定长格式存 TEXT 列，相似度检索在应用层完成（依据见第1章 1.3.4）
CREATE TABLE IF NOT EXISTS `policy_clause` (
  `clause_id`    BIGINT        NOT NULL AUTO_INCREMENT COMMENT '条款ID',
  `doc_id`       BIGINT        NOT NULL COMMENT '所属文档ID',
  `clause_code`  VARCHAR(30)   NOT NULL COMMENT '条款编号，如 3.2.1',
  `clause_index` INT           NOT NULL DEFAULT 0 COMMENT '条款在文档内的顺序号',
  `code_source`  TINYINT       NOT NULL DEFAULT 1 COMMENT '编号来源：1=原文提取,2=按序生成',
  `content`      TEXT          NOT NULL COMMENT '条款原文',
  `vector_data`  TEXT          DEFAULT NULL COMMENT '语义向量（JSON数组）',
  `vector_model` VARCHAR(100)  DEFAULT NULL COMMENT '生成向量的模型（换模型需重算）',
  `status`       TINYINT       NOT NULL DEFAULT 1 COMMENT '状态：1=启用,0=停用',
  `create_time`  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`clause_id`),
  UNIQUE KEY `uk_clause_doc_code` (`doc_id`, `clause_code`),
  KEY `idx_clause_doc` (`doc_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='制度条款表';

-- ------------------------------------------------------------
-- 2. M4 轨迹智能分析
-- ------------------------------------------------------------
-- 只加列，不改既有列语义。
-- MySQL 的 ALTER 不支持 IF NOT EXISTS，这里用存储过程做幂等保护，重复执行不会报错。
DROP PROCEDURE IF EXISTS add_track_analysis_status;
DELIMITER $$
CREATE PROCEDURE add_track_analysis_status()
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = DATABASE()
                   AND TABLE_NAME = 'track'
                   AND COLUMN_NAME = 'analysis_status') THEN
    ALTER TABLE `track` ADD COLUMN `analysis_status` TINYINT NOT NULL DEFAULT 0
      COMMENT '分析状态：0=未分析,1=分析中,2=已分析,3=分析失败';
  END IF;
END$$
DELIMITER ;
CALL add_track_analysis_status();
DROP PROCEDURE IF EXISTS add_track_analysis_status;

CREATE TABLE IF NOT EXISTS `work_event` (
  `event_id`       BIGINT       NOT NULL AUTO_INCREMENT COMMENT '事件ID',
  `track_id`       BIGINT       NOT NULL COMMENT '所属轨迹',
  `user_id`        BIGINT       NOT NULL COMMENT '所属员工',
  `event_type`     VARCHAR(20)  NOT NULL DEFAULT 'STAY' COMMENT '事件类型：MOVE=移动,STAY=停留',
  `start_time`     DATETIME     NOT NULL COMMENT '开始时间',
  `end_time`       DATETIME     NOT NULL COMMENT '结束时间',
  `center_lat`     DOUBLE       DEFAULT NULL COMMENT '停留点中心纬度',
  `center_lng`     DOUBLE       DEFAULT NULL COMMENT '停留点中心经度',
  `dwell_seconds`  INT          DEFAULT NULL COMMENT '停留时长（秒）',
  `poi_name`       VARCHAR(200) DEFAULT NULL COMMENT '地点名称',
  `semantic_label` VARCHAR(50)  DEFAULT NULL COMMENT '语义标注（LLM输出）',
  `confidence`     DOUBLE       DEFAULT NULL COMMENT '标注置信度',
  `ai_reason`      VARCHAR(500) DEFAULT NULL COMMENT 'LLM判定理由',
  `confirm_status` TINYINT      NOT NULL DEFAULT 0 COMMENT '确认状态：0=待确认,1=已确认,2=已驳回',
  `confirm_by`     BIGINT       DEFAULT NULL COMMENT '确认人ID',
  `confirm_time`   DATETIME     DEFAULT NULL COMMENT '确认时间',
  `create_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`event_id`),
  KEY `idx_event_track` (`track_id`),
  KEY `idx_event_user_time` (`user_id`, `start_time`),
  KEY `idx_event_confirm` (`confirm_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='工作事件表（AI输出落库）';

-- ------------------------------------------------------------
-- 3. M5 合规核查与整改工单
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `compliance_rule` (
  `rule_id`          BIGINT       NOT NULL AUTO_INCREMENT,
  `rule_code`        VARCHAR(30)  NOT NULL COMMENT '规则编码，唯一',
  `rule_name`        VARCHAR(100) NOT NULL,
  `rule_type`        VARCHAR(20)  NOT NULL COMMENT '类型：DURATION=时长,FREQUENCY=频次,RANGE=范围,ABNORMAL=异常',
  `threshold`        DOUBLE       DEFAULT NULL COMMENT '阈值，含义依 rule_type 而定',
  `extra_config`     VARCHAR(500) DEFAULT NULL COMMENT '附加配置（JSON）',
  `policy_clause_id` BIGINT       DEFAULT NULL COMMENT '关联的制度条款ID（可追溯性外键）',
  `enabled`          TINYINT      NOT NULL DEFAULT 1,
  `create_time`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`rule_id`),
  UNIQUE KEY `uk_rule_code` (`rule_code`),
  KEY `idx_rule_clause` (`policy_clause_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='合规规则表';

-- result 取值：0=合规,1=疑似违规（条款回查通过）,2=引用无效待复核
CREATE TABLE IF NOT EXISTS `compliance_check` (
  `check_id`          BIGINT       NOT NULL AUTO_INCREMENT,
  `user_id`           BIGINT       NOT NULL COMMENT '被核查员工',
  `work_date`         DATE         NOT NULL COMMENT '核查日期',
  `work_event_id`     BIGINT       DEFAULT NULL COMMENT '触发核查的工作事件',
  `rule_id`           BIGINT       NOT NULL COMMENT '命中的规则',
  `result`            TINYINT      NOT NULL COMMENT '结果：0=合规,1=疑似违规,2=引用无效待复核',
  `evidence`          VARCHAR(500) DEFAULT NULL COMMENT '客观证据：具体数值或关联ID',
  `ai_reason`         VARCHAR(1000) DEFAULT NULL COMMENT 'LLM给出的违规性质说明',
  `cited_clause_code` VARCHAR(30)  DEFAULT NULL COMMENT 'LLM引用的条款编号',
  `clause_verified`   TINYINT      DEFAULT NULL COMMENT '回查结果：1=通过,0=引用无效',
  `create_time`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`check_id`),
  KEY `idx_check_user_date` (`user_id`, `work_date`),
  KEY `idx_check_rule` (`rule_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='合规核查结论表';

-- status 取值：0=待处理,1=处理中,2=已闭环,3=申诉中
CREATE TABLE IF NOT EXISTS `rectification_order` (
  `order_id`      BIGINT       NOT NULL AUTO_INCREMENT,
  `check_id`      BIGINT       NOT NULL COMMENT '来源核查结论',
  `user_id`       BIGINT       NOT NULL COMMENT '责任员工',
  `content`       VARCHAR(500) NOT NULL COMMENT '整改内容',
  `status`        TINYINT      NOT NULL DEFAULT 0 COMMENT '状态：0=待处理,1=处理中,2=已闭环,3=申诉中',
  `assign_by`     BIGINT       DEFAULT NULL COMMENT '下发人',
  `assign_time`   DATETIME     DEFAULT NULL,
  `handle_note`   VARCHAR(500) DEFAULT NULL COMMENT '员工处理说明',
  `handle_time`   DATETIME     DEFAULT NULL,
  `appeal_reason` VARCHAR(500) DEFAULT NULL COMMENT '申诉理由',
  `review_note`   VARCHAR(500) DEFAULT NULL COMMENT '管理员复核意见',
  `close_time`    DATETIME     DEFAULT NULL,
  `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`order_id`),
  UNIQUE KEY `uk_order_check` (`check_id`),
  KEY `idx_order_user` (`user_id`),
  KEY `idx_order_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='整改工单表';

-- ============================================================
-- 阶段三补充：M4 轨迹智能分析（W3 数据模型）
-- 说明：work_event 是 AI 分析的工作事件落库表。init_ai.sql 原有的表
-- 面向 M5/M6，本节按顺序补在文件末尾不代表依赖，仅表示同属阶段三。
-- ============================================================

-- M4 分析依赖 track_point 按 track_id 取全量点跑 DBSCAN。
-- 原表只有 id 主键，这条查询会全表扫——随轨迹点积累（每个轨迹几十到几百个点）
-- 早晚变成瓶颈，而且 analysis 是定时跑的，慢查询会一直复现。
-- 用存储过程包住，保证重复执行本脚本不报错。
DROP PROCEDURE IF EXISTS add_track_point_track_idx;
DELIMITER $$
CREATE PROCEDURE add_track_point_track_idx()
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'track_point'
      AND INDEX_NAME = 'idx_track_point_track'
  ) THEN
    ALTER TABLE `track_point` ADD INDEX `idx_track_point_track` (`track_id`, `record_time`);
  END IF;
END$$
DELIMITER ;
CALL add_track_point_track_idx();
DROP PROCEDURE IF EXISTS add_track_point_track_idx;

-- 同理，定时任务按 analysis_status 捞轨迹。
DROP PROCEDURE IF EXISTS add_track_analysis_status_idx;
DELIMITER $$
CREATE PROCEDURE add_track_analysis_status_idx()
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'track'
      AND INDEX_NAME = 'idx_track_analysis_status'
  ) THEN
    ALTER TABLE `track` ADD INDEX `idx_track_analysis_status` (`analysis_status`, `start_time`);
  END IF;
END$$
DELIMITER ;
CALL add_track_analysis_status_idx();
DROP PROCEDURE IF EXISTS add_track_analysis_status_idx;

-- 工作事件表：AI 分析的最终产出。
--
-- [!] 该表 init_ai.sql 早已建好（字段 semantic_label / confidence / ai_reason /
-- dwell_seconds / center_lat / center_lng），W3 不重复建表，直接沿用。
-- WorkEvent 实体的字段必须与既有表逐字对齐——曾因自行改名（event_desc /
-- duration_seconds）导致 INSERT 报 Unknown column。
--
-- confirm_status=0 是「AI 结论以疑似、待人工复核形式输出」这个定位在数据模型
-- 上的落点——没有它，AI 结论就直接变成管理依据了，与论文强调的「管理辅助工具」
-- 定位直接冲突。
