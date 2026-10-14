package com.workbench.platform.org;

import java.util.Collection;
import java.util.List;

/**
 * 角色查询端口：供数据范围判定（{@code platform.datascope}）读取角色的 {@code dataScope}。
 *
 * <p>只有 {@link RoleRef} 视图对外可见，业务模块拿不到 {@code sys_role} 实体，
 * 也就无法自己写一套数据范围判断逻辑。</p>
 */
public interface RoleQueryPort {

  /** 按角色码批量查角色。codes 为 null 或空时返回空列表，不发起查询。 */
  List<RoleRef> findByCodes(Collection<String> codes);
}
