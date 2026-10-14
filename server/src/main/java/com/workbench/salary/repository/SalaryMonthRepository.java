package com.workbench.salary.repository;

import com.workbench.salary.domain.SalaryMonth;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SalaryMonthRepository extends JpaRepository<SalaryMonth, Long> {

  /** 本单位某月工资月表（阶段 2：同一个月不同单位各存一份，ADR-M008）。 */
  Optional<SalaryMonth> findByCompanyIdAndMonth(Long companyId, String month);

  /** 本单位全部工资月表，按月倒序。 */
  List<SalaryMonth> findByCompanyIdOrderByMonthDesc(Long companyId);
}
