package com.workbench.memory.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.workbench.audit.application.AuditService;
import com.workbench.memory.domain.MemorySource;
import com.workbench.memory.domain.MemoryType;
import com.workbench.memory.domain.UserMemory;
import com.workbench.memory.dto.MemoryCreateRequest;
import com.workbench.memory.dto.MemoryRecallResult;
import com.workbench.memory.dto.MemoryUpdateRequest;
import com.workbench.memory.dto.MemoryView;
import com.workbench.memory.repository.UserMemoryRepository;
import com.workbench.testsupport.SecurityContextFixture;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import jakarta.persistence.EntityNotFoundException;

/**
 * 用户记忆服务回归：归属隔离、去重合并、修改（含改后撞车报错）、超限淘汰、空归属降级。
 *
 * <p>本测试用<b>真实</b>的 {@link MemoryContextBuilder}（纯计算、无副作用、输出确定），
 * 只 mock 仓储与审计——这样召回链路的「排序 + 压缩」语义也被锁在测试里，
 * 而不是被 mock 掉。</p>
 */
class MemoryServiceTest {

  private static final long COMPANY_ID = 4L;
  private static final String USER = "alice";

  private final UserMemoryRepository repository = mock(UserMemoryRepository.class);
  private final AuditService auditService = mock(AuditService.class);
  private final MemoryService service =
      new MemoryService(repository, new MemoryContextBuilder(), auditService);

  @AfterEach
  void tearDown() {
    SecurityContextFixture.clear();
  }

  private static UserMemory existingMemory(Long id, String content, int importance) {
    LocalDateTime now = LocalDateTime.now().minusDays(1);
    return UserMemory.builder()
        .id(id)
        .companyId(COMPANY_ID)
        .ownerUsername(USER)
        .memoryType(MemoryType.SEMANTIC)
        .content(content)
        .contentHash(MemoryText.contentHash(content))
        .importance(importance)
        .source(MemorySource.MANUAL)
        .createdAt(now)
        .updatedAt(now)
        .build();
  }

  // ---------- 写入：归属校验 ----------

  @Test
  void rememberWithoutCompanyIdIsRejectedAndAudited() {
    SecurityContextFixture.runAs(USER, null, "STAFF");

    IllegalStateException ex = assertThrows(IllegalStateException.class,
        () -> service.remember("喜欢表格", MemoryType.SEMANTIC, 50, null, MemorySource.MANUAL));

    assertTrue(ex.getMessage().contains("归属单位"));
    verify(auditService).log(eq(USER), eq("ACCESS_DENIED"), eq("USER_MEMORY"), eq("-"), anyString());
    verifyNoInteractions(repository);
  }

  @Test
  void rememberWithoutAnyLoginStateIsRejected() {
    SecurityContextFixture.clear();

    assertThrows(IllegalStateException.class,
        () -> service.remember("喜欢表格", MemoryType.SEMANTIC, 50, null, MemorySource.MANUAL));

    verify(auditService).log(isNull(), eq("ACCESS_DENIED"), eq("USER_MEMORY"), eq("-"), anyString());
    verifyNoInteractions(repository);
  }

  @Test
  void blankContentIsRejected() {
    SecurityContextFixture.runAsInCompany(USER, COMPANY_ID, null, "STAFF");

    assertThrows(IllegalArgumentException.class,
        () -> service.remember("   ", MemoryType.SEMANTIC, 50, null, MemorySource.MANUAL));
    verifyNoInteractions(repository);
  }

  @Test
  void overLengthContentIsRejected() {
    SecurityContextFixture.runAsInCompany(USER, COMPANY_ID, null, "STAFF");
    String tooLong = "记".repeat(MemoryService.MAX_CONTENT_LENGTH + 1);

    assertThrows(IllegalArgumentException.class,
        () -> service.remember(tooLong, MemoryType.SEMANTIC, 50, null, MemorySource.MANUAL));
    verifyNoInteractions(repository);
  }

  @Test
  void unknownTypeIsRejectedInsteadOfSilentlyFallingBack() {
    SecurityContextFixture.runAsInCompany(USER, COMPANY_ID, null, "STAFF");

    assertThrows(IllegalArgumentException.class,
        () -> service.remember(new MemoryCreateRequest("随便写点", "NOT_A_TYPE", null, null)));
    verifyNoInteractions(repository);
  }

