package com.workpath.backend.service;

import com.workpath.backend.entity.OperationLog;
import com.workpath.backend.mapper.OperationLogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 操作日志服务：记录管理类操作与登录/登出，留痕审计
 * 日志只追加、不级联删除；记录失败不影响主流程
 */
@Service
@RequiredArgsConstructor
public class OperationLogService {

    private final OperationLogMapper operationLogMapper;

    public void record(Long userId, String username, String action,
                       String targetType, Long targetId, String targetName,
                       String detail, boolean success) {
        try {
            OperationLog log = new OperationLog();
            log.setUserId(userId);
            log.setUsername(username);
            log.setAction(action);
            log.setTargetType(targetType);
            log.setTargetId(targetId);
            log.setTargetName(targetName);
            log.setDetail(detail);
            log.setResult(success ? 1 : 0);
            log.setCreateTime(LocalDateTime.now());
            operationLogMapper.insert(log);
        } catch (Exception e) {
            // 日志写入失败不影响主流程
        }
    }
}
