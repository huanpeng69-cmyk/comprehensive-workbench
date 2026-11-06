-- V19：审计日志补全治理底座字段（result / result_code / trace_id）。
--
-- 背景（治理底座 P1）：既有 audit_log 只记了「操作人 / 动作 / 对象 / 备注 / 时间」，
-- 没有操作结果，也没法把一条审计和一次请求的日志、链路对上。本次：
--   1) result       —— 操作结果枚举（SUCCESS / DENIED / FAILURE），可空，旧数据为 NULL 表示「未标注」；
--   2) result_code  —— 结果对应的业务/HTTP 码（如 403 / 400 / 500），可空；
--   3) trace_id     —— 全链路跟踪 id，与 MDC 的 traceId、响应头 X-Request-Id 一致（治理底座 P0）。
--
-- 全部可空、只增列加索引，纯增量操作，不迁移存量、不重建表；回滚见 V19__rollback.sql。
alter table audit_log add column if not exists result      varchar(16);
alter table audit_log add column if not exists result_code varchar(64);
alter table audit_log add column if not exists trace_id    varchar(64);

-- 按 trace_id 反查一次请求的完整审计轨迹；并发查询高频，建索引。
create index if not exists idx_audit_log_trace on audit_log (trace_id);