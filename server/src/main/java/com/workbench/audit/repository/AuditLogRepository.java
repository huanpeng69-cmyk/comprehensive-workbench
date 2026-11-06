package com.workbench.audit.repository;

import com.workbench.audit.domain.AuditLog;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 审计日志仓储。
 *
 * <p>治理底座 C：为审计查询页提供筛选能力。所有条件均可为 null，表示不按该维度过滤。</p>
 */
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

  /**
   * 分页查询审计日志（按创建时间倒序）。
   *
   * <p><b>为什么用原生 SQL 而不是 JPQL + Pageable</b>（2026-11-05 实测根因）：
   * JPQL 分页会生成 {@code ... fetch first ? rows only}，行号参数在 PostgreSQL 的 prepare
   * 阶段类型未知；本查询叠加 6 组 {@code (? is null or col = ?)} 后，PG 不再猜测，直接报
   * {@code could not determine data type of parameter $9}，接口 500、页面空白。
   * 改用原生 SQL，{@code limit}/{@code offset} 显式传 Integer，类型确定（ADR-M012）。</p>
   *
   * <p>时间条件用布尔开关控制是否参与比较：直接写 {@code (:fromTime is null or ...)} 时，
   * 时间参数为 null 仍会触发同样的类型推断失败。</p>
   */
  @Query(value = "select * from audit_log a where "
      + "(:username is null or a.username = :username) and "
      + "(:action is null or a.action = :action) and "
      + "(:result is null or a.result = :result) and "
      + "(:traceId is null or a.trace_id = :traceId) and "
      + "(:hasFrom = false or a.created_at >= :fromTime) and "
      + "(:hasTo = false or a.created_at <= :toTime) "
      + "order by a.created_at desc limit :limit offset :offset",
      nativeQuery = true)
  List<AuditLog> search(
      @Param("username") String username,
      @Param("action") String action,
      @Param("result") String result,
      @Param("traceId") String traceId,
      @Param("hasFrom") boolean hasFrom,
      @Param("fromTime") LocalDateTime fromTime,
      @Param("hasTo") boolean hasTo,
      @Param("toTime") LocalDateTime toTime,
      @Param("limit") int limit,
      @Param("offset") int offset);

  /** 与 {@link #search} 同条件的总数查询；时间条件同样用布尔开关。 */
  @Query(value = "select count(*) from audit_log a where "
      + "(:username is null or a.username = :username) and "
      + "(:action is null or a.action = :action) and "
      + "(:result is null or a.result = :result) and "
      + "(:traceId is null or a.trace_id = :traceId) and "
      + "(:hasFrom = false or a.created_at >= :fromTime) and "
      + "(:hasTo = false or a.created_at <= :toTime)",
      nativeQuery = true)
  long countSearch(
      @Param("username") String username,
      @Param("action") String action,
      @Param("result") String result,
      @Param("traceId") String traceId,
      @Param("hasFrom") boolean hasFrom,
      @Param("fromTime") LocalDateTime fromTime,
      @Param("hasTo") boolean hasTo,
      @Param("toTime") LocalDateTime toTime);
}