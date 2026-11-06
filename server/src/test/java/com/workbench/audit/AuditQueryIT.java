package com.workbench.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workbench.audit.application.AuditQueryService;
import com.workbench.audit.repository.AuditLogRepository;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaDialect;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 审计查询的**真实 PostgreSQL** 集成测试（治理底座 C 的回归防线）。
 *
 * <p>为什么必须是真库而不是 Mock 单测：2026-11-05退了事故——JPQL + {@code Pageable}
 * 在 PostgreSQL 上生成 {@code fetch first ? rows only}，行号参数类型在 prepare 阶段无法推断，
 * 叠加 6 组 {@code (? is null or col = ?)} 后 PG 直接报
 * {@code could not determine data type of parameter $9}，接口 500、页面空白。
 * 单测里 mock 掉 repository 根本不执行 SQL，这个 bug 100% 漏网；只有把 SQL 真的发给 PG 才能抓住。</p>
 *
 * <p>命名以 IT 结尾，归 failsafe，仅 {@code mvn verify} 时运行；{@code mvn test} 不启 Docker。</p>
 */
@Testcontainers
class AuditQueryIT {

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  private static LocalContainerEntityManagerFactoryBean FACTORY;
  private static jakarta.persistence.EntityManager EM;
  private static AuditLogRepository REPOSITORY;
  private static AuditQueryService SERVICE;
  private static TransactionTemplate TX;

