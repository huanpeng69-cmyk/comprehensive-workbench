package com.workbench.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * {@link Permissions} 与数据库权限点字典的一致性回归。
 *
 * <p>{@code Permissions} 的类注释承诺「本类列出的每个常量都必须能在迁移里找到同名 sys_permission 行」，
 * 但此前这条承诺只靠 {@code SchemaBaselineIT} 里手写的一两条断言兜着，新增常量时忘写断言就会出现
 * 「注解写对了、库里没这个点、所有人静默 403 且没人发现」。这里改成机器核对：
 * 遍历 {@code Permissions} 的全部权限点码常量，逐个到 {@code db/migration/*.sql} 里找 seed 语句。</p>
 *
 * <p>为什么跳过 {@code HAS_} 开头的常量：它们是拼给 {@code @PreAuthorize} 的 SpEL 表达式，
 * 不是权限点码；真正落库的是它们引用的那个码。</p>
 */
class PermissionsMigrationConsistencyTest {

  @Test
  void agentUseCodeIsStable() {
    // 值不得随手改：库里的 sys_permission.code 与已签发 token 里的 permissions claim 都按它匹配，
    // 改名等于让所有已登录用户（含刚被授予的角色）当场丢失权限。
    assertEquals("ai:agent:use", Permissions.AI_AGENT_USE);
    assertEquals("hasAuthority('ai:agent:use')", Permissions.HAS_AI_AGENT_USE);
  }

  @Test
  void everyPermissionCodeIsSeededBySomeMigration() throws Exception {
    String migrations = readMigrations();
    List<String> codes = permissionCodes();
    assertTrue(codes.size() >= 2,
        "反射没取到权限点码，检查 Permissions 的常量形态是否变了（当前取到 " + codes.size() + " 个）");
    for (String code : codes) {
      assertTrue(migrations.contains("'" + code + "'"),
          "权限点 " + code + " 在 Permissions 里声明了，但 db/migration 里找不到 seed 语句："
              + "库里没有这一行 → 该权限点谁都拿不到 → @PreAuthorize 一律 403");
    }
  }

  /** 读出全部迁移脚本正文，用于查找权限点 seed 语句。 */
  private static String readMigrations() throws Exception {
    Resource[] resources = new PathMatchingResourcePatternResolver()
        .getResources("classpath:db/migration/*.sql");
    assertTrue(resources.length > 0, "db/migration 下没有迁移脚本？classpath 配置有问题");
    StringBuilder text = new StringBuilder();
    for (Resource resource : resources) {
      text.append(new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8))
          .append('\n');
    }
    return text.toString();
  }

  private static List<String> permissionCodes() throws IllegalAccessException {
    List<String> codes = new ArrayList<>();
    for (Field field : Permissions.class.getDeclaredFields()) {
      if (!Modifier.isStatic(field.getModifiers()) || field.getType() != String.class) {
        continue;
      }
      if (field.getName().startsWith("HAS_")) {
        continue;
      }
      Object value = field.get(null);
      if (value != null) {
        codes.add((String) value);
      }
    }
    return codes;
  }
}
