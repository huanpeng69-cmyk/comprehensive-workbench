-- V11 回滚：把慰问金「二级单位工会审批」节点改回写死的用户名指派，并撤销 V11 引入的角色与授予。
-- 只回退 V11 引入的内容；V10 加的列与约束另见 V10__rollback.sql。幂等，可重复执行。
-- 注意（如实记账）：回滚会恢复 V11 想消除的隐患——`USER fangjing` 在用户名复合唯一后会跨单位派单。
-- 反向脚本执行顺序：先撤授予 → 再改回节点 → 最后按引用情况删角色。

-- ---------------------------------------------------------------------------
-- 1) 撤销本迁移的承接授予（只撤我们加的那一条：username = 'fangjing' 的账号）
-- ---------------------------------------------------------------------------
delete from sys_user_role
 where role_id = (select id from sys_role where code = 'UNION_LEADER')
   and user_id in (select id from sys_user where username = 'fangjing');

-- ---------------------------------------------------------------------------
-- 2) 节点改回 USER / fangjing（作用范围与 V11 对称：同一批限定条件）
-- ---------------------------------------------------------------------------
update process_node
   set assignee_type = 'USER',
       assignee_expr = 'fangjing'
 where node_type = 'APPROVAL'
   and assignee_type = 'ROLE'
   and assignee_expr = 'UNION_LEADER'
   and definition_id in (select id from process_definition where biz_type = 'condolence');

-- ---------------------------------------------------------------------------
-- 3) 删角色：只有无人引用时才删；若运维在 V11 之后又给别的账号授了这个角色，
--    则保留角色并告警（不静默删除运维数据）。
-- ---------------------------------------------------------------------------
do $$
declare
  n_ref bigint;
  n_node bigint;
begin
  select count(*) into n_ref
    from sys_user_role x
    join sys_role r on r.id = x.role_id
   where r.code = 'UNION_LEADER';

  if n_ref = 0 then
    delete from sys_role where code = 'UNION_LEADER';
    raise notice 'V11 回滚：UNION_LEADER 已删除（无引用）';
  else
    raise warning 'V11 回滚：UNION_LEADER 仍被 % 个账号引用，保留该角色（请在各单位的成员管理界面自行处理）', n_ref;
  end if;

  select count(*) into n_node
    from process_node pn
    join process_definition pd on pd.id = pn.definition_id
   where pd.biz_type = 'condolence' and pn.seq = 2
     and pn.assignee_type = 'USER' and pn.assignee_expr = 'fangjing';

  if n_node <> 1 then
    raise warning 'V11 回滚：condolence 2 号节点未回到 USER/fangjing（匹配 % 行），请人工核对', n_node;
  else
    raise notice 'V11 回滚 ok: condolence 2 号节点 = USER/fangjing';
  end if;
end $$;
