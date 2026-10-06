package com.workpath.backend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.workpath.backend.dto.ClauseVerificationRow;
import com.workpath.backend.entity.ComplianceCheck;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ComplianceCheckMapper extends BaseMapper<ComplianceCheck> {

    /**
     * 条款引用有效率的聚合行。
     *
     * <p>「给出引用的条数」按 cited_clause_code 非空判定，而不是看 clause_verified：
     * clause_verified=0 既包含「引用了不存在的编号」，也包含「压根没给编号」
     * （ABNORMAL 类结论、模型失败）。两者混在一起当分母，得到的就不是引用有效率。</p>
     */
    @Select("""
            SELECT
              COUNT(*) AS total,
              SUM(CASE WHEN cited_clause_code IS NOT NULL AND cited_clause_code <> ''
                       THEN 1 ELSE 0 END) AS citation_given,
              SUM(CASE WHEN clause_verified = 1 THEN 1 ELSE 0 END) AS citation_valid,
              SUM(CASE WHEN cited_clause_code IS NOT NULL AND cited_clause_code <> ''
                            AND clause_verified = 0
                       THEN 1 ELSE 0 END) AS citation_invalid
            FROM compliance_check
            """)
    ClauseVerificationRow selectClauseVerificationRow();
}
