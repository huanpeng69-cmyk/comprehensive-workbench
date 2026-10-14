-- 阶段 2 补充：慰问金「二级单位工会审批」节点改为**单位内角色**（ADR-M005 / ADR-M008 遗留项①）
-- 背景：V8__init_condolence.sql:34 把该节点写死为 assignee_type='USER' + assignee_expr='fangjing'。
--       V10 之后 sys_user.username 改为 (company_id, username) 复合唯一 ⇒「按用户名指派」会在别的单位
--       命中同名账号（跨单位派单），或在该单位解析不到人（任务无人可审、流程卡死）。
-- 纪律：
--   1) 不改 V8 —— 它已应用到开发库，改已应用的迁移会让 Flyway 校验和失败；这里用 UPDATE 就地改正；
--   2) 节点定位不按名字反查：按 process_definition.biz_type + process_node.seq 精确定位；
--   3) 「承接原有写死指派」只按 username 匹配**账号**，company_id 取自账号自身，绝不做公司名匹配；
--   4) 全部语句幂等，可重复执行。
-- 反向脚本：server/src/main/resources/db/rollback/V11__rollback.sql（手工执行，不参与 Flyway 扫描）

-- ---------------------------------------------------------------------------
-- 1) 角色种子：二级单位工会审批所需的公司级角色码
-- ---------------------------------------------------------------------------
insert into sys_role (code, name, data_scope, enabled)
select 'UNION_LEADER', '工会主席', 'COMPANY', true
 where not exists (select 1 from sys_role where code = 'UNION_LEADER');

-- ---------------------------------------------------------------------------
-- 2) 节点切换：condolence 的 2 号审批节点 USER/fangjing → ROLE/UNION_LEADER
--    作用范围被 4 个条件同时锁定（节点类型 + 原指派类型 + 原用户名 + 慰问金定义），
--    避免误伤其它流程的 USER 节点。
-- ---------------------------------------------------------------------------
update process_node
   set assignee_type = 'ROLE',
       assignee_expr = 'UNION_LEADER'
 where node_type = 'APPROVAL'
   and assignee_type = 'USER'
   and assignee_expr = 'fangjing'
   and definition_id in (select id from process_definition where biz_type = 'condolence');

-- ---------------------------------------------------------------------------
-- 3) 承接授予：把原写死审批人的账号接到新角色上（按账号自身所属单位，不跨单位）
--    说明：这一步是「迁移原有写死指派」的一次性承接，不是按名字识别公司；
--          今后新增/调整工会审批人一律走成员管理，不再由迁移脚本改写。
-- ---------------------------------------------------------------------------
insert into sys_user_role (user_id, role_id)
select u.id, r.id
  from sys_user u
  cross join sys_role r
 where u.username = 'fangjing'
   and r.code = 'UNION_LEADER'
   and not exists (select 1 from sys_user_role x where x.user_id = u.id and x.role_id = r.id);

-- ---------------------------------------------------------------------------
-- 4) 自检：节点必须真的切过去了（防"看起来成功其实没匹配到行"）
-- ---------------------------------------------------------------------------
do $$
declare
  n_role bigint;
  n_node bigint;
  n_grant bigint;
begin
  select count(*) into n_role from sys_role where code = 'UNION_LEADER';
  if n_role <> 1 then
    raise exception 'V11 失败：UNION_LEADER 角色应有 1 条，实际 % 条', n_role;
  end if;

  select count(*) into n_node
    from process_node pn
    join process_definition pd on pd.id = pn.definition_id
   where pd.biz_type = 'condolence' and pn.seq = 2
     and pn.assignee_type = 'ROLE' and pn.assignee_expr = 'UNION_LEADER';
  if n_node <> 1 then
    raise exception 'V11 失败：condolence 2 号节点未切换为 ROLE/UNION_LEADER（匹配 % 行）', n_node;
  end if;

  -- 存量库里没有 fangjing 账号也算正常（新装库由 DataInitializer 授予），这里只做提示
  select count(*) into n_grant
    from sys_user_role x
    join sys_role r on r.id = x.role_id
   where r.code = 'UNION_LEADER';
  raise notice 'V11 ok: 节点已切换为 ROLE/UNION_LEADER，UNION_LEADER 已授予 % 个账号', n_grant;
end $$;
