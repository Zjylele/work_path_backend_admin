package com.workpath.backend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.workpath.backend.common.BusinessException;
import com.workpath.backend.common.ResultCode;
import com.workpath.backend.entity.ComplianceCheck;
import com.workpath.backend.entity.ComplianceRule;
import com.workpath.backend.interceptor.UserContext;
import com.workpath.backend.mapper.ComplianceCheckMapper;
import com.workpath.backend.mapper.ComplianceRuleMapper;
import com.workpath.backend.service.compliance.RuleEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 合规规则的配置管理（M5-W5）。CRUD 与校验都在这里，
 * 判定逻辑在 {@link RuleEngine}——配置错误要在保存时拦住，
 * 而不是等到核查时静默跳过。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ComplianceRuleService {

    private static final Set<String> RULE_TYPES = Set.of("DURATION", "FREQUENCY", "RANGE", "ABNORMAL");

    /** 这三类没有阈值就没有判定标准，保存时直接拒绝 */
    private static final Set<String> THRESHOLD_REQUIRED = Set.of("DURATION", "FREQUENCY", "RANGE");

    private final ComplianceRuleMapper ruleMapper;
    private final ComplianceCheckMapper checkMapper;
    private final com.workpath.backend.mapper.PolicyClauseMapper clauseMapper;
    private final OperationLogService operationLogService;

    public List<ComplianceRule> list(Integer enabled) {
        LambdaQueryWrapper<ComplianceRule> q = new LambdaQueryWrapper<>();
        if (enabled != null) {
            q.eq(ComplianceRule::getEnabled, enabled);
        }
        q.orderByAsc(ComplianceRule::getRuleCode);
        List<ComplianceRule> rules = ruleMapper.selectList(q);
        fillClauses(rules);
        return rules;
    }

    /** 批量补齐关联条款的编号与内容摘要，让"规则挂在哪条制度上"直接可读。 */
    private void fillClauses(List<ComplianceRule> rules) {
        List<Long> ids = rules.stream()
                .map(ComplianceRule::getPolicyClauseId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return;
        }
        Map<Long, com.workpath.backend.entity.PolicyClause> clauses =
                clauseMapper.selectBatchIds(ids).stream()
                        .collect(java.util.stream.Collectors.toMap(
                                com.workpath.backend.entity.PolicyClause::getClauseId,
                                java.util.function.Function.identity(), (a, b) -> a));
        rules.forEach(r -> {
            var c = clauses.get(r.getPolicyClauseId());
            if (c != null) {
                r.setClauseCode(c.getClauseCode());
                String content = c.getContent();
                r.setClauseContent(content != null && content.length() > 40
                        ? content.substring(0, 40) + "…" : content);
            }
        });
    }

    @Transactional
    public Long create(ComplianceRule rule) {
        validate(rule, null);
        rule.setRuleId(null);
        if (rule.getEnabled() == null) {
            rule.setEnabled(1);
        }
        ruleMapper.insert(rule);
        logCreate(rule);
        return rule.getRuleId();
    }

    @Transactional
    public void update(Long ruleId, ComplianceRule patch) {
        ComplianceRule exist = ruleMapper.selectById(ruleId);
        if (exist == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "规则不存在");
        }
        validate(patch, ruleId);
        patch.setRuleId(ruleId);
        ruleMapper.updateById(patch);
        logCreate(patch);
    }

    /**
     * 删除规则。已被核查结论引用的规则拒绝硬删——check.rule_id 是
     * 「结论为什么成立」的追溯线索，删掉它历史结论就解释不了自己；
     * 不要这条规则时停用即可。
     */
    @Transactional
    public void delete(Long ruleId) {
        ComplianceRule exist = ruleMapper.selectById(ruleId);
        if (exist == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "规则不存在");
        }
        Long referenced = checkMapper.selectCount(new LambdaQueryWrapper<ComplianceCheck>()
                .eq(ComplianceCheck::getRuleId, ruleId));
        if (referenced != null && referenced > 0) {
            throw new BusinessException(ResultCode.CONFLICT.getCode(),
                    "该规则已有 " + referenced + " 条核查结论引用，不能删除，可改为停用");
        }
        ruleMapper.deleteById(ruleId);
        logCreate(exist);
    }

    private void validate(ComplianceRule rule, Long selfId) {
        if (rule == null || !StringUtils.hasText(rule.getRuleCode())) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "规则编码不能为空");
        }
        if (!StringUtils.hasText(rule.getRuleName())) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "规则名称不能为空");
        }
        if (!RULE_TYPES.contains(rule.getRuleType())) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(),
                    "规则类型必须是 DURATION/FREQUENCY/RANGE/ABNORMAL");
        }
        if (THRESHOLD_REQUIRED.contains(rule.getRuleType()) && rule.getThreshold() == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(),
                    rule.getRuleType() + " 类型必须填写阈值");
        }
        // extra_config 有值就必须是合法 JSON 且驻地字段可用，坏配置宁可挡在保存时
        if (StringUtils.hasText(rule.getExtraConfig())
                && new RuleEngine().parseStation(rule) == null
                && !"{}".equals(rule.getExtraConfig().trim())) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(),
                    "附加配置必须是含 station_lat/station_lng 数值的 JSON");
        }
        // 规则编码唯一：排除自身后查重（uk_rule_code 兜底，这里给出可读错误）
        Long dup = ruleMapper.selectCount(new LambdaQueryWrapper<ComplianceRule>()
                .eq(ComplianceRule::getRuleCode, rule.getRuleCode().trim())
                .ne(selfId != null, ComplianceRule::getRuleId, selfId));
        if (dup != null && dup > 0) {
            throw new BusinessException(ResultCode.CONFLICT.getCode(), "规则编码已存在");
        }
        if (StringUtils.hasText(rule.getRuleCode())) {
            rule.setRuleCode(rule.getRuleCode().trim());
        }
    }

    /** 操作留痕。命名 logCreate 是因为 create/update/delete 都是"改了规则配置"。 */
    private void logCreate(ComplianceRule rule) {
        operationLogService.record(UserContext.getUserId(), null, "变更合规规则",
                "compliance_rule", rule.getRuleId(), rule.getRuleName(),
                "ruleCode=" + rule.getRuleCode() + " type=" + rule.getRuleType(), true);
    }
}
