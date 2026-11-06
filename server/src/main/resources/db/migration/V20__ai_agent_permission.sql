-- 通用 Agent 入口权限点 ai:agent:use（ADR-M013）
-- 背景：POST /api/ai/writing/agent 会按登录身份调用工具（知识库检索等），而此前它只要求「已登录」，
--       等于任何账号都能借 Agent 间接使用全部工具。把准入门槛落到 sys_permission + @PreAuthorize
--       才是真把关，也才能「按角色收回」而不是靠改代码。
--
-- 分工（与 V14 一致，ADR-M010）：
--   本迁移只建「权限点字典」一行；「角色 ↔ 权限点」的授予放在 DataInitializer 启动期补授。
--   原因：Flyway 跑本脚本时 sys_role 还是空的（角色种子由 DataInitializer / 演示数据写入），
--   这里的 insert 永远匹配不到行、纯属空转，还会让人觉得「授权已生效」。
--   与流程定义权限点不同的是，本权限点要授给**全部**既有角色，因此由
--   DataInitializer#grantAgentUseToAllRoles 每次启动遍历角色表补授（幂等），
--   这样开发库/生产库这类已有角色的库也不会在升级后出现「Agent 一律 403」。
--
-- 纪律：语句幂等，可重复执行；权限点 code 全局唯一（公司统一制定，与 sys_role.code 同口径）；
--       不给权限点加 company_id——权限是全局口径，数据隔离靠 company_id（ADR-M002）。

-- ---------------------------------------------------------------------------
-- 1) 权限点字典
-- ---------------------------------------------------------------------------
insert into sys_permission (code, name, module, enabled)
select 'ai:agent:use', 'AI 助手使用', 'ai', true
 where not exists (select 1 from sys_permission where code = 'ai:agent:use');

-- ---------------------------------------------------------------------------
-- 2) 自检
--    只验证「迁移自己该做的事」：权限点必须建成。
--    刻意不断言「角色已获得该权限」——SchemaBaselineIT 只跑 Flyway、不启 Spring，
--    空库里根本没有 sys_role 行；那条断言会让新装库的迁移直接失败（与 V14 同一坑）。
-- ---------------------------------------------------------------------------
do $$
declare
  n_perm bigint;
begin
  select count(*) into n_perm from sys_permission where code = 'ai:agent:use';
  if n_perm <> 1 then
    raise exception 'V20 失败：权限点 ai:agent:use 应恰好 1 条，实际 % 条', n_perm;
  end if;

  raise notice 'V20 ok: 权限点 ai:agent:use 已就位（角色授权由 DataInitializer 在启动期补授）';
end $$;
