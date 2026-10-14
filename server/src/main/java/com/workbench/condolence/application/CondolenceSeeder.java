package com.workbench.condolence.application;

import com.workbench.condolence.domain.Condolence;
import com.workbench.condolence.repository.CondolenceRepository;
import com.workbench.platform.org.OrgQueryPort;
import com.workbench.platform.org.OrgUnitRef;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 演示数据：condolence 表为空时写入 3 条历史已发放慰问金（与原 mock 一致，供联调演示）。
 * 真实场景由工会慰问流程写入；新建申请走 /api/condolences 会实时发起流程。
 *
 * <p>阶段 2（ADR-M008）：行必须带归属单位。启动期没有登录态，锚定「第一家单位」
 * （口径与 {@code V10__multi_company.sql} 第 3 节回填一致：{@code type='COMPANY'} 取最小 id，
 * 禁止按公司名反查）；库里没有任何单位时跳过整段，不猜单位。表里已有数据（开发/生产库）时本类不执行。</p>
 */
@Component
public class CondolenceSeeder implements CommandLineRunner {

  private final CondolenceRepository condolenceRepository;
  private final OrgQueryPort orgQueryPort;

  public CondolenceSeeder(CondolenceRepository condolenceRepository, OrgQueryPort orgQueryPort) {
    this.condolenceRepository = condolenceRepository;
    this.orgQueryPort = orgQueryPort;
  }

  @Override
  @Transactional
  public void run(String... args) {
    if (condolenceRepository.count() > 0) {
      return;
    }
    Optional<OrgUnitRef> company = orgQueryPort.findFirstCompanyUnit();
    if (company.isEmpty()) {
      return;
    }
    Long companyId = company.get().id();
    LocalDateTime now = LocalDateTime.now();
    condolenceRepository.saveAll(List.of(
        rec(companyId, "周晓琳", "zhouxiaolin", "云岭项目部", 2L, "生育慰问", "2026年8月生育一子",
            "1000.00", "人民币壹仟元整", "同意", "同意", now.minusDays(20)),
        rec(companyId, "黄浩然", "huanghaoran", "云岭项目部", 2L, "困难帮扶", "家属重病住院",
            "2000.00", "人民币贰仟元整", "同意", "同意", now.minusDays(8)),
        rec(companyId, "林涛", "lintao", "榕荫项目部", 3L, "退休慰问", "光荣退休",
            "1500.00", "人民币壹仟伍佰元整", "同意", "同意", now.minusDays(25))
    ));
  }

  private Condolence rec(Long companyId, String applicant, String username, String project,
      Long projectId, String category, String reason, String amount, String inWords,
      String projOpinion, String companyOpinion, LocalDateTime appliedAt) {
    return Condolence.builder()
        .companyId(companyId)
        .applicant(applicant).applicantUsername(username)
        .project(project).projectId(projectId)
        .category(category).reason(reason)
        .amount(new BigDecimal(amount)).amountInWords(inWords)
        .projectUnionOpinion(projOpinion).companyUnionOpinion(companyOpinion)
        .status("已发放")
        .createdAt(appliedAt).updatedAt(appliedAt)
        .build();
  }
}
