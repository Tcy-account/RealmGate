-- Phase 2：种子用户。Flyway 只跑一次（记录于 flyway_schema_history）。
-- 目的：让登录链路一启动就有可登录的账号，password_hash 必须是 BCrypt 哈希，绝不明文。
-- 约定：tenant_id 与 Phase 1 验证用的 X-Tenant-Id 对齐（alpha / beta）。

-- 1) 先 seed 租户（顺手补全 tenant 表，数据才完整）
INSERT INTO tenant (name, slug, status) VALUES
    ('Alpha 租户', 'alpha', 'ACTIVE'),
    ('Beta 租户',  'beta',  'ACTIVE')
ON CONFLICT (slug) DO NOTHING;   -- clean 重跑时不报唯一冲突

-- 2) 再 seed 用户（id / created_at / updated_at 都有默认值，不插）
INSERT INTO app_user
    (tenant_id, username, email, password_hash, display_name, status)
VALUES
    ('alpha', 'admin',  'admin@alpha.dev',  '$2a$10$WyErDzQStyI1dXqgarUWiuFHkz.FjgcFE1n/EGg0lgarDPviifaL.', 'Alpha Admin', 'ACTIVE'),
    ('beta',  'member', 'member@beta.dev',  '$2a$10$aO7ISG9wd5q8wSG8g4x.bO5BErwQNGhMWAlobtjcZ6pQvvBW3N0hG', 'Beta Member', 'ACTIVE')
ON CONFLICT (tenant_id, username) DO NOTHING;
