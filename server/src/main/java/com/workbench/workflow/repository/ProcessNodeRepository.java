package com.workbench.workflow.repository;

import com.workbench.workflow.domain.ProcessNode;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessNodeRepository extends JpaRepository<ProcessNode, Long> {

  List<ProcessNode> findByDefinitionIdOrderBySeq(Long definitionId);
}
