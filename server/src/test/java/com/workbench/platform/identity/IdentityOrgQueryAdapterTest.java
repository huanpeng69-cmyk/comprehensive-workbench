package com.workbench.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.workbench.identity.domain.OrgUnit;
import com.workbench.identity.repository.OrgUnitRepository;
import com.workbench.platform.org.OrgUnitRef;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link IdentityOrgQueryAdapter} 单测：单位维度查询的「不猜单位」口径。
 *
 * <p>启动期种子数据（{@code DataInitializer} / 各 Seeder）依赖 {@code findFirstCompanyUnit()}
 * 在无登录态时确定归属单位，本测试锁住两条语义：只认 {@code type='COMPANY'} 取最小 id；
 * 库里没有单位时返回 empty（调用方据此跳过写库，而不是退化成不写 company_id）。</p>
 */
@ExtendWith(MockitoExtension.class)
class IdentityOrgQueryAdapterTest {

  private static final String COMPANY_NAME = "中国水利水电第六工程局有限公司南方公司";

  @Mock private OrgUnitRepository orgUnitRepository;

  @InjectMocks private IdentityOrgQueryAdapter adapter;

  @Test
  void findFirstCompanyUnitReturnsRefOfFirstCompany() {
    OrgUnit company = OrgUnit.builder().id(1L).name(COMPANY_NAME).type("COMPANY")
        .companyId(1L).enabled(true).build();
    when(orgUnitRepository.findFirstByTypeOrderByIdAsc("COMPANY")).thenReturn(Optional.of(company));

    assertThat(adapter.findFirstCompanyUnit())
        .contains(new OrgUnitRef(1L, COMPANY_NAME, 1L));
    verify(orgUnitRepository).findFirstByTypeOrderByIdAsc("COMPANY");
  }

  @Test
  void findFirstCompanyUnitIsEmptyWhenNoCompanyExists() {
    when(orgUnitRepository.findFirstByTypeOrderByIdAsc("COMPANY")).thenReturn(Optional.empty());

    assertThat(adapter.findFirstCompanyUnit()).isEmpty();
  }

  @Test
  void findFirstByNameAndCompanyIdRejectsBlankNameOrNullCompany() {
    assertThat(adapter.findFirstByNameAndCompanyId("  ", 1L)).isEmpty();
    assertThat(adapter.findFirstByNameAndCompanyId("云岭项目部", null)).isEmpty();
    verifyNoInteractions(orgUnitRepository);
  }

  @Test
  void findByIdIsEmptyForNullId() {
    assertThat(adapter.findById(null)).isEmpty();
    verifyNoInteractions(orgUnitRepository);
  }
}
