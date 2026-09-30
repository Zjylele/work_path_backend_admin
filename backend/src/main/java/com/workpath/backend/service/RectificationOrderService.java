package com.workpath.backend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.workpath.backend.common.BusinessException;
import com.workpath.backend.common.ResultCode;
import com.workpath.backend.entity.RectificationOrder;
import com.workpath.backend.entity.User;
import com.workpath.backend.mapper.RectificationOrderMapper;
import com.workpath.backend.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * M5-W7 整改工单状态机。
 *
 * <p>合法流转（实施指南 W7 的状态图）：</p>
 * <pre>
 *   0 待处理 --员工提交处理说明--> 1 处理中 --管理员确认整改--> 2 已闭环
 *   0 待处理 --员工申诉---------> 3 申诉中 --管理员复核(维持/接受)--> 2 已闭环
 * </pre>
 *
 * <p>两条硬约束：</p>
 * <ol>
 *   <li>员工不能自行关闭工单：0→2、1→2 只能走管理端；员工侧只有
 *       提交说明（0→1）与申诉（0→3）两个动作；</li>
 *   <li>申诉只改变状态不改变结论：复核的两个出口都落在 2 已闭环，
 *       结论对错由管理员意见（review_note）记录，员工申诉不能改写 check。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RectificationOrderService {

    public static final int STATUS_PENDING = 0;
    public static final int STATUS_PROCESSING = 1;
    public static final int STATUS_CLOSED = 2;
    public static final int STATUS_APPEALING = 3;

    private final RectificationOrderMapper orderMapper;
    private final UserMapper userMapper;

    /** 员工：我的工单。 */
    public List<RectificationOrder> myOrders(Long userId) {
        List<RectificationOrder> orders = orderMapper.selectList(
                new LambdaQueryWrapper<RectificationOrder>()
                        .eq(RectificationOrder::getUserId, userId)
                        .orderByDesc(RectificationOrder::getCreateTime));
        fillUsernames(orders);
        return orders;
    }

    /** 管理员：工单列表，可按状态筛选。 */
    public List<RectificationOrder> list(Integer status) {
        LambdaQueryWrapper<RectificationOrder> q = new LambdaQueryWrapper<>();
        if (status != null) {
            q.eq(RectificationOrder::getStatus, status);
        }
        q.orderByDesc(RectificationOrder::getCreateTime);
        List<RectificationOrder> orders = orderMapper.selectList(q);
        fillUsernames(orders);
        return orders;
    }

    private void fillUsernames(List<RectificationOrder> orders) {
        if (orders.isEmpty()) {
            return;
        }
        List<Long> userIds = orders.stream().map(RectificationOrder::getUserId).distinct().toList();
        Map<Long, String> names = userMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, User::getUsername, (a, b) -> a));
        orders.forEach(o -> o.setUsername(names.get(o.getUserId())));
    }

    /** 员工提交处理说明：0→1。其他状态一律拒绝。 */
    @Transactional
    public void handle(Long orderId, Long userId, String handleNote) {
        RectificationOrder order = requireOwned(orderId, userId);
        requireStatus(order, STATUS_PENDING, "提交处理说明");
        patch(order.getOrderId(), STATUS_PENDING, p -> {
            p.setStatus(STATUS_PROCESSING);
            p.setHandleNote(handleNote);
            p.setHandleTime(LocalDateTime.now());
        });
    }

    /** 员工申诉：0→3。处理中不可申诉——先有说明才有争议的锚点。 */
    @Transactional
    public void appeal(Long orderId, Long userId, String appealReason) {
        RectificationOrder order = requireOwned(orderId, userId);
        requireStatus(order, STATUS_PENDING, "提交申诉");
        patch(order.getOrderId(), STATUS_PENDING, p -> {
            p.setStatus(STATUS_APPEALING);
            p.setAppealReason(appealReason);
        });
    }

    /** 管理员确认整改：1→2。待处理直接闭环被拒——没看过员工说明就闭环违背流程。 */
    @Transactional
    public void close(Long orderId, String reviewNote) {
        RectificationOrder order = requireOrder(orderId);
        requireStatus(order, STATUS_PROCESSING, "确认整改");
        patch(order.getOrderId(), STATUS_PROCESSING, p -> {
            p.setStatus(STATUS_CLOSED);
            p.setReviewNote(reviewNote);
            p.setCloseTime(LocalDateTime.now());
        });
    }

    /**
     * 管理员复核申诉：3→2。两个出口都是闭环：
     * 维持原结论（结论不变，意见留痕）或接受申诉（意见记录修正内容）。
     * 都不改 compliance_check——申诉改变的是工单状态，不是核查结论。
     */
    @Transactional
    public void review(Long orderId, boolean accept, String reviewNote) {
        RectificationOrder order = requireOrder(orderId);
        requireStatus(order, STATUS_APPEALING, "复核申诉");
        String head = accept ? "接受申诉，结论修正" : "维持原结论";
        String note = StringUtils.hasText(reviewNote) ? head + "：" + reviewNote : head;
        patch(order.getOrderId(), STATUS_APPEALING, p -> {
            p.setStatus(STATUS_CLOSED);
            p.setReviewNote(note);
            p.setCloseTime(LocalDateTime.now());
        });
    }

    private RectificationOrder requireOrder(Long orderId) {
        RectificationOrder order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "工单不存在");
        }
        return order;
    }

    /** 员工操作前置：工单存在且属于本人——员工只能动自己的工单。 */
    private RectificationOrder requireOwned(Long orderId, Long userId) {
        RectificationOrder order = requireOrder(orderId);
        if (userId == null || !userId.equals(order.getUserId())) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "只能操作本人的工单");
        }
        return order;
    }

    private void requireStatus(RectificationOrder order, int expected, String action) {
        if (order.getStatus() == null || order.getStatus() != expected) {
            throw new BusinessException(ResultCode.CONFLICT.getCode(),
                    "当前状态不允许" + action + "（期望 "
                            + expected + "，实际 " + order.getStatus() + "）");
        }
    }

    /** 只推状态与对应字段，不整实体回写（与 AnalysisTaskQueue.markStatus 同口径）。 */
    private void patch(Long orderId, int expectedStatus, java.util.function.Consumer<RectificationOrder> mutator) {
        RectificationOrder p = new RectificationOrder();
        p.setOrderId(orderId);
        mutator.accept(p);
        if (orderMapper.updateById(p) == 0) {
            throw new BusinessException(ResultCode.CONFLICT.getCode(), "工单不存在或已被删除");
        }
        log.info("工单状态流转 orderId={} {} -> {}", orderId, expectedStatus, p.getStatus());
    }
}
