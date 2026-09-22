package com.workbench.kb.api;

import com.workbench.audit.application.AuditService;
import com.workbench.common.Result;
import com.workbench.kb.application.RegulationService;
import java.io.IOException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 制度正文接口：需登录。正文不再放前端 public 目录，避免匿名下载内部制度原文。
 */
@RestController
@RequestMapping("/api/kb/regulations")
public class RegulationsController {

  private final RegulationService regulationService;
  private final AuditService auditService;

  public RegulationsController(RegulationService regulationService, AuditService auditService) {
    this.regulationService = regulationService;
    this.auditService = auditService;
  }

  @GetMapping("/{docNo}")
  public Result<String> content(@PathVariable String docNo) throws IOException {
    String text = regulationService.read(docNo);
    auditService.log("VIEW_REGULATION", "REGULATION", docNo, "查阅制度正文");
    return Result.success(text);
  }
}
