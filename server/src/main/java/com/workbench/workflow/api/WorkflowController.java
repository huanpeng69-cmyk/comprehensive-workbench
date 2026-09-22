package com.workbench.workflow.api;

import com.workbench.common.Result;
import com.workbench.workflow.application.WorkflowService;
import com.workbench.workflow.application.dto.InstanceSummaryVo;
import com.workbench.workflow.application.dto.ProcessDefinitionVo;
import com.workbench.workflow.application.dto.ProcessInstanceVo;
import com.workbench.workflow.application.dto.StartProcessRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/workflow")
public class WorkflowController {

  private final WorkflowService workflowService;

  public WorkflowController(WorkflowService workflowService) {
    this.workflowService = workflowService;
  }

  @PostMapping("/instances")
  public Result<InstanceSummaryVo> start(@Valid @RequestBody StartProcessRequest request) {
    return Result.success(workflowService.start(request));
  }

  @GetMapping("/instances/{id}")
  public Result<ProcessInstanceVo> instanceDetail(@PathVariable Long id) {
    return Result.success(workflowService.instanceDetail(id));
  }

  @GetMapping("/instances/mine")
  public Result<List<InstanceSummaryVo>> myInstances() {
    return Result.success(workflowService.myInstances());
  }

  @GetMapping("/definitions")
  public Result<List<ProcessDefinitionVo>> listDefinitions() {
    return Result.success(workflowService.listDefinitions());
  }
}
