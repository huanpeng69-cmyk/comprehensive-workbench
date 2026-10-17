package com.workbench.memory.dto;

import com.workbench.memory.domain.UserMemory;
import java.time.LocalDateTime;

/**
 * 记忆视图：返回给前端与管理接口的形态。
 *
 * @param id          记忆 id
 * @param type        类型枚举名（EPISODIC / SEMANTIC / PROCEDURAL）
 * @param typeLabel   类型中文名
 * @param content     记忆正文
 * @param importance  重要度 0..100
 * @param source      来源枚举名（MANUAL / AGENT）
 * @param sourceLabel 来源中文名
 * @param createdAt   创建时间
 * @param updatedAt   更新时间（同时是检索打分的「新鲜度」依据）
 * @param expiresAt   过期时间，可为 null（永不过期）
 * @param expired     相对当前时刻是否已过期（过期记忆仍在列表里可见，只是不再参与检索）
 */
public record MemoryView(
    Long id,
    String type,
    String typeLabel,
    String content,
    int importance,
    String source,
    String sourceLabel,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    LocalDateTime expiresAt,
    boolean expired) {

  /** 由实体转视图；{@code now} 用于判断是否已过期。 */
  public static MemoryView of(UserMemory memory, LocalDateTime now) {
    return new MemoryView(
        memory.getId(),
        memory.getMemoryType() == null ? null : memory.getMemoryType().name(),
        memory.getMemoryType() == null ? null : memory.getMemoryType().label(),
        memory.getContent(),
        memory.getImportance() == null ? 0 : memory.getImportance(),
        memory.getSource() == null ? null : memory.getSource().name(),
        memory.getSource() == null ? null : memory.getSource().label(),
        memory.getCreatedAt(),
        memory.getUpdatedAt(),
        memory.getExpiresAt(),
        memory.isExpiredAt(now));
  }
}
