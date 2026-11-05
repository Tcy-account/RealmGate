-- Phase 5 RBAC：租户级角色/权限引擎四表。
-- 命名前缀 rbac_：role 是 PostgreSQL 保留字（与 user 同理，不能裸用表名），故统一加前缀。
-- 所有表继承 TenantEntity 的 tenant_id，且所有唯一约束都带 tenant_id —— 这是行级隔离的硬保证。
-- 说明：permission 虽然概念上是全局权限目录，但为遵守「RBAC 查询必带 tenantId」的铁律，
--       这里把 role / permission / 两张关联表全部 tenant 化，每个租户各持一份，不跨租户共享。

-- 1) 角色表：role_name 形如 TENANT_ADMIN / PROJECT_MANAGER / MEMBER / VIEWER
CREATE TABLE rbac_role (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   VARCHAR(64) NOT NULL,
    role_name   VARCHAR(64) NOT NULL,
    description VARCHAR(256),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_role_tenant_name UNIQUE (tenant_id, role_name)
);

COMMENT ON TABLE  rbac_role IS '租户级角色';
COMMENT ON COLUMN rbac_role.role_name IS '角色标识（TENANT_ADMIN / PROJECT_MANAGER / MEMBER / VIEWER）';

-- 2) 权限表：perm_key 形如 project:delete / task:read，是 @PreAuthorize 比对的精确字符串
CREATE TABLE rbac_permission (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   VARCHAR(64) NOT NULL,
    perm_key    VARCHAR(128) NOT NULL,
    description VARCHAR(256),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_perm_tenant_key UNIQUE (tenant_id, perm_key)
);

COMMENT ON TABLE  rbac_permission IS '权限目录';
COMMENT ON COLUMN rbac_permission.perm_key IS '权限键，必须精确匹配，通配符 * 不会自动展开';

-- 3) 用户-角色关联：app_user 与 rbac_role 的多对多
CREATE TABLE rbac_user_role (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   VARCHAR(64) NOT NULL,
    user_id     BIGINT NOT NULL REFERENCES app_user(id),
    role_id     BIGINT NOT NULL REFERENCES rbac_role(id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_ur_tenant_user_role UNIQUE (tenant_id, user_id, role_id)
);

COMMENT ON TABLE  rbac_user_role IS '用户-角色关联';
COMMENT ON COLUMN rbac_user_role.user_id IS '指向 app_user（避开保留字 user）';

-- 4) 角色-权限关联：rbac_role 与 rbac_permission 的多对多
CREATE TABLE rbac_role_permission (
    id            BIGSERIAL PRIMARY KEY,
    tenant_id     VARCHAR(64) NOT NULL,
    role_id       BIGINT NOT NULL REFERENCES rbac_role(id),
    permission_id BIGINT NOT NULL REFERENCES rbac_permission(id),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_rp_tenant_role_perm UNIQUE (tenant_id, role_id, permission_id)
);

COMMENT ON TABLE  rbac_role_permission IS '角色-权限关联';
