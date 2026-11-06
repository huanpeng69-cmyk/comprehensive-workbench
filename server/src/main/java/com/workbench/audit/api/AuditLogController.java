package com.workbench.audit.api;

import com.workbench.audit.application.AuditQueryService;
import com.workbench.common.Result;
import java.time.LocalDateTime;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 审计日志查询接口（治理底座 C）。
 *
 * <p><b>授权口径：仅 SYSTEM_ADMIN</b>，用类级 {@link PreAuthorize} 放在唯一的 HTTP 入口上，
 * Service 内不再判断角色（ADR-M012：同一份口径只落一处，避免两处漂移）。
 * 注意这里是 {@code hasRole('SYSTEM_ADMIN')} 而不是 {@code hasAnyRole(...SYSTEM_ADMIN, HR_ADMIN)}——
 * 审计记录含全单位所有人与操作，HR_ADMIN 不具备该可见范围。</p>
 *
 * <p>与记忆接口（{@code /api/memories}）的边界：记忆按 {@code (companyId, username)} 强隔离，
 * 任何登录用户只能看自己的；本接口是全量审计，只有系统管理员能看。二者不得混用授权。</p>
 */
@RestController
@RequestMapping("/api/audit-logs")
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
public class AuditLogController {

  private final AuditQueryService auditQueryService;

  public AuditLogController(AuditQueryService auditQueryService) {
    this.auditQueryService = auditQueryService;
  }

  /**
   * 分页查询审计日志（按时间倒序）。
   *
   * <p>所有筛选参数可省略。时间参数用 ISO-8601 本地时间（如 {@code 2026-11-05T00:00:00}）。</p>
   */
  @GetMapping
  public Result<Map<String, Object>> list(
      @RequestParam(required = false) String username,
      @RequestParam(required = false) String action,
      @RequestParam(required = false) String result,
      @RequestParam(required = false) String traceId,
      @RequestParam(required = false)
      @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
      @RequestParam(required = false)
      @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "50") int size) {
    return Result.success(
        auditQueryService.search(username, action, result, traceId, from, to, page, size));
  }
}