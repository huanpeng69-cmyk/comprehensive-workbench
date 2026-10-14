package com.workbench.salary.application.dto;

import com.workbench.salary.domain.PerfRecord;
import com.workbench.salary.domain.SalaryRecord;
import java.time.LocalDateTime;
import java.util.List;

/** 一个月的完整工资制作数据（对齐前端 SalaryMonthData）。 */
public record MonthVo(
    String month,
    LocalDateTime importedAt,
    List<SalaryRecord> records,
    List<PerfRecord> perf) {
}
