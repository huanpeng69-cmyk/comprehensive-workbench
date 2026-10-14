package com.workbench.attachment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 附件元数据（阶段 3 任务 2，ADR-M011）。
 *
 * <p>文件本体落本地磁盘，本表只存元数据。<b>关键约束</b>：</p>
 * <ul>
 *   <li>{@code companyId} NOT NULL——文件归属单位，与 11 张公司维度表同口径（ADR-M002 软隔离），
 *       跨公司访问由应用层 {@code canAccessCompany} 拒绝；</li>
 *   <li>{@code storagePath} 只存<b>相对</b>于 storage-root 的路径（如 {@code 1/uuid.pdf}），
 *       绝不存绝对路径——换盘 / 容器化时只改 {@code WORKBENCH_FILE_ROOT} 配置，不动数据；</li>
 *   <li>{@code originalName} 仅展示用，<b>不参与</b>落盘路径，避免路径穿越（用 {@code storedName} 落盘，
 *       后者是 UUID + 原始扩展名，不含用户输入）。</li>
 * </ul>
 */
@Entity
@Table(name = "file_attachment")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FileAttachment {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "company_id", nullable = false)
  private Long companyId;

  /** 关联业务模块，如 LEAVE / PAYROLL / RECEPTION。 */
  @Column(name = "biz_type", nullable = false, length = 32)
  private String bizType;

  /** 关联业务单据 id（字符串，兼容各模块主键形态）。 */
  @Column(name = "biz_id", nullable = false, length = 64)
  private String bizId;

  /** 用户上传时的原始文件名，仅展示用，不参与路径。 */
  @Column(name = "original_name", nullable = false, length = 255)
  private String originalName;

  /** 落盘文件名：UUID + 原始扩展名（无扩展名则无后缀）。 */
  @Column(name = "stored_name", nullable = false, length = 128)
  private String storedName;

  /** 相对 storage-root 的路径，如 1/uuid.pdf。 */
  @Column(name = "storage_path", nullable = false, length = 512)
  private String storagePath;

  @Column(name = "content_type", nullable = false, length = 128)
  private String contentType;

  @Column(name = "size_bytes", nullable = false)
  private long sizeBytes;

  /** 上传人登录名，用于审计与「我的上传」。 */
  @Column(name = "created_by", nullable = false, length = 64)
  private String createdBy;

  @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
  private LocalDateTime createdAt;
}
