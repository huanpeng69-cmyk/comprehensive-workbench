package com.workbench.workflow.repository;

import com.workbench.workflow.domain.Task;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TaskRepository extends JpaRepository<Task, Long> {

  List<Task> findByInstanceIdOrderByNodeSeqAscCreatedAtAsc(Long instanceId);

  List<Task> findByInstanceIdAndNodeSeq(Long instanceId, Integer nodeSeq);

  /** 我的待办：名下未处理任务，且所属实例仍在进行中。 */
  @Query("""
      select t from Task t
      where t.assignee = :username and t.status = 'PENDING'
      and t.instanceId in (select i.id from ProcessInstance i where i.status = 'RUNNING')
      order by t.createdAt desc
      """)
  List<Task> findMyTodos(@Param("username") String username);
}
