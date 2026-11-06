package com.workbench.audit.application;

import com.workbench.audit.domain.AuditLog;
import com.workbench.audit.repository.AuditLogRepository;
import com.workbench.security.CurrentUserHolder;
import com.workbench.security.TraceContext;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 审计日志服务（治理底座 P1）：审批动作（发起/通过/驳回/转办/抄送/完成）统一留痕。
 *
 * <p>写入用独立事务（REQUIRES_NEW）：即使外层业务事务只读或回滚，审计记录也不受影响。</p>
 *
 * <p><b>结果与链路</b>：所有写入自动带上
 * <ul>
 *   <li>{@code result} —— SUCCESS / DENIED / FAILURE；</li>
 *   <li>{@code traceId} —— 当前请求的链路 id（来自 {@link TraceContext#get()}），
 *       使审计表与日志的 traceId、响应头 X-Request-Id 三处天然对齐，无需每个调用点手动传。</li>
 * </ul>
 * 既有 {@link #log} 方法签名保持不变，兼容全部历史调用点；其中越权拒绝在项目既有约定里
 * 是用 {@code action="ACCESS_DENIED"} 表达的（如记忆模块 {@code requireOwner}），
 * 本类据此把 {@code result} 映射为 {@code DENIED}，使审计表语义自洽。</p>
 */
@Service
public class AuditService {

  private static final String ACCESS_DENIED_ACTION = "ACCESS_DENIED";

  private final AuditLogRepository auditLogRepository;

  public AuditService(AuditLogRepository auditLogRepository) {
    this.auditLogRepository = auditLogRepository;
  }

  /** 成功类审计：从安全上下文取当前用户，结果按 action 映射（默认 SUCCESS）。 */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void log(String action, String targetType, String targetId, String detail) {
    log(CurrentUserHolder.currentUsername(), action, targetType, targetId, detail);
  }

  /**
   * 显式指定用户名：登录成功/失败等场景下安全上下文尚未建立，无法从上下文取用户。
   * 结果按 action 映射（{@code ACCESS_DENIED} → DENIED，其余 → SUCCESS）。
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void log(String username, String action, String targetType, String targetId,
      String detail) {
    write(username, action, targetType, targetId, detail, resultFor(action), null);
  }

  /** 权限拒绝类审计：{@code result=DENIED}，resultCode 为业务/HTTP 码（如 403）。 */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void denied(String username, String action, String targetType, String targetId,
      String detail, String resultCode) {
    write(username, action, targetType, targetId, detail, "DENIED", resultCode);
  }

  /** 失败类审计：{@code result=FAILURE}，resultCode 为业务/HTTP 码（如 500）。 */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void failed(String username, String action, String targetType, String targetId,
      String detail, String resultCode) {
    write(username, action, targetType, targetId, detail, "FAILURE", resultCode);
  }

  private void write(String username, String action, String targetType, String targetId,
      String detail, String result, String resultCode) {
    // 异步线程或登录前场景可能取不到用户名，用 anonymous 兜底避免非空约束报错。
    String safeUsername = username == null || username.isBlank() ? "anonymous" : username;
    auditLogRepository.save(AuditLog.builder()
        .username(safeUsername)
        .action(action)
        .targetType(targetType)
        .targetId(targetId)
        .detail(detail)
        .result(result)
        .resultCode(resultCode)
        .traceId(TraceContext.get())
        .createdAt(LocalDateTime.now())
        .build());
  }

  private static String resultFor(String action) {
    return ACCESS_DENIED_ACTION.equals(action) ? "DENIED" : "SUCCESS";
  }
}