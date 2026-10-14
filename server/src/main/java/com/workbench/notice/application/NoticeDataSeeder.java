package com.workbench.notice.application;

import com.workbench.notice.domain.Notice;
import com.workbench.notice.repository.NoticeRepository;
import com.workbench.platform.org.OrgQueryPort;
import com.workbench.platform.org.OrgUnitRef;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 演示数据：notice 表为空时写入若干公告，供首页聚合展示（与 DataInitializer 同生命周期）。
 * 阅读率为演示值，真实场景应由阅读行为回写 read_count / total_count。
 *
 * <p>阶段 2（ADR-M008）：公告按归属单位隔离，行必须带 company_id。启动期没有登录态，
 * 锚定「第一家单位」（口径与 {@code V10__multi_company.sql} 第 3 节回填一致：{@code type='COMPANY'}
 * 取最小 id，禁止按公司名反查）；库里没有任何单位时跳过整段。表里已有数据（开发/生产库）时本类不执行。</p>
 */
@Component
public class NoticeDataSeeder implements CommandLineRunner {

  private final NoticeRepository noticeRepository;
  private final OrgQueryPort orgQueryPort;

  public NoticeDataSeeder(NoticeRepository noticeRepository, OrgQueryPort orgQueryPort) {
    this.noticeRepository = noticeRepository;
    this.orgQueryPort = orgQueryPort;
  }

  @Override
  @Transactional
  public void run(String... args) {
    if (noticeRepository.count() > 0) {
      return;
    }
    Optional<OrgUnitRef> company = orgQueryPort.findFirstCompanyUnit();
    if (company.isEmpty()) {
      return;
    }
    Long companyId = company.get().id();
    noticeRepository.saveAll(List.of(
        Notice.builder().companyId(companyId)
            .title("关于开展2026年中秋国庆期间安全生产检查的通知")
            .category("通知").scope("各项目部、各部门").publishedAt("2026-09-15 09:30")
            .readCount(42).totalCount(56).urgent(true)
            .content("中秋国庆双节临近，各项目部须于节前完成一次全覆盖安全生产大检查……").build(),
        Notice.builder().companyId(companyId)
            .title("关于印发《南方公司出差旅费报销管理办法（2025版）》的宣贯通知")
            .category("制度宣贯").scope("各项目部、各部门").publishedAt("2026-09-12 14:10")
            .readCount(38).totalCount(56).urgent(false)
            .content("新版差旅费报销管理办法已印发，请各部门组织宣贯并严格执行……").build(),
        Notice.builder().companyId(companyId)
            .title("关于组织2026年第三季度工会活动的通知")
            .category("工会活动").scope("全体会员").publishedAt("2026-09-10 16:00")
            .readCount(51).totalCount(56).urgent(false)
            .content("第三季度工会活动定于本月下旬开展，欢迎广大工会会员积极参与……").build(),
        Notice.builder().companyId(companyId)
            .title("云岭项目部周例会会议纪要（2026年第36期）")
            .category("会议纪要").scope("项目部各部门").publishedAt("2026-09-08 18:20")
            .readCount(29).totalCount(45).urgent(false)
            .content("本周例会通报了进度、安全及质量情况，纪要如下……").build(),
        Notice.builder().companyId(companyId)
            .title("关于2026年防暑费发放的温馨提示（6-10月）")
            .category("温馨提示").scope("全体员工").publishedAt("2026-06-05 10:00")
            .readCount(55).totalCount(56).urgent(false)
            .content("6月至10月为本年度防暑降温期，防暑费将随工资一并发放，请注意查收……").build()
    ));
  }
}
