package com.workpath.backend.service;

import com.workpath.backend.entity.RectificationOrder;
import com.workpath.backend.mapper.RectificationOrderMapper;
import com.workpath.backend.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * W7 工单状态机测试。实施指南 8.1 要求：四态流转全部合法路径 +
 * 非法路径被拒。
 *
 * <p>两条硬约束在这里逐条钉死：员工不能自行关闭工单（0→2、1→2 只能走
 * 管理端 close）；申诉只落在闭环，不改核查结论。</p>
 */
class RectificationOrderServiceTest {

    private RectificationOrderMapper orderMapper;
    private UserMapper userMapper;
    private RectificationOrderService service;

    @BeforeEach
    void setUp() {
        orderMapper = Mockito.mock(RectificationOrderMapper.class);
        userMapper = Mockito.mock(UserMapper.class);
        service = new RectificationOrderService(orderMapper, userMapper);
        // patch() 依赖 updateById 的返回行数，mock 默认 0 会被当成"工单已消失"
        when(orderMapper.updateById(any(RectificationOrder.class))).thenReturn(1);
    }

    // ============================================================
    // 合法路径
    // ============================================================

    @Test
    @DisplayName("0 待处理 --员工提交处理说明--> 1 处理中")
    void handlePendingToProcessing() {
        when(orderMapper.selectById(1L)).thenReturn(order(0));

        service.handle(1L, 5L, "已与客户核对完毕并补交报备");

        ArgumentCaptor<RectificationOrder> cap = ArgumentCaptor.forClass(RectificationOrder.class);
        verify(orderMapper).updateById(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo(1);
        assertThat(cap.getValue().getHandleNote()).isEqualTo("已与客户核对完毕并补交报备");
        assertThat(cap.getValue().getHandleTime()).isNotNull();
    }

    @Test
    @DisplayName("1 处理中 --管理员确认整改--> 2 已闭环")
    void closeProcessingToClosed() {
        when(orderMapper.selectById(1L)).thenReturn(order(1));

        service.close(1L, "整改到位");

        ArgumentCaptor<RectificationOrder> cap = ArgumentCaptor.forClass(RectificationOrder.class);
        verify(orderMapper).updateById(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo(2);
        assertThat(cap.getValue().getReviewNote()).isEqualTo("整改到位");
        assertThat(cap.getValue().getCloseTime()).isNotNull();
    }

    @Test
    @DisplayName("0 待处理 --员工申诉--> 3 申诉中")
    void appealPendingToAppealing() {
        when(orderMapper.selectById(1L)).thenReturn(order(0));

        service.appeal(1L, 5L, "当日停留已提前报备");

        ArgumentCaptor<RectificationOrder> cap = ArgumentCaptor.forClass(RectificationOrder.class);
        verify(orderMapper).updateById(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo(3);
        assertThat(cap.getValue().getAppealReason()).isEqualTo("当日停留已提前报备");
    }

    @Test
    @DisplayName("3 申诉中 --管理员复核维持--> 2 已闭环（意见留痕）")
    void reviewMaintainToClosed() {
        when(orderMapper.selectById(1L)).thenReturn(order(3));

        service.review(1L, false, "报备记录查无此人");

        ArgumentCaptor<RectificationOrder> cap = ArgumentCaptor.forClass(RectificationOrder.class);
        verify(orderMapper).updateById(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo(2);
        assertThat(cap.getValue().getReviewNote()).startsWith("维持原结论");
        assertThat(cap.getValue().getCloseTime()).isNotNull();
    }

    @Test
    @DisplayName("3 申诉中 --管理员复核接受--> 2 已闭环（结论修正意见）")
    void reviewAcceptToClosed() {
        when(orderMapper.selectById(1L)).thenReturn(order(3));

        service.review(1L, true, "确已补报备");

        ArgumentCaptor<RectificationOrder> cap = ArgumentCaptor.forClass(RectificationOrder.class);
        verify(orderMapper).updateById(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo(2);
        assertThat(cap.getValue().getReviewNote()).startsWith("接受申诉");
    }

    // ============================================================
    // 非法路径
    // ============================================================

    @Test
    @DisplayName("非法：员工不能把待处理工单直接闭环（0→2 被拒）")
    void directCloseRejected() {
        when(orderMapper.selectById(1L)).thenReturn(order(0));

        assertThatThrownBy(() -> service.close(1L, null))
                .isInstanceOf(com.workpath.backend.common.BusinessException.class)
                .hasMessageContaining("不允许");
    }

    @Test
    @DisplayName("非法：处理中不能申诉（1→3 被拒）")
    void appealAfterHandleRejected() {
        when(orderMapper.selectById(1L)).thenReturn(order(1));

        assertThatThrownBy(() -> service.appeal(1L, 5L, "理由"))
                .isInstanceOf(com.workpath.backend.common.BusinessException.class)
                .hasMessageContaining("不允许");
    }

    @Test
    @DisplayName("非法：已闭环工单不能再提交说明（2→1 被拒）")
    void handleClosedRejected() {
        when(orderMapper.selectById(1L)).thenReturn(order(2));

        assertThatThrownBy(() -> service.handle(1L, 5L, "说明"))
                .isInstanceOf(com.workpath.backend.common.BusinessException.class)
                .hasMessageContaining("不允许");
    }

    @Test
    @DisplayName("非法：待处理工单不能直接复核（0→2 被拒）")
    void reviewWithoutAppealRejected() {
        when(orderMapper.selectById(1L)).thenReturn(order(0));

        assertThatThrownBy(() -> service.review(1L, true, null))
                .isInstanceOf(com.workpath.backend.common.BusinessException.class)
                .hasMessageContaining("不允许");
    }

    @Test
    @DisplayName("非法：员工只能操作本人的工单")
    void nonOwnerRejected() {
        when(orderMapper.selectById(1L)).thenReturn(order(0));

        assertThatThrownBy(() -> service.handle(1L, 99L, "说明"))
                .isInstanceOf(com.workpath.backend.common.BusinessException.class)
                .hasMessageContaining("本人");
    }

    @Test
    @DisplayName("非法：工单不存在时动作全部拒绝")
    void missingOrderRejected() {
        when(orderMapper.selectById(1L)).thenReturn(null);

        assertThatThrownBy(() -> service.handle(1L, 5L, "说明"))
                .isInstanceOf(com.workpath.backend.common.BusinessException.class)
                .hasMessageContaining("不存在");
    }

    // ============================================================
    // 造数
    // ============================================================

    private RectificationOrder order(int status) {
        RectificationOrder o = new RectificationOrder();
        o.setOrderId(1L);
        o.setCheckId(11L);
        o.setUserId(5L);
        o.setContent("单次停留超时：停留 60.0 分钟，超过阈值 30 分钟");
        o.setStatus(status);
        o.setCreateTime(LocalDateTime.of(2026, 9, 30, 10, 0));
        return o;
    }
}
