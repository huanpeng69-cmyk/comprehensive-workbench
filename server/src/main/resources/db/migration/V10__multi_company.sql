-- 阶段 2：多单位维度落地（软隔离：同库同表 + company_id）。
-- 设计依据：docs/decisions/2026-10-13-multi-org-middle-platform.md（ADR-M002 / ADR-M003 / ADR-M006）
--           与 docs/中台化改造方案.md §3.3、§9.1、§9.2。
-- 执行顺序：结构 → 单位种子 → 回填 → NOT NULL 收紧 → 唯一约束切换 → 角色种子 → 公司显示名规范化。
-- 全部语句幂等（if not exists / where not exists），可重复执行。
-- 反向脚本：server/src/main/resources/db/rollback/V10__rollback.sql（手工执行，不参与 Flyway 扫描）。

-- ---------------------------------------------------------------------------
-- 1) 结构：新增 company_id（先可空，回填后再收紧为 NOT NULL）
-- ---------------------------------------------------------------------------
alter table org_unit         add column if not exists company_id bigint;
alter table sys_user         add column if not exists company_id bigint;
alter table sys_user         add column if not exists external_id varchar(64);
alter table employee         add column if not exists company_id bigint;
alter table leave_record     add column if not exists company_id bigint;
alter table condolence       add column if not exists company_id bigint;
alter table process_instance add column if not exists company_id bigint;
alter table salary_month     add column if not exists company_id bigint;
alter table salary_record    add column if not exists company_id bigint;
alter table perf_record      add column if not exists company_id bigint;
alter table notice           add column if not exists company_id bigint;
alter table party_fee        add column if not exists company_id bigint;

comment on column org_unit.company_id         is '所属公司（org_unit.id, type=COMPANY）；COMPANY 行等于自身 id';
comment on column sys_user.company_id         is '账号所属公司（ADR-M003：角色生效范围由用户所属公司决定）';
comment on column sys_user.external_id        is '预留：统一身份认证外部 ID（本期只建列，不写业务逻辑，ADR-M006）';
-- 说明：以下表刻意不加 company_id（三家单位共用一套，见方案 §9.1 结论 2）：
--   salary_config（全局单行配置）、sys_role / sys_user_role（角色码全局统一）、
--   process_definition / process_node / process_task（流程定义共用）、ai_config（全局 AI 配置）、
--   audit_log（全局审计，保留 username 口径）。

-- ---------------------------------------------------------------------------
-- 2) 单位种子：新增 2 家公司，各带 5 个项目部 + 7 个部门（结构与南方公司一致）
--    不进 sys_user 的公司名匹配逻辑；全部按 type/parent 定位，禁止按名字反查公司 id
-- ---------------------------------------------------------------------------
do $$
declare
  company_names text[] := array['中国水利水电第六工程局有限公司北方公司', '中国水利水电第六工程局有限公司华东公司'];
  short_names   text[] := array['北方公司', '华东公司'];
  project_names text[] := array['天河项目部', '白云机场项目部', '钟村项目部', '车棠项目部', '红茶项目部'];
  dept_names    text[] := array['综合管理部', '工程管理部', '合同管理部', '安全环保部', '质检部', '设备物资部', '财务管理部'];
  i int; j int; cid bigint; pid bigint; first_pid bigint; sname text;
begin
  for i in 1..array_length(company_names, 1) loop
    sname := short_names[i];
    select id into cid from org_unit where name = company_names[i] and type = 'COMPANY';
    if cid is null then
      insert into org_unit (name, type, parent_id, full_path, sort_no, enabled, company_id)
      values (company_names[i], 'COMPANY', null, sname, 1 + i, true, null)
      returning id into cid;
    end if;
    -- COMPANY 行的 company_id = 自身
    update org_unit set company_id = id where id = cid and company_id is distinct from id;

    first_pid := null;
    for j in 1..array_length(project_names, 1) loop
      select id into pid from org_unit where name = project_names[j] and parent_id = cid;
      if pid is null then
        insert into org_unit (name, type, parent_id, full_path, sort_no, enabled, company_id)
        values (project_names[j], 'PROJECT', cid, sname || '/' || project_names[j], j, true, cid)
        returning id into pid;
      end if;
      if first_pid is null then
        first_pid := pid;
      end if;
    end loop;

    -- 部门挂在各公司第一个项目部下（镜像南方公司现状：7 个部门全挂天河项目部）
    for j in 1..array_length(dept_names, 1) loop
      if not exists (select 1 from org_unit where name = dept_names[j] and parent_id = first_pid) then
        insert into org_unit (name, type, parent_id, full_path, sort_no, enabled, company_id)
        values (dept_names[j], 'DEPARTMENT', first_pid,
                sname || '/' || project_names[1] || '/' || dept_names[j], j, true, cid);
      end if;
    end loop;
  end loop;
end $$;

-- ---------------------------------------------------------------------------
-- 3) 回填：既有数据全部归南方公司（唯一既有 COMPANY 节点）
--    纪律：回填不按公司名匹配，先按 type='COMPANY' 取最小 id 作为"唯一既有公司"
-- ---------------------------------------------------------------------------
update org_unit set company_id = id where type = 'COMPANY';

-- 下级单位从最近的 COMPANY 祖先继承
with recursive tree as (
  select o.id, o.company_id
    from org_unit o
   where o.company_id is not null
  union all
  select c.id, t.company_id
    from org_unit c
    join tree t on c.parent_id = t.id
   where c.company_id is null
)
update org_unit o
   set company_id = t.company_id
  from tree t
 where t.id = o.id
   and o.company_id is null;

