package com.workbench.attachment.api;

import com.workbench.attachment.application.AttachmentService;
import com.workbench.attachment.application.AttachmentService.DownloadedFile;
import com.workbench.platform.attachment.AttachmentMeta;
import com.workbench.common.Result;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 附件能力接口（阶段 3 任务 2，ADR-M011）。
 *
 * <p>所有接口都要求登录；公司级隔离在 {@link AttachmentService} 内由 {@code canAccessCompany} 把关，
 * 跨公司访问统一返回 404（不暴露「目标存在但无权限」）。</p>
 *
 * <p>路径：</p>
 * <ul>
 *   <li>{@code POST /api/attachments?bizType=&bizId=} 上传（multipart/file）；</li>
 *   <li>{@code GET  /api/attachments?bizType=&bizId=} 某单据附件列表；</li>
 *   <li>{@code GET  /api/attachments/{id}/content} 下载（流式，带 Content-Disposition）；</li>
 *   <li>{@code DELETE /api/attachments/{id}} 删除。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/attachments")
@PreAuthorize("isAuthenticated()")
public class AttachmentController {

  private final AttachmentService attachmentService;

  @Autowired
  public AttachmentController(AttachmentService attachmentService) {
    this.attachmentService = attachmentService;
  }

  @PostMapping
  public Result<AttachmentMeta> upload(@RequestParam String bizType, @RequestParam String bizId,
      @RequestParam("file") MultipartFile file) {
    return Result.success(attachmentService.upload(bizType, bizId, file));
  }

  @GetMapping
  public Result<List<AttachmentMeta>> list(@RequestParam String bizType,
      @RequestParam String bizId) {
    return Result.success(attachmentService.listByBiz(bizType, bizId));
  }

  @GetMapping("/{id}/content")
  public ResponseEntity<org.springframework.core.io.Resource> content(@PathVariable Long id) {
    DownloadedFile downloaded = attachmentService.download(id);
    try {
      org.springframework.core.io.Resource resource =
          new org.springframework.core.io.FileSystemResource(downloaded.absolutePath().toFile());
      return ResponseEntity.ok()
          .contentType(MediaType.parseMediaType(downloaded.meta().getContentType()))
          .header(HttpHeaders.CONTENT_DISPOSITION,
              "inline; filename=\"" + safeFileName(downloaded.meta().getOriginalName()) + "\"")
          .body(resource);
    } catch (Exception e) {
      throw new IllegalStateException("附件读取失败: " + id, e);
    }
  }

  @DeleteMapping("/{id}")
  public Result<Void> delete(@PathVariable Long id) {
    attachmentService.delete(id);
    return Result.success(null);
  }

  /** Content-Disposition 文件名只接受 ASCII，避免头部注入 / 解析问题；非 ASCII 一律落空。 */
  private static String safeFileName(String name) {
    if (name == null) {
      return "";
    }
    boolean allAscii = name.chars().allMatch(c -> c >= 0x20 && c < 0x7f);
    return allAscii ? name : "";
  }
}
