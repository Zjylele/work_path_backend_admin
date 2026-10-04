package com.workpath.backend.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.workpath.backend.entity.OperationLog;
import com.workpath.backend.entity.PolicyDocument;
import com.workpath.backend.entity.Track;
import com.workpath.backend.entity.User;
import com.workpath.backend.entity.WorkEvent;
import com.workpath.backend.mapper.OperationLogMapper;
import com.workpath.backend.mapper.PolicyDocumentMapper;
import com.workpath.backend.mapper.TrackMapper;
import com.workpath.backend.mapper.TrackPointMapper;
import com.workpath.backend.mapper.UserMapper;
import com.workpath.backend.mapper.WorkEventMapper;
import com.workpath.backend.vo.DashboardRecentVO;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 仪表盘最近动态（四个列表）的组装测试。
 *
 * <p>钉住三件事：待复核事件只取 confirm_status=0、制度文档只取启用版本、
 * 四个查询都带 LIMIT 10 与时间倒序；以及用户名批量填充、空数据不炸。</p>
 */
class AdminServiceRecentTest {

    private UserMapper userMapper;
    private TrackMapper trackMapper;
    private TrackPointMapper trackPointMapper;
    private OperationLogMapper operationLogMapper;
    private OperationLogService operationLogService;
    private WorkEventMapper workEventMapper;
    private PolicyDocumentMapper policyDocumentMapper;
    private AdminService service;

