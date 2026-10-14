package com.workbench.condolence.api;

import com.workbench.common.Result;
import com.workbench.condolence.application.CondolenceService;
import com.workbench.condolence.application.CondolenceVo;
import com.workbench.condolence.domain.Condolence;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 慰问金接口。所有读写均需登录；列表按角色数据范围在服务端过滤。
 * 提交(create) 会启动 condolence 流程；批准/驳回走真实工作流（仅被指派工会审批人可操作）。
 */
@RestController
@RequestMapping("/api/condolences")
public class CondolenceController {

  private final CondolenceService condolenceService;

  public CondolenceController(CondolenceService condolenceService) {
    this.condolenceService = condolenceService;
  }

  @GetMapping
  public Result<List<CondolenceVo>> list() {
    return Result.success(condolenceService.list());
  }

  @GetMapping("/mine")
  public Result<List<CondolenceVo>> myCondolences() {
    return Result.success(condolenceService.myCondolences());
  }

  @PostMapping
  public Result<CondolenceVo> create(@RequestBody Condolence condolence) {
    return Result.success(condolenceService.create(condolence));
  }

  @PostMapping("/{id}/approve")
  public Result<CondolenceVo> approve(@PathVariable Long id,
      @RequestParam(required = false) String comment) {
    return Result.success(condolenceService.approve(id, comment));
  }

  @PostMapping("/{id}/reject")
  public Result<CondolenceVo> reject(@PathVariable Long id,
      @RequestParam(required = false) String comment) {
    return Result.success(condolenceService.reject(id, comment));
  }
}
