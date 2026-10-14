package com.workbench.party.application;

import com.workbench.party.domain.PartyFee;
import com.workbench.party.repository.PartyFeeRepository;
import com.workbench.platform.org.OrgQueryPort;
import com.workbench.platform.org.OrgUnitRef;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 演示数据：party_fee 表为空时写入 2026-Q3 六名党员缴费明细（与原 mock 一致，供联调演示）。
 * 真实场景应由党费收缴流程写入。
 *
 * <p>阶段 2（ADR-M008）：党费明细按归属单位隔离，行必须带 company_id。启动期没有登录态，
 * 锚定「第一家单位」（口径与 {@code V10__multi_company.sql} 第 3 节回填一致：{@code type='COMPANY'}
 * 取最小 id，禁止按公司名反查）；库里没有任何单位时跳过整段。表里已有数据（开发/生产库）时本类不执行。</p>
 */
@Component
public class PartyFeeSeeder implements CommandLineRunner {

  private final PartyFeeRepository partyFeeRepository;
  private final OrgQueryPort orgQueryPort;

  public PartyFeeSeeder(PartyFeeRepository partyFeeRepository, OrgQueryPort orgQueryPort) {
    this.partyFeeRepository = partyFeeRepository;
    this.orgQueryPort = orgQueryPort;
  }

  @Override
  @Transactional
  public void run(String... args) {
    if (partyFeeRepository.count() > 0) {
      return;
    }
    Optional<OrgUnitRef> company = orgQueryPort.findFirstCompanyUnit();
    if (company.isEmpty()) {
      return;
    }
    Long companyId = company.get().id();
    partyFeeRepository.saveAll(List.of(
        fee(companyId, "陈国栋", "2917.72", "14.59", "2786.88", "13.93", "2841.88", "14.21"),
        fee(companyId, "李明远", "2311.57", "11.56", "2193.88", "10.97", "2248.88", "11.24"),
        fee(companyId, "王海涛", "2311.98", "11.56", "2191.88", "10.96", "2246.88", "11.23"),
        fee(companyId, "赵启明", "3857.26", "38.57", "4516.94", "45.17", "3055.94", "30.56"),
        fee(companyId, "韩雪松", "4443.04", "44.43", "3698.72", "36.99", "2987.72", "14.94"),
        fee(companyId, "曹磊", "3465.81", "34.66", "3820.64", "38.21", "1820.64", "9.10")
    ));
  }

  private PartyFee fee(Long companyId, String name, String b1, String f1, String b2, String f2,
      String b3, String f3) {
    BigDecimal total = new BigDecimal(f1).add(new BigDecimal(f2)).add(new BigDecimal(f3));
    return PartyFee.builder()
        .companyId(companyId)
        .name(name).quarter("2026-Q3")
        .base1(new BigDecimal(b1)).fee1(new BigDecimal(f1))
        .base2(new BigDecimal(b2)).fee2(new BigDecimal(f2))
        .base3(new BigDecimal(b3)).fee3(new BigDecimal(f3))
        .total(total)
        .build();
  }
}
