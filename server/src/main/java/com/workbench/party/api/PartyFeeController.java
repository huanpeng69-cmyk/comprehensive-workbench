package com.workbench.party.api;

import com.workbench.common.Result;
import com.workbench.party.application.PartyFeeService;
import com.workbench.party.domain.PartyFee;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 党费季度明细接口（只读，当前 UI 无编辑功能）。
 * 敏感数据闸门在 PartyFeeService：仅全公司数据范围角色可查询，拒绝即写审计。
 */
@RestController
@RequestMapping("/api/party-fees")
public class PartyFeeController {

  private final PartyFeeService partyFeeService;

  public PartyFeeController(PartyFeeService partyFeeService) {
    this.partyFeeService = partyFeeService;
  }

  @GetMapping
  public Result<List<PartyFee>> list(
      @RequestParam(name = "quarter", required = false) String quarter) {
    return Result.success(partyFeeService.list(quarter));
  }
}
