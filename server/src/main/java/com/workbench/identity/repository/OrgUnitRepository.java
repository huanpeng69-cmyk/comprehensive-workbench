package com.workbench.identity.repository;

import com.workbench.identity.domain.OrgUnit;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrgUnitRepository extends JpaRepository<OrgUnit, Long> {

  List<OrgUnit> findByTypeOrderBySortNo(String type);

  Optional<OrgUnit> findFirstByNameAndCompanyId(String name, Long companyId);

  /** 取某家公司下的指定类型单位（按 sort_no、id），供启动期种子数据定位演示账号所属项目部。 */
  List<OrgUnit> findByTypeAndCompanyIdOrderBySortNoAscIdAsc(String type, Long companyId);

  /** 取第一家单位（type='COMPANY' 中 id 最小），供启动期种子数据确定归属单位。 */
  Optional<OrgUnit> findFirstByTypeOrderByIdAsc(String type);
}
