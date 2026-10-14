package com.workbench.codegen.api;

import com.workbench.codegen.domain.CodeRule;
import com.workbench.codegen.repository.CodeRuleRepository;
import com.workbench.common.Result;
import com.workbench.platform.codegen.CodeGenPort;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 文号能力接口（阶段 3，ADR-M009）。
 *
 * <p>两个用途：</p>
 * <ol>
 *   <li>{@code POST /api/codes/{category}/next} —— 供业务模块与前端在提交前预取文号；</li>
 *   <li>{@code GET /api/codes/rules} —— 后台查看已配置的文号规则（只读，规则维护见后续批次）。</li>
 * </ol>
 *
 * <p><b>关于预取</b>：文号一经取出即消耗（不重号承诺）。前端「提交前预取」若最终未提交，
 * 该号会成为空号——这是「不跳号」与「不空号」之间的必然取舍，选择保住不重号。
 * 调用方应在真正落库前一刻取号，不要提前很久。</p>
 */
@RestController
@RequestMapping("/api/codes")
public class CodeGenController {

  private final CodeGenPort codeGenPort;
  private final CodeRuleRepository ruleRepository;

  public CodeGenController(CodeGenPort codeGenPort, CodeRuleRepository ruleRepository) {
    this.codeGenPort = codeGenPort;
    this.ruleRepository = ruleRepository;
  }

  /** 取一个文号。prefix 可省略（该类别的规则无前缀时）。 */
  @PostMapping("/{category}/next")
  public Result<String> next(@PathVariable String category,
      @RequestParam(required = false) String prefix) {
    return Result.success(codeGenPort.next(category, prefix));
  }

  /** 已配置的文号规则（只读）。 */
  @GetMapping("/rules")
  public Result<List<CodeRule>> rules() {
    return Result.success(ruleRepository.findAllByOrderByCategoryAsc());
  }
}
