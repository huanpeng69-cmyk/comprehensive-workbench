-- V20 回滚：移除通用 Agent 入口权限点 ai:agent:use。
--
-- 影响范围（如实记账）：
--   1) 删 sys_permission 里的一行；sys_role_permission 中指向它的行由外键 on delete cascade 一并删除，
--      即**所有角色**都会失去该权限点。
--   2) 必须与代码同退：只要 @PreAuthorize(Permissions.HAS_AI_AGENT_USE) 还在，
--      删掉库里的权限点后所有账号调 /api/ai/writing/agent 都会被 403
--      （JWT 里的 permissions claim 来自库里这张表）。
--   3) 已签发且尚未过期的 token 不受影响（它们本就带着旧的 permissions claim），
--      但重新登录后拿不到该权限点。
--
-- 执行前备份：
--   pg_dump -t sys_permission -t sys_role_permission <库名> > permission_before_rollback.sql
--
-- 只删本迁移插入的行，不动其它权限点；幂等，可重复执行。
do $$
begin
  if not exists (select 1 from sys_permission where code = 'ai:agent:use') then
    raise notice 'V20 回滚：权限点 ai:agent:use 不存在，无需处理';
    return;
  end if;

  delete from sys_permission where code = 'ai:agent:use';
  raise notice 'V20 回滚 ok: 权限点 ai:agent:use 及其角色授权已移除';
end $$;
