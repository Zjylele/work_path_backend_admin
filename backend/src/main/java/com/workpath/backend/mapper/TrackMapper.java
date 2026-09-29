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
            </where>
            ORDER BY t.start_time DESC
            </script>
            """)
    IPage<AdminTrackVO> selectAdminTracks(Page<AdminTrackVO> page,
                                          @Param("userId") Long userId,
                                          @Param("startTime") LocalDateTime startTime,
                                          @Param("endTime") LocalDateTime endTime);

    /**
     * 捞待分析的轨迹。定时任务用，只取未分析（analysis_status=0）的。
     *
     * <p>按 start_time 升序——先传的轨迹先分析，避免新上传的把老轨迹挤到后面
     * 永远排不上（积压场景下这会让最早的几条数据长期不出结果）。
     * ORDER BY start_time 而非 id，因为轨迹的 start_time 才对应真实的业务先后。</p>
     *
     * @param limit 单轮最多捞几条，防止一次捞太多把线程池占满
     */
    @Select("""
            SELECT * FROM track
            WHERE analysis_status = 0
            ORDER BY start_time ASC, id ASC
            LIMIT #{limit}
            """)
    List<Track> selectPendingAnalysis(@Param("limit") int limit);

}
