package com.workpath.backend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.workpath.backend.entity.Track;
import com.workpath.backend.vo.AdminTrackVO;
import com.workpath.backend.vo.TrackListVO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

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
                <if test="analysisStatuses != null and !analysisStatuses.isEmpty()">
                    AND t.analysis_status IN
                    <foreach collection="analysisStatuses" item="status" open="(" separator="," close=")">
                        #{status}
                    </foreach>
                </if>
                <if test="trackIds != null and !trackIds.isEmpty()">
                    AND t.id IN
                    <foreach collection="trackIds" item="trackId" open="(" separator="," close=")">
                        #{trackId}
                    </foreach>
                </if>
            </where>
            ORDER BY t.start_time DESC, t.id DESC
            </script>
            """)
    IPage<AdminTrackVO> selectAdminTracks(Page<AdminTrackVO> page,
                                          @Param("userId") Long userId,
                                          @Param("startTime") LocalDateTime startTime,
                                          @Param("endTime") LocalDateTime endTime,
                                          @Param("analysisStatuses") List<Integer> analysisStatuses,
                                          @Param("trackIds") List<Long> trackIds);

    /** 仅允许从待分析/失败切换到处理中，用数据库条件更新挡住重复提交。 */
    @Update("UPDATE track SET analysis_status = 1 WHERE id = #{id} AND analysis_status IN (0, 3)")
    int claimForManualAnalysis(@Param("id") Long id);

}
