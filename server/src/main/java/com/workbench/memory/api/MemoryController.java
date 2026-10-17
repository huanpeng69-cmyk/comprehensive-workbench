package com.workbench.memory.api;

import com.workbench.common.Result;
import com.workbench.memory.application.MemoryService;
import com.workbench.memory.dto.MemoryCreateRequest;
import com.workbench.memory.dto.MemoryRecallRequest;
import com.workbench.memory.dto.MemoryRecallResult;
import com.workbench.memory.dto.MemoryUpdateRequest;
import com.workbench.memory.dto.MemoryView;
import jakarta.validation.Valid;
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
 * 用户长期记忆接口：本人看本人的记忆，没有「管理员看别人记忆」的入口。
 *
 * <p>权限上不需要新角色：{@code SecurityConfig} 里 {@code anyRequest().authenticated()}，
 * 登录即可访问；归属强隔离由 {@code MemoryService} 按 (companyId, username) 在仓储层完成，
 * 因此本层不做数据范围判断，只做参数校验与结构转换。</p>
 */
@RestController
@RequestMapping("/api/memories")
public class MemoryController {

  private final MemoryService memoryService;

  public MemoryController(MemoryService memoryService) {
    this.memoryService = memoryService;
  }

  /** 我的记忆列表（含已过期项，由 {@code expired} 标注）。 */
  @GetMapping
  public Result<List<MemoryView>> list() {
    return Result.success(memoryService.list());
  }

  /** 新增一条记忆；内容重复时返回既有那条（幂等合并，重要度取较大值）。 */
  @PostMapping
  public Result<MemoryView> remember(@Valid @RequestBody MemoryCreateRequest request) {
    return Result.success(memoryService.remember(request));
  }

  /**
   * 修改我的一条记忆：正文、类型、重要度、有效期都可改。
   *
   * <p>PUT 而不是 PATCH：请求体是「这四个字段的目标状态」而不是一串局部补丁，
   * 前端因此总是把当前值回传（未改的字段传原值即无变化），语义比 PATCH 更好验证。</p>
   */
  @PutMapping("/{id}")
  public Result<MemoryView> update(
      @PathVariable Long id, @Valid @RequestBody MemoryUpdateRequest request) {
    return Result.success(memoryService.update(id, request));
  }

  /** 删除我的一条记忆；不存在或不属于当前用户都返回 404。 */
  @DeleteMapping("/{id}")
  public Result<Void> forget(@PathVariable Long id) {
    memoryService.forget(id);
    return Result.success();
  }

  /**
   * 召回预览：给定当前话题，返回要注入提示词的那一块记忆。
   *
   * <p>请求体可省略——前端「召回预览」面板什么都不传时按默认条数与预算执行，
   * 所以标成 {@code required = false} 而不是要求传空对象。</p>
   */
  @PostMapping("/recall")
  public Result<MemoryRecallResult> recall(
      @Valid @RequestBody(required = false) MemoryRecallRequest request) {
    MemoryRecallRequest effective = request == null
        ? new MemoryRecallRequest(null, null, null)
        : request;
    return Result.success(
        memoryService.recall(effective.query(), effective.topK(), effective.tokenBudget()));
  }
}
