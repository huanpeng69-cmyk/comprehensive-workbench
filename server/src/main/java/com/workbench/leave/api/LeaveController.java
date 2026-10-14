package com.workbench.leave.api;

import com.workbench.common.Result;
import com.workbench.leave.application.LeaveService;
import com.workbench.leave.application.dto.LeaveVo;
import com.workbench.leave.domain.Leave;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 请假接口。所有读写均需登录；列表按角色数据范围在服务端过滤。
 * 提交(create) 会启动 leave 流程；批准/驳回走真实工作流（仅被指派经理可操作）。
 */
@RestController
@RequestMapping("/api/leaves")
public class LeaveController {

  private final LeaveService leaveService;

  public LeaveController(LeaveService leaveService) {
    this.leaveService = leaveService;
  }

  @GetMapping
  public Result<List<LeaveVo>> list() {
    return Result.success(leaveService.list());
  }

  @GetMapping("/mine")
  public Result<List<LeaveVo>> myLeaves() {
    return Result.success(leaveService.myLeaves());
  }

  @PostMapping
  public Result<LeaveVo> create(@RequestBody Leave leave) {
    return Result.success(leaveService.create(leave));
  }

  @PutMapping("/{id}")
  public Result<LeaveVo> update(@PathVariable Long id, @RequestBody Leave leave) {
    return Result.success(leaveService.update(id, leave));
  }

  @DeleteMapping("/{id}")
  public Result<Void> remove(@PathVariable Long id) {
    leaveService.remove(id);
    return Result.success();
  }

  @PostMapping("/{id}/approve")
  public Result<LeaveVo> approve(@PathVariable Long id,
      @RequestParam(required = false) String comment) {
    return Result.success(leaveService.approve(id, comment));
  }

  @PostMapping("/{id}/reject")
  public Result<LeaveVo> reject(@PathVariable Long id,
      @RequestParam(required = false) String comment) {
    return Result.success(leaveService.reject(id, comment));
  }
}
