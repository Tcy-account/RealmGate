-- Phase 1：User 业务表（租户内的用户账户）。tenant_id 来自 TenantEntity。
-- 表名用 app_user 而非 user：user 是 PostgreSQL 保留字，JPA 默认不加引号会触发 SQL 语法错误。
CREATE TABLE app_user (
    id            BIGSERIAL PRIMARY KEY,
    tenant_id     VARCHAR(64) NOT NULL,                 -- 继承 TenantEntity 的字段，必须显式建列
    username      VARCHAR(64) NOT NULL,
    email         VARCHAR(128) NOT NULL,
    password_hash VARCHAR(128) NOT NULL DEFAULT '',      -- Phase 1 占位，Phase 2 接 BCrypt
    display_name  VARCHAR(128),
    status        VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_user_tenant_username UNIQUE (tenant_id, username),
    CONSTRAINT uk_user_tenant_email    UNIQUE (tenant_id, email)
);

COMMENT ON TABLE  app_user IS '租户内的用户账户';
COMMENT ON COLUMN app_user.username IS '租户内唯一登录名';
COMMENT ON COLUMN app_user.email IS '租户内唯一邮箱';
COMMENT ON COLUMN app_user.password_hash IS '密码哈希，Phase 2 前为占位，绝不进响应';