  // ---------- 写入：落库内容 ----------

  @Test
  void rememberStoresUnderCurrentCompanyAndUser() {
    SecurityContextFixture.runAsInCompany(USER, COMPANY_ID, null, "STAFF");
    when(repository.save(any(UserMemory.class))).thenAnswer(invocation -> invocation.getArgument(0));
    when(repository.countByCompanyIdAndOwnerUsername(anyLong(), anyString())).thenReturn(0L);

    MemoryView view = service.remember("写材料要先出提纲", MemoryType.PROCEDURAL, 70, 30, MemorySource.AGENT);

    ArgumentCaptor<UserMemory> captor = ArgumentCaptor.forClass(UserMemory.class);
    verify(repository).save(captor.capture());
    UserMemory saved = captor.getValue();
    assertEquals(COMPANY_ID, saved.getCompanyId());
    assertEquals(USER, saved.getOwnerUsername());
    assertEquals(MemoryType.PROCEDURAL, saved.getMemoryType());
    assertEquals(MemorySource.AGENT, saved.getSource());
    assertEquals(70, saved.getImportance());
    assertEquals("写材料要先出提纲", saved.getContent());
    assertEquals(MemoryText.contentHash("写材料要先出提纲"), saved.getContentHash());
    assertNotNull(saved.getCreatedAt());
    assertNotNull(saved.getUpdatedAt());
    assertNotNull(saved.getExpiresAt());
    assertEquals("PROCEDURAL", view.type());
    assertEquals("程序记忆", view.typeLabel());
    assertFalse(view.expired());
    verify(auditService).log(eq(USER), eq("MEMORY_CREATE"), eq("USER_MEMORY"), anyString(), anyString());
  }

  @Test
  void manualRequestDefaultsToSemanticAndNoExpiry() {
    SecurityContextFixture.runAsInCompany(USER, COMPANY_ID, null, "STAFF");
    when(repository.save(any(UserMemory.class))).thenAnswer(invocation -> invocation.getArgument(0));

    service.remember(new MemoryCreateRequest("我在综合办工作", null, null, null));

    ArgumentCaptor<UserMemory> captor = ArgumentCaptor.forClass(UserMemory.class);
    verify(repository).save(captor.capture());
    UserMemory saved = captor.getValue();
    assertEquals(MemoryType.SEMANTIC, saved.getMemoryType());
    assertEquals(MemorySource.MANUAL, saved.getSource());
    assertEquals(MemoryService.DEFAULT_IMPORTANCE, saved.getImportance());
    assertEquals(null, saved.getExpiresAt());
  }

  // ---------- 写入：去重合并 ----------

  @Test
  void duplicateContentMergesInsteadOfInsertingAgain() {
    SecurityContextFixture.runAsInCompany(USER, COMPANY_ID, null, "STAFF");
    UserMemory existing = existingMemory(7L, "我不要套话开场", 80);
    when(repository.findByCompanyIdAndOwnerUsernameAndMemoryTypeAndContentHash(
        COMPANY_ID, USER, MemoryType.SEMANTIC, MemoryText.contentHash("我不要套话开场")))
        .thenReturn(Optional.of(existing));
    when(repository.save(any(UserMemory.class))).thenAnswer(invocation -> invocation.getArgument(0));

    service.remember("  我不要套话开场  ", MemoryType.SEMANTIC, 30, null, MemorySource.MANUAL);

    ArgumentCaptor<UserMemory> captor = ArgumentCaptor.forClass(UserMemory.class);
    verify(repository).save(captor.capture());
    assertEquals(7L, captor.getValue().getId());
    assertEquals(80, captor.getValue().getImportance());
    verify(auditService).log(eq(USER), eq("MEMORY_MERGE"), eq("USER_MEMORY"), eq("7"), anyString());
  }

  @Test
  void duplicateContentKeepsContentAndHashStable() {
    SecurityContextFixture.runAsInCompany(USER, COMPANY_ID, null, "STAFF");
    UserMemory existing = existingMemory(9L, "汇报要带数据", 20);
    when(repository.findByCompanyIdAndOwnerUsernameAndMemoryTypeAndContentHash(
        COMPANY_ID, USER, MemoryType.SEMANTIC, MemoryText.contentHash("汇报要带数据")))
        .thenReturn(Optional.of(existing));
    when(repository.save(any(UserMemory.class))).thenAnswer(invocation -> invocation.getArgument(0));

    service.remember("汇报要带数据", MemoryType.SEMANTIC, 90, null, MemorySource.MANUAL);

    assertEquals("汇报要带数据", existing.getContent());
    assertEquals(90, existing.getImportance());
    assertEquals(1, existing.getId() == null ? 0 : 1);
  }

