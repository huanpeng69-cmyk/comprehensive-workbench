package com.workbench.memory.repository;

import com.workbench.memory.domain.MemoryType;
import com.workbench.memory.domain.UserMemory;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 用户记忆仓储。
 *
 * <p>所有查询方法都带 {@code companyId + ownerUsername} 双条件：<b>隔离不是调用方自觉，而是方法签名强制的</b>——
 * 想按 id 取一条别人的记忆，就必须自己编一个 companyId/username 出来，写错一眼可见。</p>
 */
public interface UserMemoryRepository extends JpaRepository<UserMemory, Long> {

  /** 某单位某人的全部记忆（含已过期），按更新时间倒序：管理列表用。 */
  List<UserMemory> findByCompanyIdAndOwnerUsernameOrderByUpdatedAtDesc(
      Long companyId, String ownerUsername);

  /**
   * 某单位某人「未过期」的记忆，按更新时间倒序：检索注入用。
   *
   * <p>过期过滤必须下推到 SQL：若在应用层过滤，等于每次都把用不到的行读回内存。</p>
   */
  List<UserMemory> findByCompanyIdAndOwnerUsernameAndExpiresAtIsNullOrExpiresAtAfterOrderByUpdatedAtDesc(
      Long companyId, String ownerUsername, LocalDateTime now);

  /** 去重查找：同单位、同人、同类型下同一份内容的既有记忆。 */
  Optional<UserMemory> findByCompanyIdAndOwnerUsernameAndMemoryTypeAndContentHash(
      Long companyId, String ownerUsername, MemoryType memoryType, String contentHash);

  /** 按 id 取自己的记忆；不是自己的（或不存在）一律返回空，由调用方转 404。 */
  Optional<UserMemory> findByIdAndCompanyIdAndOwnerUsername(
      Long id, Long companyId, String ownerUsername);

  /** 当前用户已有多少条记忆：用于上限淘汰。 */
  long countByCompanyIdAndOwnerUsername(Long companyId, String ownerUsername);

  /** 按「重要度升序、更新时间升序」排列：淘汰时从队首（最不重要、最旧）开始。 */
  List<UserMemory> findByCompanyIdAndOwnerUsernameOrderByImportanceAscUpdatedAtAsc(
      Long companyId, String ownerUsername);
}
