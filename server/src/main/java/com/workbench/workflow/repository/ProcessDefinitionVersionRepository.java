package com.workbench.workflow.repository;

import com.workbench.workflow.domain.ProcessDefinitionVersion;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 流程定义版本快照仓储。 */
public interface ProcessDefinitionVersionRepository
    extends JpaRepository<ProcessDefinitionVersion, Long> {

  /** 全部版本，新→旧（后台版本列表）。 */
  List<ProcessDefinitionVersion> findByDefinitionIdOrderByVersionDesc(Long definitionId);

  /** 取指定版本（回滚目标）。 */
  Optional<ProcessDefinitionVersion> findByDefinitionIdAndVersion(Long definitionId, Integer version);

  /** 当前最大版本号；从未发布过返回 null（由调用方按 1 起算）。 */
  Integer findFirstByDefinitionIdOrderByVersionDesc(Long definitionId);
}
