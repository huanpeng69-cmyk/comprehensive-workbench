package com.workbench.attachment.repository;

import com.workbench.attachment.domain.FileAttachment;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** 附件元数据仓储（阶段 3 任务 2，ADR-M011）。 */
public interface FileAttachmentRepository extends JpaRepository<FileAttachment, Long> {

  /** 按业务单据取附件列表（同公司内）。 */
  List<FileAttachment> findByBizTypeAndBizIdOrderByCreatedAtDesc(String bizType, String bizId);

  /** 按 id 取附件，并顺带带出 companyId（供鉴权判断），避免两查。 */
  List<FileAttachment> findByCompanyId(Long companyId);
}
