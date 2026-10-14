package com.workbench.platform.attachment;

/**
 * 附件服务端口：平台内核向业务模块暴露的「统一文件与附件」能力（阶段 3 任务 2，ADR-M011）。
 *
 * <p>业务模块上传 / 查询附件一律走此端口，不得自己拼磁盘路径、也不得直连
 * {@code attachment} 的仓储——否则「公司隔离 + 下载审计 + 路径穿越防护」会散落各处。</p>
 *
 * <p><b>语义承诺</b>：</p>
 * <ul>
 *   <li>附件始终归属当前登录用户的公司（{@code companyId} 由端口从安全上下文取，调用方无需传）；</li>
 *   <li>读取 / 删除他人的公司附件被拒绝（应用层 {@code canAccessCompany} 把关）；</li>
 *   <li>{@code storagePath} 只存相对 storage-root 的路径，换盘只改 {@code WORKBENCH_FILE_ROOT} 配置。</li>
 * </ul>
 */
public interface AttachmentPort {

  /**
   * 查询某业务单据的附件列表（限当前用户所属公司）。
   *
   * @param bizType 业务模块，如 LEAVE / PAYROLL / RECEPTION
   * @param bizId   业务单据 id
   * @return 该单据的附件元数据列表（只含当前公司）
   */
  java.util.List<AttachmentMeta> listByBiz(String bizType, String bizId);

  /**
   * 取附件元信息（用于鉴权后再决定下载 / 删除）。
   *
   * @param id 附件 id
   * @return 元信息；不存在返回空
   */
  java.util.Optional<AttachmentMeta> getMeta(Long id);
}
