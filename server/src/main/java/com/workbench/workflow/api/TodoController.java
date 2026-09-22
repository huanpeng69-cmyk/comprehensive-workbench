package com.workbench.workflow.api;

import com.workbench.common.Result;
import com.workbench.workflow.application.WorkflowService;
import com.workbench.workflow.application.dto.TaskActionRequest;
import com.workbench.workflow.application.dto.TaskVo;
import com.workbench.workflow.application.dto.TransmitRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/todos")
public class TodoController {

  private final WorkflowService workflowService;

  public TodoController(WorkflowService workflowService) {
    this.workflowService = workflowService;
  }

  @GetMapping("/mine")
  public Result<List<TaskVo>> myTodos() {
    return Result.success(workflowService.myTodos());
  }

  @PostMapping("/{taskId}/approve")
  public Result<Void> approve(@PathVariable Long taskId,
      @RequestBody(required = false) TaskActionRequest request) {
    workflowService.approve(taskId, request != null ? request : new TaskActionRequest(null));
    return Result.success();
  }

  @PostMapping("/{taskId}/reject")
  public Result<Void> reject(@PathVariable Long taskId,
      @RequestBody(required = false) TaskActionRequest request) {
    workflowService.reject(taskId, request != null ? request : new TaskActionRequest(null));
    return Result.success();
  }

  @PostMapping("/{taskId}/transmit")
  public Result<Void> transmit(@PathVariable Long taskId, @Valid @RequestBody TransmitRequest request) {
    workflowService.transmit(taskId, request);
    return Result.success();
  }
}
