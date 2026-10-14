package com.workbench.salary.repository;

import com.workbench.salary.domain.SalaryRecord;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SalaryRecordRepository extends JpaRepository<SalaryRecord, String> {

  /** 本单位某月工资明细，按序号升序。 */
  List<SalaryRecord> findByCompanyIdAndMonthOrderBySeqAscIdAsc(Long companyId, String month);

  /** 本单位内的某条工资明细。 */
  Optional<SalaryRecord> findByCompanyIdAndMonthAndId(Long companyId, String month, String id);
}
