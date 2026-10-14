package com.workbench.ai.api;

import com.workbench.ai.application.AiConfigService;
import com.workbench.ai.application.AiConfigService.AiConnectionCheck;
import com.workbench.ai.dto.AiConfigSaveRequest;
import com.workbench.ai.dto.AiConfigTestRequest;
import com.workbench.ai.dto.AiConfigView;
import com.workbench.common.Result;
import com.workbench.security.CurrentUserHolder;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 写作台模型设置。
 * 读取：任意登录可见（只包含脱敏后的地址/模型名与「是否已配置密钥」）；
 * 写入：SYSTEM_ADMIN 或 HR_ADMIN。HR 是实际维护模型配置的人，放开它可以避免每次换模型
 * 都要 SYSTEM_ADMIN 代填；普通员工仍然只读，密钥不会被随手改掉。前端不做角色判断，
 * 只读 view() 返回的 editable 字段（后端是唯一权威来源，不会出现两边口径漂移）。
 */
@RestController
@RequestMapping("/api/ai/writing")
public class AiConfigController {

  private final AiConfigService configService;

  public AiConfigController(AiConfigService configService) {
    this.configService = configService;
  }

  @GetMapping("/config")
  public Result<AiConfigView> config() {
    return Result.success(configService.view());
  }

  @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'HR_ADMIN')")
  @PutMapping("/config")
  public Result<AiConfigView> save(@RequestBody AiConfigSaveRequest request) {
    configService.save(request.baseUrl(), request.model(), request.apiKey(), CurrentUserHolder.currentUsername());
    return Result.success(configService.view());
  }

  /**
   * 测试连接：拿候选配置真实发一次极简生成请求，返回通不通 + 人话原因 + 耗时。
   * 权限与 save 一致（只有能改配置的角色才值得去试探上游），且只探测不落库。
   */
  @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'HR_ADMIN')")
  @PostMapping("/config/test")
  public Result<AiConnectionCheck> test(@RequestBody(required = false) AiConfigTestRequest request) {
    return Result.success(configService.testConnection(request));
  }
}
