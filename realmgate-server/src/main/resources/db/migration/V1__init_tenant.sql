-- Phase 0 基础表：tenant（租户）。
-- 这是整个多租户体系的「根」表，本身不带 tenant_id（它就是租户的定义者）。
-- 后续所有业务表都以 tenant_id 关联回这里，并加 UNIQUE(tenant_id, 业务键) 约束。
CREATE TABLE tenant (
    id         BIGSERIAL PRIMARY KEY,
    name       VARCHAR(128) NOT NULL,
    slug       VARCHAR(64)  NOT NULL,
    status     VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_tenant_slug UNIQUE (slug)
);

COMMENT ON TABLE  tenant IS '平台租户';
COMMENT ON COLUMN tenant.slug  IS '租户唯一标识，用于 URL 路径，如 /api/tenants/alpha/...';
COMMENT ON COLUMN tenant.status IS 'ACTIVE / DISABLED';
