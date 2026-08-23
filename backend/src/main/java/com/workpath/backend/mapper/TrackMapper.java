package com.workpath.backend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.workpath.backend.entity.Track;
import com.workpath.backend.vo.AdminTrackVO;
import com.workpath.backend.vo.TrackListVO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

public interface TrackMapper extends BaseMapper<Track> {

    @Select("""
            SELECT t.*, (SELECT COUNT(*) FROM track_point p WHERE p.track_id = t.id) AS point_count
            FROM track t
            WHERE t.user_id = #{userId}
            ORDER BY t.start_time DESC
            """)
    IPage<TrackListVO> selectMyTracks(Page<TrackListVO> page, @Param("userId") Long userId);

    @Select("""
            <script>
            SELECT t.*, u.username AS username,
                   (SELECT COUNT(*) FROM track_point p WHERE p.track_id = t.id) AS point_count
            FROM track t
            LEFT JOIN `user` u ON t.user_id = u.id
            <where>
                <if test="userId != null">t.user_id = #{userId}</if>
                <if test="startTime != null">AND t.start_time &gt;= #{startTime}</if>
                <if test="endTime != null">AND t.start_time &lt;= #{endTime}</if>
            </where>
            ORDER BY t.start_time DESC
            </script>
            """)
    IPage<AdminTrackVO> selectAdminTracks(Page<AdminTrackVO> page,
                                          @Param("userId") Long userId,
                                          @Param("startTime") LocalDateTime startTime,
                                          @Param("endTime") LocalDateTime endTime);

}
