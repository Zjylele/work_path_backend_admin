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
  `content`     MEDIUMTEXT   DEFAULT NULL COMMENT '制度原文（预览用；加列前的存量文档为 NULL，预览时按条款拼合）',
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

-- ------------------------------------------------------------
-- 6. M6 收尾：operation_log.target_type 扩列
--
-- 根因：ModelProviderService 把 target_type 写成 "model_provider_config"（21 字符），
-- 而 init.sql 里该列是 VARCHAR(20)，越界一字符。
-- 更糟的是 OperationLogService.record() 外层 catch 吞掉 SQLException，
-- 于是这段审计日志静默丢失——页面提示成功、配置也落库，只有 operation_log 查不到记录。
--
-- 选取扩列而不是把字符串改短：target_type 作为通用审计字段本来就可能写表名，
-- VARCHAR(30) 能容纳本库现有最长表名；改短字符串只治这一次，下次照旧越界。
-- 已有数据不会有长度 >30 的 target_type（列宽 20 摆在那），所以扩列无截断风险。
-- ------------------------------------------------------------
DROP PROCEDURE IF EXISTS widen_operation_log_target_type;
DELIMITER $$
CREATE PROCEDURE widen_operation_log_target_type()
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'operation_log'
               AND COLUMN_NAME = 'target_type'
               AND CHARACTER_MAXIMUM_LENGTH < 30) THEN
    ALTER TABLE `operation_log`
      MODIFY COLUMN `target_type` VARCHAR(30) DEFAULT NULL
      COMMENT '目标类型：USER/TRACK/MODEL_CONFIG';
  END IF;
END$$
DELIMITER ;
CALL widen_operation_log_target_type();
DROP PROCEDURE IF EXISTS widen_operation_log_target_type;

-- ------------------------------------------------------------
-- 7. M5 配套：model_provider_config 支持一用途多模型
--
-- 原表 uk_purpose 唯一键把「每个用途只有一条配置」焊死，无法做到
-- 「添加多个模型、按用途切换当前使用」。改造分三步，全部幂等：
--   1) 唯一键降级为普通索引（允许同用途多行）；
--   2) 加 selected 标记列（1=该用途当前生效的配置）；
--   3) 存量行每用途选 id 最小的一行置为当前使用。
-- 唯一性由 Service 层保证：切换在事务内先清后置；requireConfig 发现
-- 某用途没有 selected 行时自愈（自动选中 id 最小的启用行）。
-- ------------------------------------------------------------
DROP PROCEDURE IF EXISTS multipurpose_model_provider;
DELIMITER $$
CREATE PROCEDURE multipurpose_model_provider()
BEGIN
  -- 1) 唯一键 uk_purpose 若存在则降级为普通索引
  IF EXISTS (
    SELECT 1 FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'model_provider_config'
      AND INDEX_NAME = 'uk_purpose'
  ) THEN
    ALTER TABLE `model_provider_config` DROP INDEX `uk_purpose`;
    ALTER TABLE `model_provider_config` ADD INDEX `idx_purpose` (`purpose`);
  END IF;

  -- 2) selected 列
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'model_provider_config'
      AND COLUMN_NAME = 'selected'
  ) THEN
    ALTER TABLE `model_provider_config`
      ADD COLUMN `selected` TINYINT NOT NULL DEFAULT 0
      COMMENT '是否当前使用：1=该用途当前生效的配置' AFTER `enabled`;
  END IF;

  -- 3) 存量数据自愈：每用途尚无 selected 行时，选中 id 最小的一行
  UPDATE `model_provider_config` m
    LEFT JOIN `model_provider_config` s
      ON s.purpose = m.purpose AND s.selected = 1
    SET m.selected = 1
    WHERE s.id IS NULL
      AND m.id = (SELECT MIN(id) FROM
        (SELECT id, purpose FROM model_provider_config) x
        WHERE x.purpose = m.purpose);
END$$
DELIMITER ;
CALL multipurpose_model_provider();
DROP PROCEDURE IF EXISTS multipurpose_model_provider;

