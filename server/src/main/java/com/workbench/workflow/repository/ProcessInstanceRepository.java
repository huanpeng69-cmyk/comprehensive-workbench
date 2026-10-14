package com.workbench.workflow.repository;

import com.workbench.workflow.domain.ProcessInstance;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessInstanceRepository extends JpaRepository<ProcessInstance, Long> {

  /** 本单位内「我发起的」流程（阶段 2：用户名只在单位内唯一，必须带单位维度，ADR-M006）。 */
  List<ProcessInstance> findByCompanyIdAndInitiatorOrderByCreatedAtDesc(Long companyId,
      String initiator);
}
