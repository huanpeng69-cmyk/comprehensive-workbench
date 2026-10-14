package com.workbench.notice.api;

import com.workbench.common.Result;
import com.workbench.notice.domain.Notice;
import com.workbench.notice.repository.NoticeRepository;
import com.workbench.platform.datascope.DataScopeResolver;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 通知公告接口（只读）。完整 CRUD 见后续批次；此处仅满足首页「最新公告 / 公告阅读率」聚合需求。
 *
 * <p>可见范围（阶段 2，ADR-M008）：公告随所属单位隔离，只返回当前登录单位的公告；
 * 登录态未携带所属单位时返回空列表，绝不跨单位泄露。</p>
 */
@RestController
@RequestMapping("/api/notices")
public class NoticeController {

  private final NoticeRepository noticeRepository;
  private final DataScopeResolver dataScopeResolver;

  public NoticeController(NoticeRepository noticeRepository, DataScopeResolver dataScopeResolver) {
    this.noticeRepository = noticeRepository;
    this.dataScopeResolver = dataScopeResolver;
  }

  @GetMapping
  public Result<List<Notice>> list() {
    Long companyId = dataScopeResolver.currentCompanyId();
    if (companyId == null) {
      return Result.success(List.of());
    }
    return Result.success(noticeRepository.findByCompanyIdOrderByPublishedAtDesc(companyId));
  }
}
