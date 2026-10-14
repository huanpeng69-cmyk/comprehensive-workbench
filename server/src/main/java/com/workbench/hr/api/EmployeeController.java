package com.workbench.hr.api;

import com.workbench.common.Result;
import com.workbench.hr.application.EmployeeService;
import com.workbench.hr.domain.Employee;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 人员花名册接口。所有读写均需登录；列表按角色数据范围在服务端过滤。
 */
@RestController
@RequestMapping("/api/employees")
public class EmployeeController {

  private final EmployeeService employeeService;

  public EmployeeController(EmployeeService employeeService) {
    this.employeeService = employeeService;
  }

  @GetMapping
  public Result<List<Employee>> list() {
    return Result.success(employeeService.list());
  }

  @PostMapping
  public Result<Employee> create(@RequestBody Employee employee) {
    return Result.success(employeeService.create(employee));
  }

  @PutMapping("/{id}")
  public Result<Employee> update(@PathVariable Long id, @RequestBody Employee employee) {
    return Result.success(employeeService.update(id, employee));
  }

  @DeleteMapping("/{id}")
  public Result<Void> delete(@PathVariable Long id) {
    employeeService.delete(id);
    return Result.success();
  }

  /** 花名册整体导入（覆盖式），body 为 Excel 解析后的记录数组。 */
  @PostMapping("/import")
  public Result<Integer> importReplace(@RequestBody List<Employee> members) {
    return Result.success(employeeService.importReplace(members));
  }
}
