package com.workbench.audit.application;

import com.workbench.audit.domain.AuditLog;
import com.workbench.audit.dto.AuditLogView;
import com.workbench.audit.repository.AuditLogRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 审计日志查询服务（治理底座 C）。
 *
 * <p><b>只读</b>：本类只提供查询，不提供任何写入或删除。审计写入统一走 {@link AuditService}，
 * 保证「审计只能被记录、不能被顺手篡改」这条边界不因新增页面而破。</p>
 *
 * <p>授权不在这里做：调用方 {@code AuditLogController} 已在类级用
 * {@code @PreAuthorize("hasRole('SYSTEM_ADMIN')")} 把关（ADR-M012）。</p>
 *
 * <p><b>分页不用 Pageable</b>：见 {@code AuditLogRepository.search} 的注释——JPQL 分页在
 * PostgreSQL 上会因行号参数类型无法推断而报错。这里显式算 limit/offset 交给原生 SQL。</p>
 */
@Service
@Transactional(readOnly = true)
public class AuditQueryService {

  /** 单页上限：防止前端传 size=100000 把整张表拉进内存。 */
  public static final int MAX_PAGE_SIZE = 200;

  private static final int DEFAULT_PAGE_SIZE = 50;

  private final AuditLogRepository repository;

  public AuditQueryService(AuditLogRepository repository) {
    this.repository = repository;
  }

  /**
   * 分页查询审计日志，按时间倒序。
   *
   * @param page 页码，从 1 开始；小于 1 按 1 处理
   * @param size 每页条数；小于 1 按 50，大于 {@link #MAX_PAGE_SIZE} 按上限截断
   */
  public Map<String, Object> search(String username, String action, String result,
      String traceId, LocalDateTime fromTime, LocalDateTime toTime, int page, int size) {
    int currentPage = page < 1 ? 1 : page;
    int safeSize = size < 1 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
    int offset = (currentPage - 1) * safeSize;

    String u = blankToNull(username);
    String a = blankToNull(action);
    String r = blankToNull(result);
    String t = blankToNull(traceId);
    boolean hasFrom = fromTime != null;
    boolean hasTo = toTime != null;

    List<AuditLogView> items = repository
        .search(u, a, r, t, hasFrom, fromTime, hasTo, toTime, safeSize, offset)
        .stream().map(AuditLogView::of).toList();
    long total = repository.countSearch(u, a, r, t, hasFrom, fromTime, hasTo, toTime);

    return Map.of(
        "items", items,
        "total", total,
        "page", currentPage,
        "size", safeSize);
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}