    @BeforeEach
    void setUp() {
        // LambdaQueryWrapper 把 lambda 解析成列名依赖 TableInfoHelper 的实体缓存。
        // 纯 Mockito 环境没有 MyBatis 的初始化时机，调用 getSqlSegment() 会抛
        // "can not find lambda cache"——这里手动初始化四个实体的表信息，
        // 让 queriesCarryFilterAndLimit 能对生成的 SQL 片段做断言
        MybatisConfiguration configuration = new MybatisConfiguration();
        for (Class<?> entity : List.of(Track.class, WorkEvent.class,
                OperationLog.class, PolicyDocument.class)) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), entity);
        }

        userMapper = Mockito.mock(UserMapper.class);
        trackMapper = Mockito.mock(TrackMapper.class);
        trackPointMapper = Mockito.mock(TrackPointMapper.class);
        operationLogMapper = Mockito.mock(OperationLogMapper.class);
        operationLogService = Mockito.mock(OperationLogService.class);
        workEventMapper = Mockito.mock(WorkEventMapper.class);
        policyDocumentMapper = Mockito.mock(PolicyDocumentMapper.class);
        service = new AdminService(userMapper, trackMapper, trackPointMapper,
                operationLogMapper, operationLogService, workEventMapper, policyDocumentMapper);
    }

    @Test
    @DisplayName("四个列表各自组装：轨迹/待复核事件/日志/制度文档，用户名批量填充")
    void assemblesFourLists() {
        LocalDateTime now = LocalDateTime.now();

        Track track = new Track();
        track.setId(11L);
        track.setUserId(2L);
        track.setName("客户拜访行程");
        track.setStartTime(now.minusHours(2));
        track.setCreateTime(now.minusHours(1));
        Mockito.when(trackMapper.selectList(Mockito.any())).thenReturn(List.of(track));

        WorkEvent event = new WorkEvent();
        event.setEventId(21L);
        event.setUserId(2L);
        event.setEventType("STAY");
        event.setPoiName("某某客户");
        event.setSemanticLabel("客户拜访");
        event.setStartTime(now.minusHours(3));
        event.setCreateTime(now.minusHours(1));
        Mockito.when(workEventMapper.selectList(Mockito.any())).thenReturn(List.of(event));

        OperationLog log = new OperationLog();
        log.setId(31L);
        log.setUsername("admin");
        log.setAction("USER_DISABLE");
        log.setTargetName("zhangsan");
        log.setDetail("这是一个超过十个字的操作详情用于验证后端不做截断");
        log.setCreateTime(now);
        Mockito.when(operationLogMapper.selectList(Mockito.any())).thenReturn(List.of(log));

        PolicyDocument doc = new PolicyDocument();
        doc.setDocId(41L);
        doc.setTitle("外勤人员管理制度");
        doc.setVersion("v1.0");
        doc.setCreateTime(now);
        Mockito.when(policyDocumentMapper.selectList(Mockito.any())).thenReturn(List.of(doc));

        User u = new User();
        u.setId(2L);
        u.setUsername("zhangsan");
        Mockito.when(userMapper.selectBatchIds(Mockito.any())).thenReturn(List.of(u));

        DashboardRecentVO vo = service.recent();

        assertThat(vo.getRecentTracks()).hasSize(1);
        assertThat(vo.getRecentTracks().get(0).getId()).isEqualTo(11L);
        assertThat(vo.getRecentTracks().get(0).getName()).isEqualTo("客户拜访行程");
        assertThat(vo.getRecentTracks().get(0).getUsername()).isEqualTo("zhangsan");
        assertThat(vo.getRecentTracks().get(0).getStartTime()).isEqualTo(track.getStartTime());
        assertThat(vo.getRecentTracks().get(0).getCreateTime()).isEqualTo(track.getCreateTime());

        assertThat(vo.getPendingEvents()).hasSize(1);
        assertThat(vo.getPendingEvents().get(0).getEventId()).isEqualTo(21L);
        assertThat(vo.getPendingEvents().get(0).getUsername()).isEqualTo("zhangsan");
        assertThat(vo.getPendingEvents().get(0).getSemanticLabel()).isEqualTo("客户拜访");
        assertThat(vo.getPendingEvents().get(0).getPoiName()).isEqualTo("某某客户");

        assertThat(vo.getRecentLogs()).hasSize(1);
        // 后端返回完整 detail——「最多 10 个字、超出省略号」是前端展示层的事
        assertThat(vo.getRecentLogs().get(0).getDetail())
                .isEqualTo("这是一个超过十个字的操作详情用于验证后端不做截断");

        assertThat(vo.getRecentDocuments()).hasSize(1);
        assertThat(vo.getRecentDocuments().get(0).getDocId()).isEqualTo(41L);
        assertThat(vo.getRecentDocuments().get(0).getTitle()).isEqualTo("外勤人员管理制度");
    }

    @Test
    @DisplayName("四个查询都带 LIMIT 10、按新增时间倒序；待复核=confirm_status 0；制度=启用版本")
    void queriesCarryFilterAndLimit() {
        Mockito.when(trackMapper.selectList(Mockito.any())).thenReturn(List.of());
        Mockito.when(workEventMapper.selectList(Mockito.any())).thenReturn(List.of());
        Mockito.when(operationLogMapper.selectList(Mockito.any())).thenReturn(List.of());
        Mockito.when(policyDocumentMapper.selectList(Mockito.any())).thenReturn(List.of());

        service.recent();

        Mockito.verify(trackMapper).selectList(Mockito.argThat(w ->
                w.getSqlSegment().contains("LIMIT 10")
                        && w.getSqlSegment().toUpperCase().contains("ORDER BY")));
        Mockito.verify(workEventMapper).selectList(Mockito.argThat(w ->
                w.getSqlSegment().contains("confirm_status")
                        && w.getSqlSegment().contains("LIMIT 10")));
        Mockito.verify(operationLogMapper).selectList(Mockito.argThat(w ->
                w.getSqlSegment().contains("LIMIT 10")));
        Mockito.verify(policyDocumentMapper).selectList(Mockito.argThat(w ->
                w.getSqlSegment().contains("status")
                        && w.getSqlSegment().contains("LIMIT 10")));
    }

    @Test
    @DisplayName("四表全空时返回空列表，不查用户表也不炸")
    void emptyDataReturnsEmptyLists() {
        Mockito.when(trackMapper.selectList(Mockito.any())).thenReturn(List.of());
        Mockito.when(workEventMapper.selectList(Mockito.any())).thenReturn(List.of());
        Mockito.when(operationLogMapper.selectList(Mockito.any())).thenReturn(List.of());
        Mockito.when(policyDocumentMapper.selectList(Mockito.any())).thenReturn(List.of());

        DashboardRecentVO vo = service.recent();

        assertThat(vo.getRecentTracks()).isEmpty();
        assertThat(vo.getPendingEvents()).isEmpty();
        assertThat(vo.getRecentLogs()).isEmpty();
        assertThat(vo.getRecentDocuments()).isEmpty();
        Mockito.verify(userMapper, Mockito.never()).selectBatchIds(Mockito.any());
    }
}
