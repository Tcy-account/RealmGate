-- Phase 8 管理后台：补齐跨租户 tenant 管理与 permission 目录管理所需的权限键，并挂 TENANT_ADMIN。
-- 原 V8 种子只含 user/role/project/task/member 域；tenant:* / permission:* 为本次新增。
-- tenant 管理是平台级（跨租户），permission 管理是原种子未覆盖的域。

INSERT INTO rbac_permission (tenant_id, perm_key, description)
SELECT t.slug, v.perm_key, v.description
FROM (VALUES ('alpha'), ('beta')) AS t(slug)
CROSS JOIN (VALUES
    ('tenant:read',   '查看租户'),
    ('tenant:manage', '管理租户'),
    ('permission:read',   '查看权限'),
    ('permission:manage', '管理权限')
) AS v(perm_key, description)
ON CONFLICT (tenant_id, perm_key) DO NOTHING;

-- 把上述新权限键关联到本租户 TENANT_ADMIN 角色
INSERT INTO rbac_role_permission (tenant_id, role_id, permission_id, created_at, updated_at)
SELECT r.tenant_id, r.id, p.id, now(), now()
FROM rbac_role r
CROSS JOIN rbac_permission p
WHERE r.role_name = 'TENANT_ADMIN' AND r.tenant_id = p.tenant_id
  AND p.perm_key IN ('tenant:read', 'tenant:manage', 'permission:read', 'permission:manage')
ON CONFLICT (tenant_id, role_id, permission_id) DO NOTHING;
