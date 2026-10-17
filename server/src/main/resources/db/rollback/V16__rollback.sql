-- V16 回滚：删除用户记忆表。
-- 只回退 V16 引入的内容（本迁移是纯新增表，未改动任何既有表与数据），幂等，可重复执行。
--
-- 如实记账：本脚本会**丢弃全部用户记忆数据**。执行前请确认已备份：
--   pg_dump -t user_memory ... > user_memory_before_rollback.sql
-- 备份后重放本迁移（create table if not exists）即可恢复表结构，但数据只能从备份回填。

do $$
begin
  if to_regclass('public.user_memory') is null then
    raise notice 'V16 回滚：user_memory 不存在，无需处理';
    return;
  end if;

  drop table user_memory;
  raise notice 'V16 回滚 ok: user_memory 已删除';
end $$;
