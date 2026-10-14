-- AI 写作台模型配置（单数行表：全库只保留一行，管理员在页面上维护一次即可）
-- 优先级：本表配置 > 环境变量 WORKBENCH_AI_BASE_URL / WORKBENCH_AI_MODEL / WORKBENCH_AI_API_KEY
create table if not exists ai_config (
    id bigserial primary key,
    base_url varchar(512),
    model varchar(128),
    api_key varchar(512),
    updated_by varchar(64),
    updated_at timestamp
);

comment on table ai_config is 'AI 写作台模型配置（单行）';
comment on column ai_config.base_url is 'OpenAI 兼容接口地址，例如 https://<host>/v1';
comment on column ai_config.model is '模型名，例如 agnes-2.5-flash';
comment on column ai_config.api_key is '模型密钥，仅服务端读取，不返回给浏览器';
