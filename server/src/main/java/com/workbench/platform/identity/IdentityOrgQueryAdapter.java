package com.workbench.platform.identity;

import com.workbench.identity.domain.OrgUnit;
import com.workbench.identity.repository.OrgUnitRepository;
import com.workbench.platform.org.OrgQueryPort;
import com.workbench.platform.org.OrgUnitRef;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * {@link OrgQueryPort} 的适配器：把 identity 的组织单元实体翻译成平台视图记录。
 *
 * <p>本包（{@code platform.identity}）是唯一允许依赖 {@code identity.repository} 的地方，
 * 由 {@code ModuleBoundaryTest} 用 ArchUnit 锁死。</p>
 */
@Component
public class IdentityOrgQueryAdapter implements OrgQueryPort {

  private final OrgUnitRepository orgUnitRepository;

  public IdentityOrgQueryAdapter(OrgUnitRepository orgUnitRepository) {
    this.orgUnitRepository = orgUnitRepository;
  }

  @Override
  public Optional<OrgUnitRef> findById(Long id) {
    if (id == null) {
      return Optional.empty();
    }
    return orgUnitRepository.findById(id).map(IdentityOrgQueryAdapter::toRef);
  }

  @Override
  public Optional<OrgUnitRef> findFirstByNameAndCompanyId(String name, Long companyId) {
    if (name == null || name.isBlank() || companyId == null) {
      return Optional.empty();
    }
    return orgUnitRepository.findFirstByNameAndCompanyId(name, companyId)
        .map(IdentityOrgQueryAdapter::toRef);
  }

  @Override
  public Optional<OrgUnitRef> findFirstCompanyUnit() {
    return orgUnitRepository.findFirstByTypeOrderByIdAsc("COMPANY")
        .map(IdentityOrgQueryAdapter::toRef);
  }

  private static OrgUnitRef toRef(OrgUnit unit) {
    return new OrgUnitRef(unit.getId(), unit.getName(), unit.getCompanyId());
  }
}
