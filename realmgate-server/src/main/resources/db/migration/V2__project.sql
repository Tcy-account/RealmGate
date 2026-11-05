-- Phase 1：Project 业务表。tenant_id 来自 TenantEntity，这里显式声明。
CREATE TABLE project (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   VARCHAR(64) NOT NULL,                 -- 继承 TenantEntity 的字段，必须显式建列
    name        VARCHAR(128) NOT NULL,
    slug        VARCHAR(64)  NOT NULL,
    description VARCHAR(512),
    status      VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_project_tenant_slug UNIQUE (tenant_id, slug)  -- 行级隔离核心约束
);

COMMENT ON TABLE  project IS '租户下的项目';
COMMENT ON COLUMN project.slug IS '项目在租户内的唯一短标识';
