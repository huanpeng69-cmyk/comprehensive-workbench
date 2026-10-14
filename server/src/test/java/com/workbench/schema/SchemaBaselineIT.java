package com.workbench.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 中台化回归基线：真实 PostgreSQL 上的迁移与约束契约。
 *
 * <p>用**一次性容器**（绝不复用开发库）：既不碰开发库数据，也不依赖本地是否有 PG。
 * 本类命名以 IT 结尾，归 maven-failsafe-plugin 管理，只在 {@code mvn verify} 时执行；
 * {@code mvn test} 不启动 Docker，保证无 Docker 环境下单元测试仍全绿。</p>
 *
 * <p><b>前置条件</b>：本机需有可用 Docker。Docker Engine 29 / Docker Desktop 4.72 起，
 * 客户端声明的 API 版本低于引擎下限会被直接拒绝（HTTP 400），而 docker-java 默认声明 1.32，
 * Testcontainers 因此误报「Could not find a valid Docker environment」。
 * 修复方式为显式抬高 API 版本，已固化在 {@code server/pom.xml} 的 failsafe 插件配置
 * （系统属性 api.version = 1.44），无需命令行传参即可 {@code mvn verify}。
 * 若本机或 CI 的引擎更老，按该引擎支持的最大版本调小即可。</p>
 *
 * <p><b>阶段 2 已改写</b>——阶段 0 立的断言是「这些表还没有公司维度、username 全局唯一」，
 * 那是改造前的护栏；V10 落地后它们必然为假，因此这里正面改写为阶段 2 的目标契约：</p>
 * <ol>
 *   <li><b>软隔离落地</b>：11 张表带 {@code company_id} 且 NOT NULL，
 *       {@code username} / {@code month} 由全局唯一改为按公司复合唯一。</li>
 *   <li><b>公司维度不下沉到不该下沉的地方</b>：{@code salary_config}（全局单行配置）与
 *       {@code sys_role} / {@code process_definition} 仍按全局口径，不得被顺手加上公司列。</li>
 *   <li><b>单位种子</b>：V10 种下 2 家公司，各自 5 个项目部 + 7 个部门；
 *       新增 4 个角色码（办公室主任 / 副主任 / 部门负责人 / 工会主席）。
 * </ol>
 *
 * <p>空库上跑迁移只能验证结构与约束，验证不了「既有数据回填是否归对家」，
 * 后者由 {@code V10__rollback.sql} 的可逆性演练在真实数据副本上覆盖
 * （见 {@code docs/中台化改造方案.md} 阶段 2 记账）。</p>
 */
@Testcontainers
class SchemaBaselineIT {

  /** 与生产同版本线的轻量镜像；Testcontainers 自行拉取并销毁。 */
  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  /** 现有迁移：V1～V15 全部成功，一条都不能漏。 */
  private static final int EXPECTED_MIGRATIONS = 15;

  /** 阶段 2 加 {@code company_id} 且收紧为 NOT NULL 的 11 张表（ADR-M002 软隔离口径）。 */
  private static final List<String> COMPANY_SCOPED_TABLES = List.of(
      "org_unit", "sys_user", "employee", "leave_record", "condolence", "process_instance",
      "salary_month", "salary_record", "perf_record", "notice", "party_fee");

  /** 无项目维度、因此只有 {@code company_id} 而没有 {@code project_id} 的表。 */
  private static final List<String> COMPANY_ONLY_TABLES = List.of(
      "salary_month", "salary_record", "perf_record", "notice", "party_fee");

  /** 已经按项目部隔离的表（PROJECT 数据范围的依据）。 */
  private static final List<String> TABLES_WITH_PROJECT_ID = List.of(
      "employee", "leave_record", "condolence", "sys_user", "process_instance");

  /** 刻意共享、不得出现公司列的表（公司维度下沉到这里就是设计事故）。 */
  private static final List<String> GLOBAL_TABLES = List.of(
      "salary_config", "sys_role", "sys_user_role", "process_definition", "process_node",
      "process_task", "audit_log");

  /** V10 种下的公司数与每家公司下级数。 */
  private static final int SEEDED_COMPANIES = 2;

  private static final int SEEDED_PROJECTS_PER_COMPANY = 5;