  // ---------- 写入：修改 ----------

  @Test
  void updateWithoutCompanyIdIsRejectedAndAudited() {
    SecurityContextFixture.runAs(USER, null, "STAFF");

    assertThrows(IllegalStateException.class,
        () -> service.update(5L, new MemoryUpdateRequest("改一下正文", null, null, null)));

    verify(auditService).log(eq(USER), eq("ACCESS_DENIED"), eq("USER_MEMORY"), eq("-"), anyString());
    verifyNoInteractions(repository);
  }

  @Test
  void updateOfAnotherUsersMemoryIsNotFoundAndWritesNothing() {
    SecurityContextFixture.runAsInCompany(USER, COMPANY_ID, null, "STAFF");

    assertThrows(EntityNotFoundException.class,
        () -> service.update(99L, new MemoryUpdateRequest("改一下正文", null, null, null)));

    verify(repository, never()).save(any(UserMemory.class));
    verify(auditService, never())
        .log(anyString(), eq("MEMORY_UPDATE"), anyString(), anyString(), anyString());
  }

  @Test
  void updateWithNullIdOrNullBodyIsRejectedBeforeTouchingRepository() {
    SecurityContextFixture.runAsInCompany(USER, COMPANY_ID, null, "STAFF");

    assertThrows(IllegalArgumentException.class,
        () -> service.update(null, new MemoryUpdateRequest("改一下正文", null, null, null)));
    assertThrows(IllegalArgumentException.class, () -> service.update(5L, null));

    verifyNoInteractions(repository);
  }

  @Test
  void updateRewritesContentTypeImportanceAndRecomputesHash() {
    SecurityContextFixture.runAsInCompany(USER, COMPANY_ID, null, "STAFF");
    UserMemory mine = existingMemory(5L, "旧的正文", 30);
    when(repository.findByIdAndCompanyIdAndOwnerUsername(5L, COMPANY_ID, USER))
        .thenReturn(Optional.of(mine));
    when(repository.findByCompanyIdAndOwnerUsernameAndMemoryTypeAndContentHash(
        COMPANY_ID, USER, MemoryType.PROCEDURAL, MemoryText.contentHash("新的正文")))
        .thenReturn(Optional.empty());
    when(repository.save(any(UserMemory.class))).thenAnswer(invocation -> invocation.getArgument(0));

    MemoryView view = service.update(5L, new MemoryUpdateRequest("新的正文", "PROCEDURAL", 90, null));

    assertEquals(5L, view.id());
    assertEquals("新的正文", mine.getContent());
    assertEquals(MemoryText.contentHash("新的正文"), mine.getContentHash(),
        "改正文必须重算指纹，否则内容与去重索引不再一致");
    assertEquals(MemoryType.PROCEDURAL, mine.getMemoryType());
    assertEquals(90, mine.getImportance());
    assertEquals("程序记忆", view.typeLabel());
    verify(auditService).log(eq(USER), eq("MEMORY_UPDATE"), eq("USER_MEMORY"), eq("5"), anyString());
  }

  @Test
  void updateKeepsFieldsLeftNullAndRefreshesUpdatedAt() {
    SecurityContextFixture.runAsInCompany(USER, COMPANY_ID, null, "STAFF");
    UserMemory mine = existingMemory(6L, "保持不变", 70);
    LocalDateTime before = mine.getUpdatedAt();
    when(repository.findByIdAndCompanyIdAndOwnerUsername(6L, COMPANY_ID, USER))
        .thenReturn(Optional.of(mine));
    when(repository.findByCompanyIdAndOwnerUsernameAndMemoryTypeAndContentHash(
        COMPANY_ID, USER, MemoryType.SEMANTIC, MemoryText.contentHash("保持不变")))
        .thenReturn(Optional.empty());
    when(repository.save(any(UserMemory.class))).thenAnswer(invocation -> invocation.getArgument(0));

    service.update(6L, new MemoryUpdateRequest("保持不变", null, null, null));

    assertEquals(MemoryType.SEMANTIC, mine.getMemoryType(), "类型传 null 应保持原类型");
    assertEquals(70, mine.getImportance(), "重要度传 null 应保持原值，而不是回落到默认 50");
    assertEquals(null, mine.getExpiresAt(), "有效期传 null 应保持原值（原本无有效期则仍无）");
    assertTrue(mine.getUpdatedAt().isAfter(before), "改过就要刷新 updatedAt，它同时是新鲜度依据");
  }

