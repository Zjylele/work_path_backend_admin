package com.workpath.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 审计日志 target_type 列宽与代码实际写入值的对账。
 *
 * <p>存在的理由：M6 的 ModelProviderService 把 target_type 写成
 * "model_provider_config"（21 字符），而 operation_log.target_type 是
 * VARCHAR(20)——越界一字符。更糟的是 OperationLogService.record() 用
 * try-catch 把 SQLException 吞掉了，于是「保存模型配置」这条审计日志
 * <b>静默丢失</b>：页面提示成功、配置也落库，只有日志表查不到记录。</p>
 *
 * <p>这类缺陷三个传统防线全部失效：单元测试 mock 掉 mapper、@SpringBootTest
 * 不碰这条路径、肉眼 review 也极难数出 21 个字符。所以这里直接拿代码里
 * 实际会写入的字符串去量列宽。</p>
 */
@SpringBootTest
class AuditTargetTypeWidthTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** operation_log.target_type 的列宽，字节数。 */
    private int targetTypeWidth() {
        Integer w = jdbcTemplate.queryForObject(
                "SELECT CHARACTER_MAXIMUM_LENGTH FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'operation_log' "
                        + "AND COLUMN_NAME = 'target_type'",
                Integer.class);
        assertThat(w).as("operation_log.target_type 列必须存在").isNotNull();
        return w;
    }

    @Test
    @DisplayName("列宽足够容纳生产代码写入的最长 target_type")
    void columnFitsLongestProducedValue() {
        int width = targetTypeWidth();

        // 下面两个字符串逐字取自 ModelProviderService.save() 与 testConnection()
        // 里传给 record(...) 的实参，不是凭印象写的常量。
        List<String> produced = List.of(
                "USER",                       // AdminService
                "TRACK",                      // AdminService
                "model_provider_config"       // ModelProviderService（M6 新增）
        );

        String longest = produced.stream()
                .max(java.util.Comparator.comparingInt(String::length))
                .orElseThrow();

        assertThat(width)
                .as("target_type 最长要写 \"%s\"（%d 字符），列宽只有 %d。"
                                + "扩列到 VARCHAR(30) 可容纳表名，比改短字符串更贴合"
                                + "该字段作为通用审计字段的定位",
                        longest, longest.length(), width)
                .isGreaterThanOrEqualTo(longest.length());
    }

    @Test
    @DisplayName("已存在的审计日志没有因列宽被静默截断")
    void existingRowsFitWidth() {
        int width = targetTypeWidth();
        Integer overflow = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM operation_log WHERE target_type IS NOT NULL "
                        + "AND CHAR_LENGTH(target_type) >= ?",
                Integer.class, width);
        assertThat(overflow)
                .as("有审计日志的 target_type 长度贴到列宽上限，随时可能超界")
                .isZero();
    }
}
