-- V19 回滚：移除治理底座为审计日志新增的字段与索引。
--
-- 如实记账：本脚本会**丢弃**已填写的 result / result_code / trace_id 数据。
-- 执行前请先备份：pg_dump -t audit_log ... > audit_log_before_rollback.sql
-- 只回退 V19 引入的列与索引，不动既有列与数据；幂等，可重复执行。
do $$
begin
  if not exists (
    select 1 from information_schema.columns
     where table_name = 'audit_log' and column_name = 'result'
  ) then
    raise notice 'V19 回滚：audit_log.result 不存在，无需处理';
    return;
  end if;

  drop index if exists idx_audit_log_trace;
  alter table audit_log drop column trace_id;
  alter table audit_log drop column result_code;
  alter table audit_log drop column result;
  raise notice 'V19 回滚 ok: audit_log 治理字段已移除';
end $$;