package com.workbench.workflow.application;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 业务完结回调注册表：流程实例完结（COMPLETED / REJECTED）时，按 bizType 通知对应业务模块
 * 同步自身状态（如把 leave_record 置为已批准/已驳回）。
 *
 * <p>用途：解耦「通用流程引擎」与「具体业务」。WorkflowService 只负责流转，不认识 leave/salary；
 * 各业务模块在构造时 register(bizType, handler)，引擎完结时回调 handler.handle(bizId, status)。
 * 后续 payroll / reception 模块可照同一模式接入，无需改动引擎。</p>
 */
@Component
public class BizCompletionRegistry {

  private final Map<String, BizCompletionHandler> handlers = new ConcurrentHashMap<>();

  /** 注册某业务类型的完结处理（一般在业务 Service 构造时调用）。 */
  public void register(String bizType, BizCompletionHandler handler) {
    handlers.put(bizType, handler);
  }

  /** 流程实例完结时由引擎调用。status 为 COMPLETED / REJECTED。 */
  public void complete(String bizType, Long bizId, String status) {
    BizCompletionHandler handler = handlers.get(bizType);
    if (handler != null && bizId != null) {
      handler.handle(bizId, status);
    }
  }

  /** 业务侧回调：bizId 即发起时传入的业务主键（如 leave_record.id）。 */
  public interface BizCompletionHandler {
    void handle(Long bizId, String status);
  }
}
