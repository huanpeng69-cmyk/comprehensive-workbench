package com.workbench.workflow.repository;

import com.workbench.workflow.domain.ProcessDefinition;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessDefinitionRepository extends JpaRepository<ProcessDefinition, Long> {

  /** 按业务类型取最新启用版本。 */
  Optional<ProcessDefinition> findFirstByBizTypeAndEnabledTrueOrderByVersionDesc(String bizType);

  List<ProcessDefinition> findByEnabledTrueOrderByIdAsc();
}
