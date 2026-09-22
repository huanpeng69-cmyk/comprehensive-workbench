package com.workbench.workflow.repository;

import com.workbench.workflow.domain.ProcessInstance;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessInstanceRepository extends JpaRepository<ProcessInstance, Long> {

  List<ProcessInstance> findByInitiatorOrderByCreatedAtDesc(String initiator);
}
