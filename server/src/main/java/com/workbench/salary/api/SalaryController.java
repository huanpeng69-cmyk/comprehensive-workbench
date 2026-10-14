package com.workbench.salary.api;

import com.workbench.common.Result;
import com.workbench.salary.application.SalaryService;
import com.workbench.salary.application.dto.MonthUpsertRequest;
import com.workbench.salary.application.dto.MonthVo;
import com.workbench.salary.domain.PerfRecord;
import com.workbench.salary.domain.SalaryRecord;
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
 * 工资制作接口。整个模块为敏感数据：仅 COMPANY 数据范围角色可访问，
 * 由 SalaryService 在每个入口强制校验并审计。
 */
@RestController
@RequestMapping("/api/salaries")
public class SalaryController {

  private final SalaryService salaryService;

  public SalaryController(SalaryService salaryService) {
    this.salaryService = salaryService;
  }

  @GetMapping("/months")
  public Result<List<MonthVo>> listMonths() {
    return Result.success(salaryService.listMonths());
  }

  /** 整月覆盖式保存（Excel 导入整体替换）。 */
  @PutMapping("/months/{month}")
  public Result<MonthVo> upsertMonth(@PathVariable String month,
      @RequestBody MonthUpsertRequest request) {
    return Result.success(salaryService.upsertMonth(month, request));
  }

  @DeleteMapping("/months/{month}")
  public Result<Void> deleteMonth(@PathVariable String month) {
    salaryService.deleteMonth(month);
    return Result.success();
  }

  @PutMapping("/months/{month}/records/{recordId}")
  public Result<SalaryRecord> updateRecord(@PathVariable String month,
      @PathVariable String recordId, @RequestBody SalaryRecord record) {
    return Result.success(salaryService.updateRecord(month, recordId, record));
  }

  @PutMapping("/months/{month}/perf/{recordId}")
  public Result<PerfRecord> updatePerf(@PathVariable String month,
      @PathVariable String recordId, @RequestBody PerfRecord record) {
    return Result.success(salaryService.updatePerf(month, recordId, record));
  }

  /** 新增单条工资明细（对齐花名册「新增人员」）。 */
  @PostMapping("/months/{month}/records")
  public Result<SalaryRecord> createRecord(@PathVariable String month,
      @RequestBody SalaryRecord record) {
    return Result.success(salaryService.createRecord(month, record));
  }

  /** 删除单条工资明细（对齐花名册「删除此行」）。 */
  @DeleteMapping("/months/{month}/records/{recordId}")
  public Result<Void> deleteRecord(@PathVariable String month,
      @PathVariable String recordId) {
    salaryService.deleteRecord(month, recordId);
    return Result.success();
  }

  @PostMapping("/months/{month}/perf")
  public Result<PerfRecord> createPerf(@PathVariable String month,
      @RequestBody PerfRecord record) {
    return Result.success(salaryService.createPerf(month, record));
  }

  @DeleteMapping("/months/{month}/perf/{recordId}")
  public Result<Void> deletePerf(@PathVariable String month,
      @PathVariable String recordId) {
    salaryService.deletePerf(month, recordId);
    return Result.success();
  }

  @GetMapping("/config")
  public Result<Object> getConfig() {
    return Result.success(salaryService.getConfig());
  }

  @PutMapping("/config")
  public Result<Object> saveConfig(@RequestBody Object data) {
    return Result.success(salaryService.saveConfig(data));
  }
}
