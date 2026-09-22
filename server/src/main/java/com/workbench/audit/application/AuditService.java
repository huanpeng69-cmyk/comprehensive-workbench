package com.workbench.audit.application;

import com.workbench.audit.domain.AuditLog;
import com.workbench.audit.repository.AuditLogRepository;
import com.workbench.security.CurrentUserHolder;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 审计日志服务：审批动作（发起/通过/驳回/转办/抄送/完成）统一留痕。
 * 写入用独立事务（REQUIRES_NEW）：即使外层业务事务只读或回滚，审计记录也不受影响。
 */
@Service
public class AuditService {

  private final AuditLogRepository auditLogRepository;

  public AuditService(AuditLogRepository auditLogRepository) {
    this.auditLogRepository = auditLogRepository;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void log(String action, String targetType, String targetId, String detail) {
    log(CurrentUserHolder.currentUsername(), action, targetType, targetId, detail);
  }

  /**
   * 显式指定用户名：登录成功/失败等场景下安全上下文尚未建立，无法从上下文取用户。
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void log(String username, String action, String targetType, String targetId, String detail) {
    auditLogRepository.save(AuditLog.builder()
        .username(username)
        .action(action)
        .targetType(targetType)
        .targetId(targetId)
        .detail(detail)
        .createdAt(LocalDateTime.now())
        .build());
  }
}
