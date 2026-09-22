package com.workbench.identity.repository;

import com.workbench.identity.domain.OrgUnit;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrgUnitRepository extends JpaRepository<OrgUnit, Long> {

  List<OrgUnit> findByTypeOrderBySortNo(String type);
}
