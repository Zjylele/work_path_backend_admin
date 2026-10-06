package com.workpath.backend.service;

import com.workpath.backend.dto.ClauseVerificationRow;
import com.workpath.backend.mapper.ComplianceCheckMapper;
import com.workpath.backend.mapper.ComplianceRuleMapper;
import com.workpath.backend.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.when;

/**
 * 条款引用有效率的统计口径测试。
 *
 * <p>这是答辩时唯一能拿出的量化结论，口径必须钉死：分母是「模型实际给出
 * 引用的次数」，不是全部结论数。ABNORMAL 这类没有条款锚点的结论压根没
 * 参与回查，把它们算进分母，有效率会被稀释成一个没有意义的数字。</p>
 */
class ComplianceCheckServiceTest {

    private ComplianceCheckMapper checkMapper;
    private ComplianceRuleMapper ruleMapper;
    private UserMapper userMapper;
    private ComplianceCheckService service;

    @BeforeEach
    void setUp() {
        checkMapper = Mockito.mock(ComplianceCheckMapper.class);
        ruleMapper = Mockito.mock(ComplianceRuleMapper.class);
        userMapper = Mockito.mock(UserMapper.class);
        service = new ComplianceCheckService(checkMapper, ruleMapper, userMapper);
    }

    @Test
    @DisplayName("引用有效率：分母只算模型给出引用的条数，无条款锚点的不进分母")
    void computesRateOverCitationsOnly() {
        when(checkMapper.selectClauseVerificationRow()).thenReturn(row(34, 25, 22, 3));

        ComplianceCheckService.ClauseVerificationStats s = service.clauseVerificationStats();

        assertThat(s.total()).isEqualTo(34);
        assertThat(s.citationGiven()).isEqualTo(25);
        assertThat(s.citationValid()).isEqualTo(22);
        assertThat(s.citationInvalid()).isEqualTo(3);
        assertThat(s.withoutCitation())
                .as("34 条结论里 9 条没有条款引用（ABNORMAL，或模型没给出编号）")
                .isEqualTo(9);
        assertThat(s.validRate()).isCloseTo(0.88, within(0.0001));
    }

    @Test
    @DisplayName("一条引用都没有时有效率为 null，不做除零")
    void rateIsNullWhenNoCitation() {
        when(checkMapper.selectClauseVerificationRow()).thenReturn(row(3, 0, 0, 0));

        ComplianceCheckService.ClauseVerificationStats s = service.clauseVerificationStats();

        assertThat(s.validRate()).isNull();
        assertThat(s.withoutCitation()).isEqualTo(3);
    }

    @Test
    @DisplayName("空表（聚合行缺失）时不抛异常，返回全零")
    void handlesNullRow() {
        when(checkMapper.selectClauseVerificationRow()).thenReturn(null);

        ComplianceCheckService.ClauseVerificationStats s = service.clauseVerificationStats();

        assertThat(s.total()).isZero();
        assertThat(s.citationGiven()).isZero();
        assertThat(s.validRate()).isNull();
    }

    private static ClauseVerificationRow row(long total, long given, long valid, long invalid) {
        ClauseVerificationRow r = new ClauseVerificationRow();
        r.setTotal(total);
        r.setCitationGiven(given);
        r.setCitationValid(valid);
        r.setCitationInvalid(invalid);
        return r;
    }
}
