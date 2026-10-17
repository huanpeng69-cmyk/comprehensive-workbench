-- V17 回滚：删除工资明细的「其他假别扣款」列。
--
-- 如实记账：本脚本会**丢弃已填写的其他假别扣款数据**。执行前请先备份：
--   pg_dump -t salary_record ... > salary_record_before_rollback.sql
-- 只回退 V17 引入的列，不动其他列与数据。幂等，可重复执行。

do $$
begin
  if not exists (
    select 1 from information_schema.columns
     where table_name = 'salary_record' and column_name = 'leave_deduct'
  ) then
    raise notice 'V17 回滚：salary_record.leave_deduct 不存在，无需处理';
    return;
  end if;

  alter table salary_record drop column leave_deduct;
  raise notice 'V17 回滚 ok: salary_record.leave_deduct 已删除';
end $$;
