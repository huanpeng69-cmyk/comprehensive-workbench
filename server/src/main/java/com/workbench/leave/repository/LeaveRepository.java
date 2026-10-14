package com.workbench.leave.repository;

import com.workbench.leave.domain.Leave;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LeaveRepository extends JpaRepository<Leave, Long> {

  /** 本单位请假记录（阶段 2：COMPANY 数据范围的可见范围就是本单位，ADR-M008）。 */
  List<Leave> findByCompanyIdOrderByCreatedAtDesc(Long companyId);

  /** 本单位内「我发起的」（阶段 2：用户名不再全局唯一，必须带单位维度）。 */
  List<Leave> findByCompanyIdAndApplicantOrderByCreatedAtDesc(Long companyId, String applicant);

  List<Leave> findByProjectIdOrderByCreatedAtDesc(Long projectId);

  Optional<Leave> findByWorkflowInstanceId(Long workflowInstanceId);
}
