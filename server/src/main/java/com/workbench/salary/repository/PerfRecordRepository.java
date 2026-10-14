package com.workbench.salary.repository;

import com.workbench.salary.domain.PerfRecord;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PerfRecordRepository extends JpaRepository<PerfRecord, String> {

  /** 本单位某月绩效明细，按序号升序。 */
  List<PerfRecord> findByCompanyIdAndMonthOrderBySeqAscIdAsc(Long companyId, String month);

  /** 本单位内的某条绩效明细。 */
  Optional<PerfRecord> findByCompanyIdAndMonthAndId(Long companyId, String month, String id);
}