  private static final int SEEDED_DEPARTMENTS_PER_COMPANY = 7;

  /** V10 / V11 新增的角色码（V11 加 {@code UNION_LEADER}）。 */
  private static final List<String> SEEDED_ROLES =
      List.of("OFFICE_DIRECTOR", "OFFICE_DEPUTY", "DEPT_LEADER", "UNION_LEADER");

  @BeforeAll
  static void migrate() {
    Flyway flyway = Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations("classpath:db/migration")
        .load();
    MigrateResult result = flyway.migrate();
    assertTrue(result.success, "Flyway 迁移必须成功");
  }

  private static Connection connect() throws SQLException {
    return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
        POSTGRES.getPassword());
  }

  private static void exec(String sql) throws SQLException {
    try (Connection conn = connect(); Statement st = conn.createStatement()) {
      st.execute(sql);
    }
  }

  /** 先执行一次让数据就位，再执行一次断言被数据库拒绝（期望 SQLState，如 23505 / 23502）。 */
  private static void assertRejected(String sql, String expectedState) throws SQLException {
    exec(sql);
    SQLException ex = assertThrows(SQLException.class, () -> exec(sql),
        "重复插入必须被数据库拒绝：" + sql);
    assertEquals(expectedState, ex.getSQLState(), "期望 SQLState " + expectedState
        + "，实际：" + ex.getMessage());
  }

  private static int count(String sql, Object... params) throws SQLException {
    try (Connection conn = connect();
        var ps = conn.prepareStatement(sql)) {
      for (int i = 0; i < params.length; i++) {
        ps.setObject(i + 1, params[i]);
      }
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getInt(1);
      }
    }
  }

  private static String text(String sql, Object... params) throws SQLException {
    try (Connection conn = connect();
        var ps = conn.prepareStatement(sql)) {
      for (int i = 0; i < params.length; i++) {
        ps.setObject(i + 1, params[i]);
      }
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getString(1) : null;
      }
    }
  }

  private static List<Long> listLong(String sql) throws SQLException {
    List<Long> ids = new ArrayList<>();
    try (Connection conn = connect();
        Statement st = conn.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      while (rs.next()) {
        ids.add(rs.getLong(1));
      }
    }
    return ids;
  }

  private static boolean hasColumn(String table, String column) throws SQLException {
    return count("select count(*) from information_schema.columns "
        + "where table_name = ? and column_name = ?", table, column) > 0;
  }

  private static boolean isNullable(String table, String column) throws SQLException {
    return "YES".equals(text("select is_nullable from information_schema.columns "
        + "where table_name = ? and column_name = ?", table, column));
  }

  /** 公司节点 id，按 id 升序（V10 之后至少 2 家；空库上没有「南方公司」）。 */
  private static List<Long> companyIds() throws SQLException {
    return listLong("select id from org_unit where type = 'COMPANY' order by id");
  }

  @Test
  void allMigrationsApplyAndAreRecorded() throws SQLException {
    List<String> versions = new ArrayList<>();
    try (Connection conn = connect();
        Statement st = conn.createStatement();
        ResultSet rs = st.executeQuery(
            "select version, success from flyway_schema_history order by installed_rank")) {
      while (rs.next()) {
        assertTrue(rs.getBoolean("success"), "迁移 " + rs.getString("version") + " 必须成功");
        if (rs.getString("version") != null) {
          versions.add(rs.getString("version"));
        }
      }
    }
    assertEquals(List.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15"), versions);
    assertEquals(EXPECTED_MIGRATIONS, versions.size());
  }

  @Test
  void companyScopedTablesCarryNotNullCompanyId() throws SQLException {
    for (String table : COMPANY_SCOPED_TABLES) {
      assertEquals(1, count("select count(*) from information_schema.tables "
              + "where table_schema = 'public' and table_name = ?", table),
          "表必须存在：" + table);
      assertTrue(hasColumn(table, "company_id"), table + " 必须有 company_id 列");
      assertTrue(!isNullable(table, "company_id"),
          table + ".company_id 必须 NOT NULL：漏填要在插入时当场失败，而不是静默变成「谁都不见」");
    }
  }

  @Test
  void globalTablesMustNotGainCompanyColumn() throws SQLException {
    for (String table : GLOBAL_TABLES) {
      assertTrue(!hasColumn(table, "company_id"),
          table + " 是三家公司共享的表，不得加 company_id（方案 §9.1 结论 2）");
    }
  }

  @Test
  void usernameIsUniquePerCompanyInsteadOfGlobally() throws SQLException {
    List<Long> companies = companyIds();
    assertTrue(companies.size() >= SEEDED_COMPANIES,
        "V10 必须种下至少 2 家公司，否则无法表达「按公司唯一」");
    long first = companies.get(0);
    long second = companies.get(1);

    // 同公司同名：必须被拒
    assertRejected("insert into sys_user (username, name, password_hash, enabled, company_id) "
        + "values ('it-dup-user', '重名用户', 'hash', true, " + first + ")", "23505");

    // 跨公司同名：必须允许（ADR-M006 复合唯一）
    exec("insert into sys_user (username, name, password_hash, enabled, company_id) "
        + "values ('it-dup-user', '重名用户', 'hash', true, " + second + ")");
    assertEquals(2, count("select count(*) from sys_user where username = 'it-dup-user'"),
        "同名账号应能在两家公司各存在一个");
  }

  @Test
  void salaryMonthIsUniquePerCompanyInsteadOfGlobally() throws SQLException {
    List<Long> companies = companyIds();
    long first = companies.get(0);
    long second = companies.get(1);

    assertRejected("insert into salary_month (month, company_id) values ('2098-12', " + first + ")",
        "23505");

    exec("insert into salary_month (month, company_id) values ('2098-12', " + second + ")");
    assertEquals(2, count("select count(*) from salary_month where month = '2098-12'"),
        "同一个月应能在两家公司各自存在");
  }

  @Test
  void usernameWithoutCompanyIsRejectedByDatabase() throws SQLException {
    // 收紧为 NOT NULL 的意义：漏填 company_id 的插入必须失败，不许悄悄变成「谁都不见」
    SQLException ex = assertThrows(SQLException.class,
        () -> exec("insert into sys_user (username, name, password_hash, enabled) "
            + "values ('it-no-company', '缺公司', 'hash', true)"),
        "缺 company_id 的插入必须被拒");
    assertEquals("23502", ex.getSQLState(), "期望 NOT NULL 违反（23502），实际：" + ex.getMessage());
  }

  @Test
  void roleCodeIsGloballyUnique() throws SQLException {
    // 角色码全局统一（ADR-M003：生效范围由用户所属公司决定，不在 sys_user_role 加公司列）
    assertRejected(
        "insert into sys_role (code, name, data_scope, enabled) "
            + "values ('DUP_ROLE', '重名角色', 'PROJECT', true)", "23505");
  }

  @Test
  void processDefinitionCodeIsGloballyUnique() throws SQLException {
    assertRejected(
        "insert into process_definition (code, name, biz_type, version, enabled) "
            + "values ('dup-flow', '重名流程', 'dup-flow', 1, true)", "23505");
  }

  @Test
  void salaryConfigAllowsOnlyOneRow() throws SQLException {
    // 不给 id，靠 default 1：单行表的语义由数据库保证
    assertRejected("insert into salary_config (data) values ('{}')", "23505");
  }

  @Test
  void projectScopedTablesStillUseProjectId() throws SQLException {
    for (String table : TABLES_WITH_PROJECT_ID) {
      assertTrue(hasColumn(table, "project_id"), table + " 必须保留 project_id 列");
    }
    for (String table : COMPANY_ONLY_TABLES) {
      assertTrue(!hasColumn(table, "project_id"),
          table + " 无项目维度，不应有 project_id");
      assertTrue(hasColumn(table, "company_id"), table + " 必须有 company_id");
    }
  }

  @Test
  void orgUnitTreeCarriesCompanyIdPointingAtCompanyRow() throws SQLException {
    for (String column : List.of("parent_id", "full_path", "company_id")) {
      assertTrue(hasColumn("org_unit", column), "org_unit 必须有 " + column + " 列");
    }

    // COMPANY 行等于自身；下级必须指向一个 COMPANY 行（不许自己编一个公司 id）
    assertEquals(0, count("select count(*) from org_unit where company_id is null"),
        "所有单位都必须有归属公司");
    assertEquals(0, count("select count(*) from org_unit o "
            + "left join org_unit c on c.id = o.company_id and c.type = 'COMPANY' "
            + "where c.id is null"),
        "company_id 必须指向 type = 'COMPANY' 的单位行");
    assertEquals(0, count("select count(*) from org_unit where type = 'COMPANY' "
            + "and company_id <> id"),
        "COMPANY 行的 company_id 必须等于自身 id");
  }


  @Test
  void workflowDefinitionAndNodeCarryDescriptionColumns() throws SQLException {
    // ADR-M010: admin UI needs description columns so an admin can tell what each field means
    assertTrue(hasColumn("process_definition", "description"),
        "process_definition must have description column");
    assertTrue(hasColumn("process_node", "description"),
        "process_node must have description column");
  }

  @Test
  void definitionVersionSnapshotIsUniquePerDefinitionAndVersion() throws SQLException {
    // rollback writes an old snapshot back and creates a NEW version, so version numbers
    // only ever grow and never repeat inside one definition
    exec("insert into process_definition (code, name, biz_type, version, enabled) "
        + "values ('it-def-ver', 'IT def', 'it', 1, true)");
    exec("insert into process_definition_version "
        + "(definition_id, version, change_type, snapshot, created_by) "
        + "values ((select id from process_definition where code = 'it-def-ver'), 1, "
        + "'INIT', '{}', 'it')");
    SQLException ex = assertThrows(SQLException.class, () -> exec(
        "insert into process_definition_version "
            + "(definition_id, version, change_type, snapshot, created_by) "
            + "values ((select id from process_definition where code = 'it-def-ver'), 1, "
            + "'UPDATE', '{}', 'it')"));
    assertEquals("23505", ex.getSQLState(),
        "same (definition, version) must be rejected: " + ex.getMessage());
  }

  @Test
  void definitionPermissionIsSeededForWorkflowAdmin() throws SQLException {
    // V14 seeds the permission dictionary only. The grant itself lives in
    // DataInitializer because sys_role rows do not exist yet during Flyway.
    assertEquals(1, count("select count(*) from sys_permission "
            + "where code = 'workflow:definition:manage'"),
        "V14 must seed the workflow:definition:manage permission");
    assertEquals(0, count("select count(*) from sys_role_permission"),
        "no grant rows during Flyway: roles are seeded by the application layer");
  }


  @Test
  void codeSequenceIsUniquePerCategoryAndPeriod() throws SQLException {
    // 阶段 3（ADR-M009）：文号「不重号」的数据库侧保障——(类别, 周期) 唯一。
    // 并发取号时服务端靠这一行加锁串行化；唯一约束则兜住「两个周期行被同时创建」的情况。
    assertRejected(
        "insert into code_sequence (category, period, current_value) "
            + "values ('LEAVE', '2026', 1)", "23505");
  }

  @Test
  void codeSequenceRejectsNegativeValue() throws SQLException {
    // 序号不允许回退：把 current_value 设成负数的插入直接被 CHECK 拒绝（单次执行即失败）
    SQLException ex = assertThrows(SQLException.class,
        () -> exec("insert into code_sequence (category, period, current_value) "
            + "values ('NEG', '2026', -1)"));
    assertEquals("23514", ex.getSQLState(),
        "期望 CHECK 约束违反（23514），实际：" + ex.getMessage());
  }

  @Test
  void codeRuleIsGloballyUniqueAndSeeded() throws SQLException {
    // 规则由公司统一制定：类别码全局唯一，不允许各单位各造一套（与 sys_role.code 同口径）
    // LEAVE 规则已由 V12 种下，因此这里单次插入就必须被拒（不需要先成功一次）
    SQLException ex = assertThrows(SQLException.class,
        () -> exec("insert into code_rule (category, name, pattern, seq_width, reset_cycle, enabled) "
            + "values ('LEAVE', '重复请假单', '{SEQ}', 4, 'YEAR', true)"));
    assertEquals("23505", ex.getSQLState(),
        "期望唯一约束违反（23505），实际：" + ex.getMessage());
    assertTrue(count("select count(*) from code_rule") >= 4,
        "V12 应种下至少 4 条演示规则");
    // 序号表必须为空（V12 不种序号数据）。刻意用一个从未被其它测试碰过的类别来数，
    // 避免依赖测试执行顺序：别的测试可能已取过号并留下序号行。
    assertEquals(0, count("select count(*) from code_sequence where category = 'CONDOLENCE'"),
        "V12 不应种序号数据，否则首个业务编号会跳号");
  }

  @Test
  void multiCompanySeedIsPresent() throws SQLException {
    assertEquals(SEEDED_COMPANIES, companyIds().size(), "V10 必须种下 2 家新公司");
    for (String path : List.of("北方公司", "华东公司")) {
      assertEquals(1, count("select count(*) from org_unit "
              + "where type = 'COMPANY' and full_path = ?", path), "缺少公司种子：" + path);
    }

    List<Long> seeded = listLong("select id from org_unit where type = 'COMPANY' "
        + "and full_path in ('北方公司', '华东公司') order by id");
    for (Long companyId : seeded) {
      assertEquals(SEEDED_PROJECTS_PER_COMPANY, count("select count(*) from org_unit "
          + "where type = 'PROJECT' and company_id = ?", companyId),
          "公司 " + companyId + " 的项目部数量不对");
      assertEquals(SEEDED_DEPARTMENTS_PER_COMPANY, count("select count(*) from org_unit "
          + "where type = 'DEPARTMENT' and company_id = ?", companyId),
          "公司 " + companyId + " 的部门数量不对");
    }

    for (String code : SEEDED_ROLES) {
      assertEquals(1, count("select count(*) from sys_role where code = ?", code),
          "缺少角色种子：" + code);
    }
  }

  @Test
  void condolenceCompanyApprovalNodeIsResolvedByRoleInsteadOfUsername() throws SQLException {
    // V11：V8 曾把该节点写死成 USER/fangjing；用户名改成单位内唯一后，按用户名派单会跨公司命中同名账号。
    // 这里锁住节点已切成 ROLE/UNION_LEADER（ADR-M008 遗留项①），并锁住「流程里不再有按用户名写死的审批节点」。
    assertEquals(1, count("select count(*) from process_node pn "
            + "join process_definition pd on pd.id = pn.definition_id "
            + "where pd.biz_type = 'condolence' and pn.seq = 2 "
            + "and pn.assignee_type = 'ROLE' and pn.assignee_expr = 'UNION_LEADER'"),
        "慰问金 2 号审批节点必须按单位内角色 UNION_LEADER 指派");

    assertEquals(0, count("select count(*) from process_node pn "
            + "join process_definition pd on pd.id = pn.definition_id "
            + "where pd.biz_type = 'condolence' and pn.assignee_type = 'USER'"),
        "慰问金流程不应再有按用户名写死的审批节点");
  }

  @Test
  void externalIdColumnIsReservedForSso() throws SQLException {
    // ADR-M006：本期不接 SSO，只建列；不许在这轮把登录逻辑挂上去（那是阶段 4）
    assertEquals("character varying", text("select data_type from information_schema.columns "
        + "where table_name = 'sys_user' and column_name = 'external_id'"), "external_id 类型不符");
    assertTrue(isNullable("sys_user", "external_id"), "external_id 本期必须可空");
    assertEquals(64, count("select character_maximum_length from information_schema.columns "
        + "where table_name = 'sys_user' and column_name = 'external_id'"), "external_id 长度应为 64");
  }

  @Test
  void attachmentTableCarriesNotNullCompanyId() throws SQLException {
    // 阶段 3 任务 2（ADR-M011）：附件归属单位，跨公司访问被应用层 canAccessCompany 拒绝。
    assertEquals(1, count("select count(*) from information_schema.tables "
            + "where table_schema = 'public' and table_name = 'file_attachment'"),
        "file_attachment 表必须存在");
    assertTrue(hasColumn("file_attachment", "company_id"),
        "file_attachment 必须有 company_id 列（与公司维度表同口径）");
    assertTrue(!isNullable("file_attachment", "company_id"),
        "file_attachment.company_id 必须 NOT NULL");
    // storage_path 只存相对 storage-root 的路径，便于换盘/容器化；这里只断言列存在
    assertTrue(hasColumn("file_attachment", "storage_path"),
        "file_attachment 必须有 storage_path 列");
  }
}
