package com.workpath.backend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.workpath.backend.entity.PolicyDocument;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface PolicyDocumentMapper extends BaseMapper<PolicyDocument> {

    /**
     * 查同名文档里版本号最大的那条，作为下一次上传的版本基数。
     *
     * <p>版本管理要求"同名文档上传 → 生成下一版本、旧版本停用但保留"，
     * 所以这里不能按 title 直接命中就返回（可能有多条历史版本），
     * 必须取版本号最大的那条才知道现在到第几版。</p>
     *
     * @return 没有同名文档时返回 null（首版）
     */
    @Select("""
            SELECT * FROM policy_document WHERE title = #{title}
            ORDER BY CAST(SUBSTRING(version, 2) AS UNSIGNED) DESC LIMIT 1
            """)
    PolicyDocument selectLatestByTitle(@Param("title") String title);
}
