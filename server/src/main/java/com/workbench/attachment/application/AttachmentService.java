package com.workbench.attachment.application;

import com.workbench.attachment.domain.FileAttachment;
import com.workbench.attachment.repository.FileAttachmentRepository;
import com.workbench.platform.attachment.AttachmentMeta;
import com.workbench.audit.application.AuditService;
import com.workbench.platform.attachment.AttachmentPort;
import com.workbench.platform.datascope.DataScopeResolver;
import com.workbench.security.CurrentUserHolder;
import jakarta.persistence.EntityNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * 附件服务实现（阶段 3 任务 2，ADR-M011）。
 *
 * <p><b>隔离与防护</b>：</p>
 * <ul>
 *   <li>附件归属当前登录用户的公司（{@code companyId} 从安全上下文取，调用方不传），
 *       写入路径一律带 {@code company_id}；</li>
 *   <li>读取 / 删除别人的公司附件被拒（{@link DataScopeResolver#canAccessCompany}）；</li>
 *   <li>落盘文件名是 {@code UUID + 原始扩展名}，不含用户输入；{@code storagePath} 只存相对路径，
 *       下载时由 storage-root 拼出绝对路径并做「仍在 root 下」校验，防止路径穿越；</li>
 *   <li>下载落审计（FILE_DOWNLOAD），上传落审计（FILE_UPLOAD）。</li>
 * </ul>
 *
 * <p><b>已知边界</b>：本服务不校验扩展名白名单 / 病毒（本期范围外）；文件大小上限交给 Spring 的
 * {@code multipart.max-file-size} 配置。路径穿越防护是硬约束，不因范围外而省略。</p>
 */
@Service
public class AttachmentService implements AttachmentPort {

  private final FileAttachmentRepository repository;
  private final DataScopeResolver dataScopeResolver;
  private final AuditService auditService;
  private final Path storageRoot;

  public AttachmentService(FileAttachmentRepository repository,
      DataScopeResolver dataScopeResolver, AuditService auditService,
      @Value("${WORKBENCH_FILE_ROOT:./uploads}") String storageRoot) {
    this.repository = repository;
    this.dataScopeResolver = dataScopeResolver;
    this.auditService = auditService;
    this.storageRoot = Paths.get(storageRoot).toAbsolutePath().normalize();
  }

  /** 上传一个附件，落盘 + 写元数据，返回元信息。 */
  @Transactional
  public AttachmentMeta upload(String bizType, String bizId, MultipartFile file) {
    Long companyId = requireCompanyId();
    String username = CurrentUserHolder.currentUsername();

    String originalName = file.getOriginalFilename() == null ? "未命名" : file.getOriginalFilename();
    String ext = extensionOf(originalName);
    String storedName = UUID.randomUUID() + ext;
    Path relative = storageRoot.resolve(String.valueOf(companyId)).resolve(storedName);
    Path absolute = relative.normalize();
    assertInsideRoot(absolute);

    try {
      Files.createDirectories(absolute.getParent());
      file.transferTo(absolute.toFile());
    } catch (IOException e) {
      throw new IllegalStateException("附件落盘失败: " + absolute, e);
    }

    FileAttachment saved = repository.save(FileAttachment.builder()
        .companyId(companyId)
        .bizType(bizType)
        .bizId(bizId)
        .originalName(originalName)
        .storedName(storedName)
        .storagePath(storageRoot.relativize(absolute).toString().replace("\\", "/"))
        .contentType(file.getContentType() == null ? "application/octet-stream" : file.getContentType())
        .sizeBytes(file.getSize())
        .createdBy(username == null ? "anonymous" : username)
        .build());

    auditService.log(requireUsername(), "FILE_UPLOAD", "FILE", String.valueOf(saved.getId()),
        "上传附件「" + originalName + "」到 " + bizType + "/" + bizId);
    return toMeta(saved);
  }

  @Override
  @Transactional(readOnly = true)
  public List<AttachmentMeta> listByBiz(String bizType, String bizId) {
    requireCompanyId();
    return repository.findByBizTypeAndBizIdOrderByCreatedAtDesc(bizType, bizId).stream()
        .filter(a -> dataScopeResolver.canAccessCompany(a.getCompanyId()))
        .map(this::toMeta)
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<AttachmentMeta> getMeta(Long id) {
    return repository.findById(id)
        .filter(a -> dataScopeResolver.canAccessCompany(a.getCompanyId()))
        .map(this::toMeta);
  }

  /** 取附件用于下载：鉴权 + 路径校验 + 落审计，返回绝对路径与元信息。 */
  @Transactional(readOnly = true)
  public DownloadedFile download(Long id) {
    FileAttachment a = loadOwned(id);
    Path absolute = resolveSafe(a.getStoragePath());
    if (!Files.exists(absolute)) {
      throw new IllegalStateException("附件文件缺失: " + a.getStoragePath());
    }
    auditService.log(requireUsername(), "FILE_DOWNLOAD", "FILE", String.valueOf(id),
        "下载附件「" + a.getOriginalName() + "」");
    return new DownloadedFile(a, absolute);
  }

  /** 删除附件：元数据 + 磁盘文件一起删（先鉴权）。 */
  @Transactional
  public void delete(Long id) {
    FileAttachment a = loadOwned(id);
    Path absolute = resolveSafe(a.getStoragePath());
    try {
      Files.deleteIfExists(absolute);
    } catch (IOException e) {
      throw new IllegalStateException("附件磁盘文件删除失败: " + absolute, e);
    }
    repository.delete(a);
    auditService.log(requireUsername(), "FILE_DELETE", "FILE", String.valueOf(id),
        "删除附件「" + a.getOriginalName() + "」");
  }

  /** 下载结果：元信息 + 绝对路径，由控制器流式返回。 */
  public record DownloadedFile(FileAttachment meta, Path absolutePath) {
  }

  // --------------------------------------------------------------------------

  private FileAttachment loadOwned(Long id) {
    FileAttachment a = repository.findById(id)
        .orElseThrow(() -> new EntityNotFoundException("附件不存在: " + id));
    if (!dataScopeResolver.canAccessCompany(a.getCompanyId())) {
      throw new EntityNotFoundException("附件不存在或无权访问: " + id);
    }
    return a;
  }

  private Path resolveSafe(String relativePath) {
    Path absolute = storageRoot.resolve(relativePath).normalize();
    assertInsideRoot(absolute);
    return absolute;
  }

  private void assertInsideRoot(Path absolute) {
    if (!absolute.startsWith(storageRoot)) {
      throw new IllegalArgumentException("非法附件路径（疑似路径穿越）: " + absolute);
    }
  }

  private static String extensionOf(String originalName) {
    int dot = originalName.lastIndexOf('.');
    if (dot > 0 && dot < originalName.length() - 1) {
      String ext = originalName.substring(dot).toLowerCase(java.util.Locale.ROOT);
      // 只放行常见扩展名形态，过长/含特殊字符的视为无扩展名
      if (ext.matches("\\.[a-z0-9]{1,10}")) {
        return ext;
      }
    }
    return "";
  }

  private Long requireCompanyId() {
    Long companyId = CurrentUserHolder.currentCompanyId();
    if (companyId == null) {
      throw new IllegalStateException("无法确定数据归属单位（token 缺少 companyId）");
    }
    return companyId;
  }

  /** 业务实体 → 平台内核契约 DTO（平台 DTO 不引用业务实体，避免架构违规）。 */
  private AttachmentMeta toMeta(FileAttachment a) {
    return new AttachmentMeta(a.getId(), a.getCompanyId(), a.getBizType(), a.getBizId(),
        a.getOriginalName(), a.getContentType(), a.getSizeBytes(), a.getCreatedBy(),
        a.getCreatedAt());
  }

  private String requireUsername() {
    String username = CurrentUserHolder.currentUsername();
    return username == null ? "anonymous" : username;
  }
}
