package com.workbench.platform.attachment;

import java.time.LocalDateTime;

/**
 * 附件元信息（跨层契约，由平台内核定义，业务模块只依赖它）。
 *
 * <p>不含磁盘绝对路径——路径由服务内部组合，防止泄露存储根（ADR-M011）。
 * 本类刻意只持有数据，不引用任何业务实体，避免在平台内核出现「平台反向依赖业务模块」的架构违规。</p>
 */
public record AttachmentMeta(
    Long id,
    Long companyId,
    String bizType,
    String bizId,
    String originalName,
    String contentType,
    long sizeBytes,
    String createdBy,
    LocalDateTime createdAt) {
}
