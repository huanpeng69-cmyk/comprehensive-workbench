package com.workbench.salary.application.dto;

import com.workbench.salary.domain.PerfRecord;
import com.workbench.salary.domain.SalaryRecord;
import java.util.List;

/** 整月覆盖式保存请求（Excel 导入整体替换）。 */
public record MonthUpsertRequest(
    List<SalaryRecord> records,
    List<PerfRecord> perf) {
}