-- ------------------------------------------------------------
-- 8. M6 增强：policy_document 加 content 列（制度文档预览）
--
-- 根因：上传时原文只用于切分条款，随即丢弃，文档表没有任何地方能取回
-- 原始文本。「预览制度文档」功能上线后，新文档必须能按原文预览，
-- 否则只能拿切分后的条款拼合——编号被重写、AUTO 编号是系统生成的，
-- 不是原文档的形态。
--
-- 兼容性：只做 ADD COLUMN，可空、无默认值，旧代码不 SELECT 该列
-- （MyBatis-Plus 实体未加字段时不会带上它），加列对既有功能透明。
-- 存量文档 content 为 NULL，预览接口按条款拼合并标注来源。
-- ------------------------------------------------------------
DROP PROCEDURE IF EXISTS add_policy_document_content;
DELIMITER $
CREATE PROCEDURE add_policy_document_content()
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = DATABASE()
                   AND TABLE_NAME = 'policy_document'
                   AND COLUMN_NAME = 'content') THEN
    ALTER TABLE `policy_document`
      ADD COLUMN `content` MEDIUMTEXT DEFAULT NULL
      COMMENT '制度原文（预览用；加列前的存量文档为 NULL，预览时按条款拼合）'
      AFTER `category`;
  END IF;
END$
DELIMITER ;
CALL add_policy_document_content();
DROP PROCEDURE IF EXISTS add_policy_document_content;

-- ------------------------------------------------------------
-- 9. 轨迹名称：track 加 name 列
--
-- 需求：移动端开始记录轨迹时填写名称，结束时随轨迹上传入库；
-- 管理端仪表盘/列表/详情展示名称。既有轨迹 name 为 NULL，
-- 展示层统一显示「未命名」——不填默认值，避免"未命名"这个
-- 展示文案被写死进数据（将来文案想改都改不动）。
--
-- 兼容性：只做 ADD COLUMN，可空。旧版移动端上传不带 name，
-- 接口按 null 接受，不受影响。
-- ------------------------------------------------------------
DROP PROCEDURE IF EXISTS add_track_name;
DELIMITER $
CREATE PROCEDURE add_track_name()
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = DATABASE()
                   AND TABLE_NAME = 'track'
                   AND COLUMN_NAME = 'name') THEN
    ALTER TABLE `track`
      ADD COLUMN `name` VARCHAR(100) DEFAULT NULL
      COMMENT '轨迹名称（旧数据为 NULL，展示为「未命名」）'
      AFTER `client_track_id`;
  END IF;
END$
DELIMITER ;
CALL add_track_name();
DROP PROCEDURE IF EXISTS add_track_name;

-- ------------------------------------------------------------
-- 7. W4 收尾：work_event.analysis_trace —— 判定轨迹
-- ------------------------------------------------------------
-- 把「这次判定依据了什么」落库，供人工复核时查看：参数快照、输入统计、
-- POI、LLM 原始输出、解析结果。没有它，管理员只能看到一个结论
-- （「客户拜访 60%」），看不到它是怎么来的——而「模型说了废话被解析成
-- 其他」与「模型明确判断为其他」在库里长得一模一样。
--
-- 用 TEXT 而非 JSON 类型：内容是给人看的纯文本行，不参与计算；
-- 模型原文可能含引号与换行，JSON 还得额外转义。

DROP PROCEDURE IF EXISTS add_work_event_analysis_trace;
DELIMITER $$
CREATE PROCEDURE add_work_event_analysis_trace()
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'work_event'
      AND COLUMN_NAME = 'analysis_trace'
  ) THEN
    ALTER TABLE `work_event` ADD COLUMN `analysis_trace` TEXT DEFAULT NULL
      COMMENT '判定轨迹：参数快照/输入统计/POI/LLM原始输出/解析结果';
  END IF;
END$$
DELIMITER ;
CALL add_work_event_analysis_trace();
DROP PROCEDURE IF EXISTS add_work_event_analysis_trace;