  @Test
  void updateWithZeroTtlDaysClearsExpiry() {
    SecurityContextFixture.runAsInCompany(USER, COMPANY_ID, null, "STAFF");
    UserMemory mine = existingMemory(7L, "本来有有效期", 50);
    mine.setExpiresAt(LocalDateTime.now().plusDays(10));
    when(repository.findByIdAndCompanyIdAndOwnerUsername(7L, COMPANY_ID, USER))
        .thenReturn(Optional.of(mine));
    when(repository.findByCompanyIdAndOwnerUsernameAndMemoryTypeAndContentHash(
        COMPANY_ID, USER, MemoryType.SEMANTIC, MemoryText.contentHash("本来有有效期")))
        .thenReturn(Optional.empty());
    when(repository.save(any(UserMemory.class))).thenAnswer(invocation -> invocation.getArgument(0));

    service.update(7L, new MemoryUpdateRequest("本来有有效期", null, null, 0));

    assertEquals(null, mine.getExpiresAt(), "ttlDays=0 是「清除有效期」的唯一表达方式");
  }

  @Test
  void updateWithPositiveTtlDaysCountsFromNow() {
    SecurityContextFixture.runAsInCompany(USER, COMPANY_ID, null, "STAFF");
    UserMemory mine = existingMemory(8L, "现在要有有效期了", 50);
    mine.setExpiresAt(LocalDateTime.now().plusDays(1));
    when(repository.findByIdAndCompanyIdAndOwnerUsername(8L, COMPANY_ID, USER))
        .thenReturn(Optional.of(mine));
    when(repository.findByCompanyIdAndOwnerUsernameAndMemoryTypeAndContentHash(
        COMPANY_ID, USER, MemoryType.SEMANTIC, MemoryText.contentHash("现在要有有效期了")))
        .thenReturn(Optional.empty());
    when(repository.save(any(UserMemory.class))).thenAnswer(invocation -> invocation.getArgument(0));

    LocalDateTime floor = LocalDateTime.now().plusDays(29);

    service.update(8L, new MemoryUpdateRequest("现在要有有效期了", null, null, 30));

    assertNotNull(mine.getExpiresAt());
    assertTrue(mine.getExpiresAt().isAfter(floor),
        "新有效期从「现在」起算，不是在原到期时间上再叠加 30 天");
  }

  @Test
  void updateRejectsContentThatDuplicatesAnotherMemory() {
    SecurityContextFixture.runAsInCompany(USER, COMPANY_ID, null, "STAFF");
    UserMemory mine = existingMemory(9L, "我这条要改成别人的内容", 50);
    UserMemory other = existingMemory(10L, "另一条已存在的内容", 50);
    when(repository.findByIdAndCompanyIdAndOwnerUsername(9L, COMPANY_ID, USER))
        .thenReturn(Optional.of(mine));
    when(repository.findByCompanyIdAndOwnerUsernameAndMemoryTypeAndContentHash(
        COMPANY_ID, USER, MemoryType.SEMANTIC, MemoryText.contentHash("另一条已存在的内容")))
        .thenReturn(Optional.of(other));

    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
        () -> service.update(9L, new MemoryUpdateRequest("另一条已存在的内容", null, null, null)));

