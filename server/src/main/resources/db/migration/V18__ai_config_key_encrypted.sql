-- V18：AI 模型密钥改为加密存储（修复「密钥明文落库」缺陷）。
--
-- 后端在保存时写入 ENC(<hex>) 密文（AES-256-GCM，见 AiConfigCrypto），密文比明文长，
-- varchar(512) 装不下长密钥的密文，因此放宽为 text。放宽是纯增量操作，不动任何存量数据。
--
-- 存量明文的迁移策略：不在此处批量改写。读取侧兼容历史明文（无 ENC 前缀原样放行），
-- 管理员下次在「模型设置」保存时自动加密回写——避免「启动即改数据」的不可逆动作，
-- 存量部署升级后无需停机、无需手工迁移脚本。
--
-- 加密口令来自环境变量 WORKBENCH_AI_CRYPTO_KEY（server/.env）；未配置时历史明文照常可读，
-- 但保存新密钥会被后端显式拒绝（绝不静默降级回明文存储）。

alter table ai_config alter column api_key type text;

comment on column ai_config.api_key is '模型密钥，加密存储为 ENC(<hex>)（口令 WORKBENCH_AI_CRYPTO_KEY），仅服务端解密使用，不返回给浏览器';
