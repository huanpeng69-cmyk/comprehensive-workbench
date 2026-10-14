package com.workbench.identity.api;

import com.workbench.common.Result;
import com.workbench.identity.domain.OrgUnit;
import com.workbench.identity.repository.OrgUnitRepository;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 组织机构只读接口。首页项目部下拉、各业务模块项目部筛选共用。
 * 返回启用中的组织单元（disabled 不展示），按 type 可选过滤；不做数据范围过滤（组织树为公开配置）。
 */
@RestController
@RequestMapping("/api/org")
public class OrgUnitController {

  private final OrgUnitRepository orgUnitRepository;

  public OrgUnitController(OrgUnitRepository orgUnitRepository) {
    this.orgUnitRepository = orgUnitRepository;
  }

  @GetMapping("/units")
  public Result<List<OrgUnit>> listUnits(
      @RequestParam(name = "type", required = false) String type) {
    List<OrgUnit> units = (type == null || type.isBlank())
        ? orgUnitRepository.findAll()
        : orgUnitRepository.findByTypeOrderBySortNo(type);
    List<OrgUnit> enabled = units.stream()
        .filter(u -> Boolean.TRUE.equals(u.getEnabled()))
        .toList();
    return Result.success(enabled);
  }
}
