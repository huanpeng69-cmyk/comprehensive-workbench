package com.workbench.party.repository;

import com.workbench.party.domain.PartyFee;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PartyFeeRepository extends JpaRepository<PartyFee, Long> {

  /** 本单位党费明细（阶段 2：党费按单位隔离，ADR-M008）。 */
  List<PartyFee> findByCompanyIdOrderByIdAsc(Long companyId);

  /** 本单位内按季度过滤。 */
  List<PartyFee> findByCompanyIdAndQuarterOrderByIdAsc(Long companyId, String quarter);
}
