package com.workbench.attachment.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.workbench.attachment.domain.FileAttachment;
import com.workbench.attachment.repository.FileAttachmentRepository;
import com.workbench.platform.attachment.AttachmentMeta;
import com.workbench.audit.application.AuditService;
import com.workbench.platform.datascope.DataScopeResolver;
import com.workbench.testsupport.SecurityContextFixture;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

/**
 * 阶段 3 任务 2（ADR-M011）单元回归：附件落盘 / 元数据 / 公司隔离 / 路径穿越防护 / 审计。
 *
 * <p>纯 Mockito 单测：repository 与 audit 走 mock，磁盘根用 {@code @TempDir}，公司隔离用 mock 的
 * {@link DataScopeResolver}（同公司可访问、异公司拒绝）。验证不依赖 Spring 容器，也不依赖真实 PG。</p>
 */
class AttachmentServiceTest {

  private final FileAttachmentRepository repository = org.mockito.Mockito.mock(FileAttachmentRepository.class);
  private final DataScopeResolver dataScopeResolver = org.mockito.Mockito.mock(DataScopeResolver.class);
  private final AuditService auditService = org.mockito.Mockito.mock(AuditService.class);

  private AttachmentService service;

  @TempDir
  Path storageRoot;

  @BeforeEach
  void setUp() {
    service = new AttachmentService(repository, dataScopeResolver, auditService, storageRoot.toString());
    // 默认：当前用户属于公司 1，且可访问公司 1 的附件
    SecurityContextFixture.runAsInCompany("zhaoqiming", 1L, 1L, "PROJECT_LEADER");
    when(dataScopeResolver.canAccessCompany(1L)).thenReturn(true);
    when(dataScopeResolver.canAccessCompany(2L)).thenReturn(false);
    when(repository.save(any(FileAttachment.class))).thenAnswer(inv -> {
      FileAttachment saved = inv.getArgument(0);
      if (saved.getId() == null) {
        saved.setId(System.nanoTime());
      }
      return saved;
    });
  }

  @AfterEach
  void tearDown() {
    SecurityContextFixture.clear();
  }

  @Test
  void upload_writesFileToDiskAndStoresRelativePathWithCompanyDirectory() {
    MockMultipartFile file = new MockMultipartFile("file", "合同.pdf", "application/pdf",
        "hello-pdf".getBytes());

    AttachmentMeta meta = service.upload("LEAVE", "1001", file);

    // 落库元数据（DTO 不含路径，路径正确性通过捕获的落库实体核验）
    ArgumentCaptor<FileAttachment> captor = ArgumentCaptor.forClass(FileAttachment.class);
    verify(repository).save(captor.capture());
    FileAttachment saved = captor.getValue();

    // 落盘：storage-root 下、公司子目录 + UUID 名
    Path absolute = storageRoot.resolve(saved.getStoragePath());
    assertTrue(Files.exists(absolute), "文件必须落盘到相对路径");
    assertEquals("hello-pdf", new String(readAll(absolute)));
    // 元数据：company_id 来自安全上下文，storage_path 是相对路径（不含 storage-root 绝对路径）
    assertEquals(1L, saved.getCompanyId());
    assertEquals("合同.pdf", saved.getOriginalName());
    assertTrue(saved.getStoragePath().startsWith("1/"), "storage_path 必须带公司子目录");
    assertFalse(saved.getStoragePath().startsWith(storageRoot.toString()),
        "storage_path 绝不能存绝对路径");
    // 对外 DTO 不含路径、不含绝对路径，且company_id 正确透出
    assertEquals(1L, meta.companyId());
    assertEquals("合同.pdf", meta.originalName());
    verify(auditService).log(eq("zhaoqiming"), eq("FILE_UPLOAD"), eq("FILE"), any(), any());
  }

  @Test
  void download_ofOtherCompanyAttachment_isRejected() {
    FileAttachment other = FileAttachment.builder().id(77L).companyId(2L).bizType("LEAVE")
        .bizId("999").originalName("secret.pdf").storedName("x.pdf").storagePath("2/x.pdf")
        .contentType("application/pdf").sizeBytes(3).createdBy("other").build();
    when(repository.findById(77L)).thenReturn(Optional.of(other));

    // 异公司：canAccessCompany(2L) = false → 视为不存在（不泄露存在性）
    assertThrows(EntityNotFoundException.class, () -> service.download(77L));
    verify(auditService, never()).log(eq("zhaoqiming"), eq("FILE_DOWNLOAD"), any(), any(), any());
  }

  @Test
  void delete_removesFileAndMetadata() throws Exception {
    FileAttachment a = FileAttachment.builder().id(5L).companyId(1L).bizType("LEAVE")
        .bizId("1001").originalName("a.pdf").storedName("s.pdf").storagePath("1/s.pdf")
        .contentType("application/pdf").sizeBytes(3).createdBy("zhaoqiming").build();
    Path onDisk = storageRoot.resolve("1/s.pdf");
    Files.createDirectories(onDisk.getParent());
    Files.write(onDisk, "abc".getBytes());
    when(repository.findById(5L)).thenReturn(Optional.of(a));

    service.delete(5L);

    assertFalse(Files.exists(onDisk), "磁盘文件必须被删");
    verify(repository).delete(a);
    verify(auditService).log(eq("zhaoqiming"), eq("FILE_DELETE"), eq("FILE"), eq("5"), any());
  }

  @Test
  void pathTraversal_inStoredName_isRejectedBeforeAnyWrite() {
    // storage_path 被串改逃逸 root 时，resolveSafe 必须拦下（路径穿越防护是硬约束）
    FileAttachment trap = FileAttachment.builder().id(9L).companyId(1L).bizType("LEAVE")
        .bizId("x").originalName("ok.pdf").storedName("ok.pdf")
        .storagePath("../escape.pdf").contentType("application/pdf").sizeBytes(1)
        .createdBy("zhaoqiming").build();
    when(repository.findById(9L)).thenReturn(Optional.of(trap));

    assertThrows(IllegalArgumentException.class, () -> service.download(9L),
        "路径穿越必须被拦下");
  }

  private static byte[] readAll(Path p) {
    try {
      return Files.readAllBytes(p);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
