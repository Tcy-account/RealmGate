-- Phase 8 续 · Audit 审计
-- 1) 审计日志表（平台级，按 tenant_id 隔离；写操作由 AOP 切面自动落盘）
CREATE TABLE IF NOT EXISTS audit_log (
    id            BIGSERIAL PRIMARY KEY,
    tenant_id     VARCHAR(64)  NOT NULL,
    actor         VARCHAR(128) NOT NULL,
    actor_id      BIGINT,
    action        VARCHAR(16)  NOT NULL,   -- CREATE / UPDATE / DELETE
    resource_type VARCHAR(32)  NOT NULL,   -- TENANT / USER / ROLE / PERMISSION / CLIENT
    resource_id   VARCHAR(128),
    detail        TEXT,
    ip_address    VARCHAR(64),
    created_at    TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_audit_log_tenant_created
    ON audit_log (tenant_id, created_at DESC);

-- 2) 补 audit:read 权限键（平台级，跨租户），挂 TENANT_ADMIN。
--    审计的「写」由切面自动完成，无需独立权限；仅「查看」受 audit:read 保护。
INSERT INTO rbac_permission (tenant_id, perm_key, description)
SELECT t.slug, v.perm_key, v.description
FROM (VALUES ('alpha'), ('beta')) AS t(slug)
CROSS JOIN (VALUES
    ('audit:read', '查看审计日志')
) AS v(perm_key, description)
ON CONFLICT (tenant_id, perm_key) DO NOTHING;

INSERT INTO rbac_role_permission (tenant_id, role_id, permission_id, created_at, updated_at)
SELECT r.tenant_id, r.id, p.id, now(), now()
FROM rbac_role r
CROSS JOIN rbac_permission p
WHERE r.role_name = 'TENANT_ADMIN' AND r.tenant_id = p.tenant_id
  AND p.perm_key IN ('audit:read')
ON CONFLICT (tenant_id, role_id, permission_id) DO NOTHING;
