package com.workbench.workflow.repository;

import com.workbench.workflow.domain.Task;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TaskRepository extends JpaRepository<Task, Long> {

  List<Task> findByInstanceIdOrderByNodeSeqAscCreatedAtAsc(Long instanceId);

  List<Task> findByInstanceIdAndNodeSeq(Long instanceId, Integer nodeSeq);

  /** 取实例下首个待处理任务（业务模块按实例直接审批用，如请假内联批准）。 */
  java.util.Optional<Task> findFirstByInstanceIdAndStatusOrderByNodeSeqAscCreatedAtAsc(
      Long instanceId, String status);

  /**
   * 我的待办：名下未处理任务，且所属实例仍在进行中、且实例归属本单位
   * （阶段 2：用户名不再全局唯一，同名账号不得跨单位看到对方待办，ADR-M006）。
   */
  @Query("""
      select t from Task t
      where t.assignee = :username and t.status = 'PENDING'
      and t.instanceId in (select i.id from ProcessInstance i
          where i.status = 'RUNNING' and i.companyId = :companyId)
      order by t.createdAt desc
      """)
  List<Task> findMyTodos(@Param("username") String username, @Param("companyId") Long companyId);
}
