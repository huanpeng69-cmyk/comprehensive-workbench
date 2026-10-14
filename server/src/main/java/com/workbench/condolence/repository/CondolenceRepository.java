package com.workbench.condolence.repository;

import com.workbench.condolence.domain.Condolence;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CondolenceRepository extends JpaRepository<Condolence, Long> {

  /** 本单位慰问金记录（阶段 2：COMPANY 数据范围的可见范围就是本单位，ADR-M008）。 */
  List<Condolence> findByCompanyIdOrderByCreatedAtDesc(Long companyId);

  /** 本单位内「我发起的」（阶段 2：用户名不再全局唯一，必须带单位维度）。 */
  List<Condolence> findByCompanyIdAndApplicantOrderByCreatedAtDesc(Long companyId, String applicant);

  List<Condolence> findByProjectIdOrderByCreatedAtDesc(Long projectId);
}
