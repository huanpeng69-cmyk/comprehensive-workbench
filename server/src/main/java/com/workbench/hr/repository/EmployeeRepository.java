package com.workbench.hr.repository;

import com.workbench.hr.domain.Employee;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmployeeRepository extends JpaRepository<Employee, Long> {

  /** 本单位花名册（阶段 2：COMPANY 数据范围的可见范围就是本单位，ADR-M008）。 */
  List<Employee> findAllByCompanyIdOrderBySeqAscIdAsc(Long companyId);

  List<Employee> findByProjectIdOrderBySeqAscIdAsc(Long projectId);

  /** 覆盖式导入前按单位清空（阶段 2：导入只覆盖本单位，其它单位数据不动）。 */
  @Modifying
  @Query("delete from Employee e where e.companyId = :companyId")
  int deleteByCompanyId(@Param("companyId") Long companyId);
}
