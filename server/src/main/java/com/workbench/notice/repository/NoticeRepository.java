package com.workbench.notice.repository;

import com.workbench.notice.domain.Notice;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NoticeRepository extends JpaRepository<Notice, Long> {

  /** 本单位公告，按发布时间倒序（阶段 2：公告只在本单位内可见，ADR-M008）。 */
  List<Notice> findByCompanyIdOrderByPublishedAtDesc(Long companyId);
}