-- 有 project_id 的业务表：经 org_unit 上溯；无法上溯（未分配项目部）落唯一既有公司
update sys_user u
   set company_id = coalesce(
         (select o.company_id from org_unit o where o.id = u.project_id),
         (select o.company_id from org_unit o where o.id = u.org_id),
         (select id from org_unit where type = 'COMPANY' order by id limit 1))
 where u.company_id is null;

update employee e
   set company_id = coalesce(
         (select o.company_id from org_unit o where o.id = e.project_id),
         (select id from org_unit where type = 'COMPANY' order by id limit 1))
 where e.company_id is null;

update leave_record r
   set company_id = coalesce(
         (select o.company_id from org_unit o where o.id = r.project_id),
         (select id from org_unit where type = 'COMPANY' order by id limit 1))
 where r.company_id is null;

update condolence c
   set company_id = coalesce(
         (select o.company_id from org_unit o where o.id = c.project_id),
         (select id from org_unit where type = 'COMPANY' order by id limit 1))
 where c.company_id is null;

update process_instance p
   set company_id = coalesce(
         (select o.company_id from org_unit o where o.id = p.project_id),
         (select id from org_unit where type = 'COMPANY' order by id limit 1))
 where p.company_id is null;

-- 无项目维度的业务表：直接落唯一既有公司
update salary_month m
   set company_id = (select id from org_unit where type = 'COMPANY' order by id limit 1)
 where m.company_id is null;

update salary_record r
   set company_id = (select id from org_unit where type = 'COMPANY' order by id limit 1)
 where r.company_id is null;

update perf_record r
   set company_id = (select id from org_unit where type = 'COMPANY' order by id limit 1)
 where r.company_id is null;

update notice n
   set company_id = (select id from org_unit where type = 'COMPANY' order by id limit 1)
 where n.company_id is null;

update party_fee f
   set company_id = (select id from org_unit where type = 'COMPANY' order by id limit 1)
 where f.company_id is null;

-- ---------------------------------------------------------------------------
-- 4) NOT NULL 收紧：公司是硬维度，插入时漏填必须当场失败（而不是静默变成"谁都不见"）
-- ---------------------------------------------------------------------------
alter table org_unit         alter column company_id set not null;
alter table sys_user         alter column company_id set not null;
alter table employee         alter column company_id set not null;
alter table leave_record     alter column company_id set not null;
alter table condolence       alter column company_id set not null;
alter table process_instance alter column company_id set not null;
alter table salary_month     alter column company_id set not null;
alter table salary_record    alter column company_id set not null;
alter table perf_record      alter column company_id set not null;
alter table notice           alter column company_id set not null;
alter table party_fee        alter column company_id set not null;

-- ---------------------------------------------------------------------------
-- 5) 唯一约束切换（ADR-M002 / ADR-M006）
--    sys_user.username : 全局唯一 → (company_id, username)
--    salary_month.month: 全局唯一 → (company_id, month)
--    旧约束名由 PostgreSQL 自动生成，这里按"定义内容"查找删除，不猜名字。
-- ---------------------------------------------------------------------------
do $$
declare c record;
begin
  for c in
    select conname
      from pg_constraint
     where conrelid = 'sys_user'::regclass
       and contype = 'u'
       and pg_get_constraintdef(oid) = 'UNIQUE (username)'
  loop
    execute format('alter table sys_user drop constraint %I', c.conname);
  end loop;

  for c in
    select conname
      from pg_constraint
     where conrelid = 'salary_month'::regclass
       and contype = 'u'
       and pg_get_constraintdef(oid) = 'UNIQUE (month)'
  loop
    execute format('alter table salary_month drop constraint %I', c.conname);
  end loop;

  if not exists (select 1 from pg_constraint where conname = 'uq_sys_user_company_username') then
    alter table sys_user add constraint uq_sys_user_company_username unique (company_id, username);
  end if;

  if not exists (select 1 from pg_constraint where conname = 'uq_salary_month_company_month') then
    alter table salary_month add constraint uq_salary_month_company_month unique (company_id, month);
  end if;
end $$;

-- ---------------------------------------------------------------------------
-- 6) 角色种子：接待流程审批人所需的 3 个角色码（ADR-M005）
--    现有 7 条角色（DataInitializer:65-71）不含这三个，必须新增。
-- ---------------------------------------------------------------------------
insert into sys_role (code, name, data_scope, enabled)
select v.code, v.name, v.data_scope, true
  from (values ('OFFICE_DIRECTOR', '办公室主任',   'COMPANY'),
               ('OFFICE_DEPUTY',   '办公室副主任', 'COMPANY'),
               ('DEPT_LEADER',     '部门负责人',   'PROJECT')) as v(code, name, data_scope)
 where not exists (select 1 from sys_role r where r.code = v.code);

-- ---------------------------------------------------------------------------
-- 7) 公司显示名规范化：把 DataInitializer 早期写入的占位名对齐为真实公司名
--    事实：DataInitializer.run() 只在 userRepository.count() == 0 时才 seedDemoData()，
--          因此在已初始化的库上，仅改 Java 里的字符串不会修正历史行（开发库实测仍为占位名）。
--    为什么放迁移而不放 DataInitializer：除根因（写死的名字）外，历史脏值需要与"是否启动应用"
--          无关的确定性修正；生产库本来就是真实名，本节在生产上匹配不到而空转。
--    纪律：此处的 name 匹配仅用于"识别待修正的占位名"，匹配不到即空转；
--          绝不用名字反查公司 id（回填一律按 type='COMPANY' 取 id，见方案 §9.2）。
-- ---------------------------------------------------------------------------
update org_unit
   set name = '中国水利水电第六工程局有限公司南方公司'
 where type = 'COMPANY'
   and name = '示范建工集团南方公司';
