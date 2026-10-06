package com.workpath.backend.dto;

import lombok.Data;

/**
 * 条款引用有效率的聚合行（Mapper 一次查询返回，不是实体）。
 *
 * <p>四个计数放在同一条 SQL 里算，而不是发四次 selectCount：
 * 口径写在一处才不会随改动漂移，也省掉四次数据库往返。</p>
 */
@Data
public class ClauseVerificationRow {

    /** 结论总数 */
    private Long total;

    /** 模型实际给出条款引用的条数（有效率的分子分母都以它为准） */
    private Long citationGiven;

    /** 引用通过回查的条数 */
    private Long citationValid;

    /** 给出引用但回查不通过的条数 */
    private Long citationInvalid;
}
