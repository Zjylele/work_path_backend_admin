package com.workpath.backend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.workpath.backend.dto.ClauseVerificationRow;
import com.workpath.backend.entity.ComplianceCheck;
import com.workpath.backend.entity.ComplianceRule;
import com.workpath.backend.entity.User;
import com.workpath.backend.mapper.ComplianceCheckMapper;
import com.workpath.backend.mapper.ComplianceRuleMapper;
import com.workpath.backend.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 合规核查结论的读侧服务（列表展示）。与写侧 {@link ComplianceService}
 * 分开，理由同 WorkEventService：写侧跑在确认请求的事务里，读侧是人
 * 一页页翻的查询路径，混在一起会让事务边界变糊。
 */
@Service
@RequiredArgsConstructor
public class ComplianceCheckService {

    private final ComplianceCheckMapper checkMapper;
    private final ComplianceRuleMapper ruleMapper;
    private final UserMapper userMapper;

    /**
     * 核查结论列表，可按员工/日期/结果筛选。规则名与用户名批量补齐——
     * 结论量在几十到几百条，逐条查会 N+1。
     */
    public List<ComplianceCheck> list(Long userId, LocalDate workDate, Integer result) {
        LambdaQueryWrapper<ComplianceCheck> q = new LambdaQueryWrapper<>();
        if (userId != null) {
            q.eq(ComplianceCheck::getUserId, userId);
        }
        if (workDate != null) {
            q.eq(ComplianceCheck::getWorkDate, workDate);
        }
        if (result != null) {
            q.eq(ComplianceCheck::getResult, result);
        }
        q.orderByDesc(ComplianceCheck::getCreateTime);
        List<ComplianceCheck> checks = checkMapper.selectList(q);
        if (checks.isEmpty()) {
            return checks;
        }

        List<Long> ruleIds = checks.stream().map(ComplianceCheck::getRuleId).distinct().toList();
        Map<Long, ComplianceRule> rules = ruleMapper.selectBatchIds(ruleIds).stream()
                .collect(Collectors.toMap(ComplianceRule::getRuleId, Function.identity(), (a, b) -> a));
        List<Long> userIds = checks.stream().map(ComplianceCheck::getUserId).distinct().toList();
        Map<Long, String> names = userMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, User::getUsername, (a, b) -> a));
        checks.forEach(c -> {
            ComplianceRule rule = rules.get(c.getRuleId());
            if (rule != null) {
                c.setRuleCode(rule.getRuleCode());
                c.setRuleName(rule.getRuleName());
            }
            c.setUsername(names.get(c.getUserId()));
        });
        return checks;
    }

    /**
     * 条款引用有效率统计（答辩用的量化指标）。
     *
     * <p>分母只算「模型实际给出引用」的条数。ABNORMAL 这类没有条款锚点的结论
     * 从未参与回查，算进分母只会把有效率稀释成一个不说明问题的数字。</p>
     *
     * @return 统计结果；一次引用都没有时 validRate 为 null（不做除零）
     */
    public ClauseVerificationStats clauseVerificationStats() {
        ClauseVerificationRow row = checkMapper.selectClauseVerificationRow();
        long total = count(row == null ? null : row.getTotal());
        long given = count(row == null ? null : row.getCitationGiven());
        long valid = count(row == null ? null : row.getCitationValid());
        long invalid = count(row == null ? null : row.getCitationInvalid());
        Double rate = given == 0 ? null : (double) valid / given;
        return new ClauseVerificationStats(total, given, valid, invalid,
                Math.max(0L, total - given), rate);
    }

    /** selectCount/sum 的判空包装：空表时 SUM 返回 NULL。 */
    private static long count(Long value) {
        return value == null ? 0 : value;
    }

    /**
     * 引用有效率的统计结果。
     *
     * @param total            结论总数
     * @param citationGiven    模型给出引用的条数（分子分母的基数）
     * @param citationValid    引用通过回查的条数
     * @param citationInvalid  引用无效（编号不存在于制度库）的条数
     * @param withoutCitation  没有条款锚点的条数（ABNORMAL、模型未给出编号）
     * @param validRate        引用有效率；无引用时为 null
     */
    public record ClauseVerificationStats(long total, long citationGiven, long citationValid,
                                          long citationInvalid, long withoutCitation,
                                          Double validRate) {
    }
}
