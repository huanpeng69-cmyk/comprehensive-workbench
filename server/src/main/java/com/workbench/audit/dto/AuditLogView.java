package com.workbench.audit.dto;

import com.workbench.audit.domain.AuditLog;
import java.time.LocalDateTime;

/**
 * 审计日志视图（治理底座 C）。
 *
 * <p>与实体分离，避免直接把 JPA 实体序列化出去（实体字段改名会静默改掉前端契约）。</p>
 */
public record AuditLogView(
    Long id,
    String username,
    String action,
    String targetType,
    String targetId,
    String detail,
    /** SUCCESS / DENIED / FAILURE；基座改造前的历史记录为 null，前端显示「未标注」。 */
    String result,
    String resultCode,
    /** 链路 id；历史记录为 null。 */
    String traceId,
    LocalDateTime createdAt) {

  public static AuditLogView of(AuditLog log) {
    return new AuditLogView(
        log.getId(),
        log.getUsername(),
        log.getAction(),
        log.getTargetType(),
        log.getTargetId(),
        log.getDetail(),
        log.getResult(),
        log.getResultCode(),
        log.getTraceId(),
        log.getCreatedAt());
  }
}