    assertTrue(ex.getMessage().contains("已存在内容相同"));
    assertEquals("另一条已存在的内容", other.getContent(), "撞车时不许改动被撞到的那条");
    verify(repository, never()).save(any(UserMemory.class));
    verify(repository, never()).delete(any(UserMemory.class));
  }

  @Test
  void updateHittingItsOwnMemoryIsNotAConflict() {
    SecurityContextFixture.runAsInCompany(USER, COMPANY_ID, null, "STAFF");
    UserMemory mine = existingMemory(11L, "只改重要度", 30);
    when(repository.findByIdAndCompanyIdAndOwnerUsername(11L, COMPANY_ID, USER))
        .thenReturn(Optional.of(mine));
    when(repository.findByCompanyIdAndOwnerUsernameAndMemoryTypeAndContentHash(
        COMPANY_ID, USER, MemoryType.SEMANTIC, MemoryText.contentHash("只改重要度")))
        .thenReturn(Optional.of(mine));
    when(repository.save(any(UserMemory.class))).thenAnswer(invocation -> invocation.getArgument(0));

    service.update(11L, new MemoryUpdateRequest("只改重要度", null, 95, null));

    assertEquals(95, mine.getImportance());
    verify(repository).save(mine);
  }

  @Test
  void updateWithBlankContentIsRejectedBeforeSaving() {
    SecurityContextFixture.runAsInCompany(USER, COMPANY_ID, null, "STAFF");
    when(repository.findByIdAndCompanyIdAndOwnerUsername(12L, COMPANY_ID, USER))
        .thenReturn(Optional.of(existingMemory(12L, "原有内容", 50)));

    assertThrows(IllegalArgumentException.class,
        () -> service.update(12L, new MemoryUpdateRequest("   ", null, null, null)));

    verify(repository, never()).save(any(UserMemory.class));
  }

  @Test
  void updateWithUnknownTypeIsRejected() {
    SecurityContextFixture.runAsInCompany(USER, COMPANY_ID, null, "STAFF");
    when(repository.findByIdAndCompanyIdAndOwnerUsername(13L, COMPANY_ID, USER))
        .thenReturn(Optional.of(existingMemory(13L, "原有内容", 50)));

    assertThrows(IllegalArgumentException.class,
        () -> service.update(13L, new MemoryUpdateRequest("原有内容", "NOT_A_TYPE", null, null)));

    verify(repository, never()).save(any(UserMemory.class));
  }

  // ---------- 读取：列表 ----------

  @Test
  void listWithoutCompanyIdSeesNothingAndDoesNotTouchRepository() {
    SecurityContextFixture.runAs(USER, null, "STAFF");

    assertTrue(service.list().isEmpty());
    verifyNoInteractions(repository);
  }

  @Test
  void listIsScopedByCompanyAndUser() {
    SecurityContextFixture.runAsInCompany(USER, COMPANY_ID, null, "STAFF");
    when(repository.findByCompanyIdAndOwnerUsernameOrderByUpdatedAtDesc(COMPANY_ID, USER))
        .thenReturn(List.of(existingMemory(1L, "我在综合办", 50)));

    List<MemoryView> views = service.list();

    assertEquals(1, views.size());
    assertEquals(1L, views.get(0).id());
    assertEquals("语义记忆", views.get(0).typeLabel());
    verify(repository).findByCompanyIdAndOwnerUsernameOrderByUpdatedAtDesc(COMPANY_ID, USER);
  }

  @Test
  void expiredMemoryStaysVisibleInListButFlagged() {
    SecurityContextFixture.runAsInCompany(USER, COMPANY_ID, null, "STAFF");
    UserMemory expired = existingMemory(2L, "上个月的临时口径", 50);
    expired.setExpiresAt(LocalDateTime.now().minusDays(1));
    when(repository.findByCompanyIdAndOwnerUsernameOrderByUpdatedAtDesc(COMPANY_ID, USER))
        .thenReturn(List.of(expired));

    List<MemoryView> views = service.list();

    assertEquals(1, views.size());
    assertTrue(views.get(0).expired());
  }

  // ---------- 读取：删除 ----------

  @Test
  void forgetOfAnotherUsersMemoryIsNotFoundAndDeletesNothing() {
    SecurityContextFixture.runAsInCompany(USER, COMPANY_ID, null, "STAFF");

    assertThrows(EntityNotFoundException.class, () -> service.forget(99L));

    verify(repository, never()).delete(any(UserMemory.class));
    verify(auditService, never()).log(anyString(), eq("MEMORY_DELETE"), anyString(), anyString(), anyString());
  }

  @Test
  void forgetOwnMemoryDeletesAndAudits() {
    SecurityContextFixture.runAsInCompany(USER, COMPANY_ID, null, "STAFF");
    UserMemory mine = existingMemory(5L, "我不管慰问金审批", 50);
    when(repository.findByIdAndCompanyIdAndOwnerUsername(5L, COMPANY_ID, USER))
        .thenReturn(Optional.of(mine));

    service.forget(5L);

    verify(repository).delete(mine);
    verify(auditService).log(eq(USER), eq("MEMORY_DELETE"), eq("USER_MEMORY"), eq("5"), anyString());
  }

  // ---------- 上限淘汰 ----------

  @Test
  void noEvictionWhenUnderTheCap() {
    SecurityContextFixture.runAsInCompany(USER, COMPANY_ID, null, "STAFF");
    when(repository.save(any(UserMemory.class))).thenAnswer(invocation -> invocation.getArgument(0));
    when(repository.countByCompanyIdAndOwnerUsername(COMPANY_ID, USER))
        .thenReturn((long) MemoryService.MAX_MEMORIES_PER_USER);

    service.remember("快到上限了", MemoryType.SEMANTIC, 50, null, MemorySource.MANUAL);

    verify(repository, never()).delete(any(UserMemory.class));
  }

  @Test
  void exceedingTheCapEvictsLeastImportantFirst() {
    SecurityContextFixture.runAsInCompany(USER, COMPANY_ID, null, "STAFF");
    when(repository.save(any(UserMemory.class))).thenAnswer(invocation -> invocation.getArgument(0));
    when(repository.countByCompanyIdAndOwnerUsername(COMPANY_ID, USER))
        .thenReturn((long) MemoryService.MAX_MEMORIES_PER_USER + 1);
    UserMemory least = existingMemory(1L, "最不重要且最旧", 5);
    UserMemory important = existingMemory(2L, "很重要", 95);
    when(repository.findByCompanyIdAndOwnerUsernameOrderByImportanceAscUpdatedAtAsc(COMPANY_ID, USER))
        .thenReturn(List.of(least, important));

    service.remember("新记忆", MemoryType.SEMANTIC, 50, null, MemorySource.MANUAL);

    verify(repository).delete(least);
    verify(repository, never()).delete(important);
    verify(auditService).log(eq(USER), eq("MEMORY_EVICT"), eq("USER_MEMORY"), eq("-"), anyString());
  }

  // ---------- 召回 ----------

  @Test
  void recallWithoutCompanyIdReturnsEmptyBlock() {
    SecurityContextFixture.runAs(USER, null, "STAFF");

    MemoryRecallResult result = service.recall("工伤怎么认定", null, null);

    assertEquals("", result.block());
    assertTrue(result.memories().isEmpty());
    verifyNoInteractions(repository);
  }

  @Test
  void recallRanksRelevantMemoryFirstAndFiltersExpired() {
    SecurityContextFixture.runAsInCompany(USER, COMPANY_ID, null, "STAFF");
    UserMemory relevant = existingMemory(1L, "工伤认定要先做事故调查", 50);
    UserMemory irrelevant = existingMemory(2L, "我喜欢用表格汇报", 90);
    UserMemory expired = existingMemory(3L, "工伤认定旧口径已作废", 100);
    expired.setExpiresAt(LocalDateTime.now().minusDays(1));
    when(repository.findByCompanyIdAndOwnerUsernameAndExpiresAtIsNullOrExpiresAtAfterOrderByUpdatedAtDesc(
        eq(COMPANY_ID), eq(USER), any(LocalDateTime.class)))
        .thenReturn(List.of(irrelevant, relevant, expired));

    MemoryRecallResult result = service.recallForPrompt("工伤认定流程是什么");

    assertTrue(result.block().contains("工伤认定要先做事故调查"));
    assertTrue(result.block().indexOf("工伤认定要先做事故调查") < result.block().indexOf("我喜欢用表格汇报"));
    assertFalse(result.block().contains("旧口径已作废"));
    assertEquals(2, result.memories().size());
  }

  @Test
  void recallWithNoMemoriesReturnsEmptyBlock() {
    SecurityContextFixture.runAsInCompany(USER, COMPANY_ID, null, "STAFF");
    when(repository.findByCompanyIdAndOwnerUsernameAndExpiresAtIsNullOrExpiresAtAfterOrderByUpdatedAtDesc(
        eq(COMPANY_ID), eq(USER), any(LocalDateTime.class)))
        .thenReturn(List.of());

    MemoryRecallResult result = service.recallForPrompt("随便问问");

    assertEquals("", result.block());
    assertEquals(0, result.estimatedTokens());
  }
}
