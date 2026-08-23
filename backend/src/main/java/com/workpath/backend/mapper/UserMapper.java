package com.workpath.backend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.workpath.backend.entity.User;
import com.workpath.backend.vo.AdminUserVO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface UserMapper extends BaseMapper<User> {

    @Select("""
            <script>
            SELECT u.id, u.username, u.nickname, u.role, u.super_admin, u.status, u.create_time,
                   (SELECT COUNT(*) FROM track t WHERE t.user_id = u.id) AS track_count
            FROM `user` u
            <where>
                <if test="keyword != null and keyword != ''">
                    AND (u.username LIKE CONCAT('%', #{keyword}, '%') OR u.nickname LIKE CONCAT('%', #{keyword}, '%'))
                </if>
            </where>
            ORDER BY u.create_time DESC
            </script>
            """)
    IPage<AdminUserVO> selectAdminUsers(Page<AdminUserVO> page, @Param("keyword") String keyword);

}
