package com.workbench.platform.org;

/**
 * 用户的最小只读视图（登录名 + 姓名）。
 *
 * <p>刻意不暴露 {@code com.workbench.identity.domain.User}：业务模块不需要（也不应拿到）
 * 密码哈希、角色集合等字段，只需要展示用到的 username/name。</p>
 */
public record UserRef(String username, String name) {
}