  @BeforeAll
  static void startUp() {
    // 1) 建表：与生产同一条迁移链，避免测试自造 DDL 与生产漂移。
    Flyway flyway = Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations("classpath:db/migration")
        .load();
    flyway.migrate();

    // 2) 最小 Hibernate 上下文：只扫本模块实体与仓储，不拉 Spring Boot 全量、不碰 Redis。
    FACTORY = new LocalContainerEntityManagerFactoryBean();
    FACTORY.setDataSource(new org.springframework.jdbc.datasource.DriverManagerDataSource(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    FACTORY.setPackagesToScan("com.workbench.audit.domain");
    FACTORY.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
    java.util.Map<String, Object> props = new java.util.HashMap<>();
    props.put("hibernate.hbm2ddl.auto", "validate");
    props.put("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect");
    props.put("hibernate.physical_naming_strategy",
        "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy");
    FACTORY.setJpaPropertyMap(props);
    FACTORY.afterPropertiesSet();

    EM = FACTORY.getObject().createEntityManager();
    JpaRepositoryFactory factory = new JpaRepositoryFactory(EM);
    REPOSITORY = factory.getRepository(AuditLogRepository.class);

    JpaTransactionManager tm = new JpaTransactionManager(FACTORY.getObject());
    TX = new TransactionTemplate(tm);
    SERVICE = new AuditQueryService(REPOSITORY);
  }

  @AfterAll
  static void tearDown() {
    if (EM != null) {
      EM.close();
    }
    if (FACTORY != null) {
      FACTORY.destroy();
    }
  }

  @BeforeEach
  void seed() throws Exception {
    // 每次用例前清空并灌入可判定的三行数据，避免用例间相互污染。
    try (Connection conn = DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = conn.createStatement()) {
      st.execute("delete from audit_log");
      st.execute("insert into audit_log (username, action, result, trace_id, created_at) values "
          + "('it-a', 'LOGIN_SUCCESS', 'SUCCESS', 'trace-1', '2026-01-01 10:00:00'),"
          + "('it-a', 'ACCESS_DENIED', 'DENIED',  'trace-2', '2026-01-02 10:00:00'),"
          + "('it-b', 'UNEXPECTED_ERROR', 'FAILURE', 'trace-3', '2026-01-03 10:00:00')");
    }
  }

  /**
   * 事故回归：空筛选也必须能查到数据且不抛 SQL 异常。
   *
   * <p>这正是 {@code could not determine data type of parameter $9} 的触发路径——
   * 所有条件为 null、走 limit/offset 分支。修复前本用例必挂。</p>
   */
  @Test
  void emptyFilterReturnsAllRowsWithoutSqlTypeError() {
    Map<String, Object> page = TX.execute(status -> SERVICE.search(null, null, null, null,
        null, null, 1, 50));

    assertNotNull(page, "查询结果不能为空");
    assertEquals(3L, page.get("total"), "空筛选必须返回全部 3 条");
    assertEquals(1, page.get("page"));
    assertEquals(50, page.get("size"));
    assertTrue(((java.util.List<?>) page.get("items")).size() == 3, "items 应有 3 条");
  }

  /**
   * 时间范围查询：两端都给了值，且必须按 created_at 过滤。
   *
   * <p>时间参数非 null 时才走比较分支，这一形态与"两端全空"是不同的 SQL 图谱，
   * 必须单独覆盖，否则修了 $9 仍可能在时间参数上报 $7/$8 类型错误。</p>
   */
  @Test
  void timeRangeFiltersByCreatedAt() {
    LocalDateTime from = LocalDateTime.of(2026, 1, 2, 0, 0, 0);
    LocalDateTime to = LocalDateTime.of(2026, 1, 3, 23, 59, 59);

    Map<String, Object> page = TX.execute(status -> SERVICE.search(null, null, null, null,
        from, to, 1, 50));

    assertEquals(2L, page.get("total"), "2026-01-02 与 01-03 两条应命中");
  }

  /** 只给下界：验证 hasFrom=true / hasTo=false 的混合布尔分支不会触发类型推断失败。 */
  @Test
  void onlyFromTimeIsAccepted() {
    LocalDateTime from = LocalDateTime.of(2026, 1, 3, 0, 0, 0);

    Map<String, Object> page = TX.execute(status -> SERVICE.search(null, null, null, null,
        from, null, 1, 50));

    assertEquals(1L, page.get("total"), "只有 01-03 那条满足下界");
  }

  /** 分页：第 2 页必须正确取到第 2 条，而不是空页或越界异常。 */
  @Test
  void secondPageReturnsCorrectSlice() {
    Map<String, Object> page = TX.execute(status -> SERVICE.search(null, null, null, null,
        null, null, 2, 1));

    assertEquals(3L, page.get("total"), "总数不因分页变化");
    assertEquals(2, page.get("page"));
    assertEquals(1, page.get("size"));
  }

  /** 结果码筛选 + 总数一致性；顺带验证按时间倒序（最新在前）。 */
  @Test
  void resultFilterAndDescendingOrder() {
    Map<String, Object> page = TX.execute(status -> SERVICE.search(null, null, "FAILURE", null,
        null, null, 1, 50));

    assertEquals(1L, page.get("total"), "只有 it-b 的 UNEXPECTED_ERROR 是 FAILURE");
    java.util.List<?> items = (java.util.List<?>) page.get("items");
    assertEquals("it-b",
        ((com.workbench.audit.dto.AuditLogView) items.get(0)).username(),
        "FAILURE 那条属于 it-b");
  }

  /** 全量查询必须按 created_at 倒序：01-03 > 01-02 > 01-01。 */
  @Test
  void allRowsOrderedByCreatedAtDesc() {
    Map<String, Object> page = TX.execute(status -> SERVICE.search(null, null, null, null,
        null, null, 1, 50));

    java.util.List<?> items = (java.util.List<?>) page.get("items");
    com.workbench.audit.dto.AuditLogView first =
        (com.workbench.audit.dto.AuditLogView) items.get(0);
    com.workbench.audit.dto.AuditLogView last =
        (com.workbench.audit.dto.AuditLogView) items.get(2);
    assertTrue(first.createdAt().isAfter(last.createdAt()), "首条必须晚于末条（倒序）");
    assertEquals("it-b", first.username(), "最新一条是 01-03 的 it-b");
  }

  /** 分页上限钳制：size 超 MAX 时必须被截断，防止整表拉进内存。 */
  @Test
  void pageSizeIsCappedByMax() {
    Map<String, Object> page = TX.execute(status -> SERVICE.search(null, null, null, null,
        null, null, 1, 100000));

    assertEquals(AuditQueryService.MAX_PAGE_SIZE, page.get("size"), "size 必须被钳到上限");
  }
}