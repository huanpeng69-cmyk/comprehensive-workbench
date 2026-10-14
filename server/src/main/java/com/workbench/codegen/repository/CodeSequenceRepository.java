package com.workbench.codegen.repository;

import com.workbench.codegen.domain.CodeSequence;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 文号序号仓储。
 *
 * <p>取号路径只用 {@link #findForUpdate(String, String)}：它带 {@link LockModeType#PESSIMISTIC_WRITE}
 * （PostgreSQL 上即 {@code SELECT ... FOR UPDATE}），把同一 (类别, 周期) 的并发取号串行化。
 * 这是「不重号不跳号」的唯一实现基础，不要改成普通查询。</p>
 */
public interface CodeSequenceRepository extends JpaRepository<CodeSequence, Long> {

  /**
   * 加行锁读取序号行。必须在事务内调用，锁在事务提交/回滚时释放。
   *
   * @return 该 (类别, 周期) 还没有发过号时返回 empty，由调用方新建行
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select s from CodeSequence s where s.category = :category and s.period = :period")
  Optional<CodeSequence> findForUpdate(@Param("category") String category,
      @Param("period") String period);
}
