-- V17：工资明细新增「其他假别扣款」（人工填写项）。
--
-- 背景：年假全额发放、事假按日工资全额扣除，两者都由公式自动算；
-- 病假及其他假别不对应固定扣款规则，改由劳资人员在工资页手工填写扣款金额，
-- 因此需要一个独立字段持久化该输入值。
--
-- 该列与其余金额列口径一致：varchar 存储，兼容「=公式」文本（NumLike）。
-- 为空表示未填写（按 0 处理，页面会提示待填）。

alter table salary_record add column if not exists leave_deduct varchar(64);

comment on column salary_record.leave_deduct is '其他假别扣款（病假等无固定规则的假别，人工填写；空=未填）';
