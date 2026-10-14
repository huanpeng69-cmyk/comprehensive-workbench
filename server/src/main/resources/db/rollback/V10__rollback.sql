-- V10__multi_company.sql 的反向脚本（手工执行；不参与 Flyway 扫描，因为 application.yml 的
-- flyway.locations 只有 classpath:db/migration）。
--
-- 使用前提（重要）：
--   1. 尚未有第二家公司产生业务数据。否则第 2 步恢复全局唯一约束会因"不同公司同名"失败。
--   2. 已备份：pg_dump -Fp -f server\backup-before-rollback-<日期>.sql
-- 用法：psql -U <user> -d <db> -v ON_ERROR_STOP=1 -f server/src/main/resources/db/rollback/V10__rollback.sql
-- 执行后如需让 Flyway 允许重跑 V10，手工删除版本记录（本脚本不自动做）：
--   delete from flyway_schema_history where version = '10';

begin;

-- 1) 删除阶段 2 新增的 3 个角色（先删关联，避免外键失败）
delete from sys_user_role
 where role_id in (select id from sys_role
                    where code in ('OFFICE_DIRECTOR', 'OFFICE_DEPUTY', 'DEPT_LEADER'));

delete from sys_role
 where code in ('OFFICE_DIRECTOR', 'OFFICE_DEPUTY', 'DEPT_LEADER');

-- 2) 唯一约束回退：删复合唯一，恢复单列全局唯一
do $$
declare c record;
begin
  for c in
    select conname from pg_constraint
     where conrelid = 'sys_user'::regclass and contype = 'u'
       and pg_get_constraintdef(oid) = 'UNIQUE (company_id, username)'
  loop
    execute format('alter table sys_user drop constraint %I', c.conname);
  end loop;

  for c in
    select conname from pg_constraint
     where conrelid = 'salary_month'::regclass and contype = 'u'
       and pg_get_constraintdef(oid) = 'UNIQUE (company_id, month)'
  loop
    execute format('alter table salary_month drop constraint %I', c.conname);
  end loop;
end $$;

alter table sys_user     add constraint sys_user_username_key unique (username);
alter table salary_month add constraint salary_month_month_key unique (month);

-- 3) 删列（顺序：先删业务表，最后删 org_unit，避免留下悬空引用）
alter table sys_user         drop column if exists external_id;
alter table sys_user         drop column if exists company_id;
alter table employee         drop column if exists company_id;
alter table leave_record     drop column if exists company_id;
alter table condolence       drop column if exists company_id;
alter table process_instance drop column if exists company_id;
alter table salary_month     drop column if exists company_id;
alter table salary_record    drop column if exists company_id;
alter table perf_record      drop column if exists company_id;
alter table notice           drop column if exists company_id;
alter table party_fee        drop column if exists company_id;
alter table org_unit         drop column if exists company_id;

-- 4) 删掉阶段 2 新增的两家公司及其下级单位（只按 full_path 前缀，不碰南方公司）
delete from org_unit
 where id in (select id from org_unit where type = 'DEPARTMENT'
                and (full_path like '北方公司/%' or full_path like '华东公司/%'));
delete from org_unit
 where id in (select id from org_unit where type = 'PROJECT'
                and (full_path like '北方公司/%' or full_path like '华东公司/%'));
delete from org_unit
 where type = 'COMPANY' and full_path in ('北方公司', '华东公司');
-- 5) 刻意不回退：公司显示名规范化（V10 第 7 节把占位名 '示范建工集团南方公司' 改为
--    '中国水利水电第六工程局有限公司南方公司'）保留真实公司名。
--    理由：该字段与 company_id 维度无关，回滚的目的是撤掉多单位改造；把名字改回占位名
--          反而把数据推回错误口径，且生产库本来就是真实名（第 7 节在生产上本就空转）。
--          如确需改回占位名，请手工执行：
--    update org_unit set name = '示范建工集团南方公司'
--     where type = 'COMPANY' and name = '中国水利水电第六工程局有限公司南方公司';


commit;
