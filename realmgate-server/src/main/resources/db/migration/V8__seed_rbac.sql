-- Phase 5 RBAC 种子：给 alpha/admin 与 beta/member 挂 TENANT_ADMIN，并铺一份权限目录。
-- 所有关联用子查询按 tenant 关联，避开 BIGSERIAL 自增 id（Flyway 不可预测 id）。

-- 1) 角色（最小闭环只 TENANT_ADMIN；PROJECT_MANAGER/MEMBER/VIEWER 以后再加）
INSERT INTO rbac_role (tenant_id, role_name, description) VALUES
    ('alpha', 'TENANT_ADMIN', '租户管理员'),
    ('beta',  'TENANT_ADMIN', '租户管理员')
ON CONFLICT (tenant_id, role_name) DO NOTHING;

-- 2) 权限目录：每个租户各一份，用 tenant × 具体 key 笛卡尔展开（* 不展开，必须列具体 key）
INSERT INTO rbac_permission (tenant_id, perm_key, description)
SELECT t.slug, v.perm_key, v.description
FROM (VALUES ('alpha'), ('beta')) AS t(slug)
CROSS JOIN (VALUES
    ('project:read',   '查看项目'),
    ('project:create', '创建项目'),
    ('project:update', '更新项目'),
    ('project:delete', '删除项目'),
    ('task:read',      '查看任务'),
    ('task:create',    '创建任务'),
    ('task:update',     '更新任务'),
    ('task:delete',     '删除任务'),
    ('member:read',    '查看成员'),
    ('member:invite',  '邀请成员'),
    ('member:manage',  '管理成员（改角色/移除）'),
    ('user:read',      '查看用户'),
    ('user:manage',    '管理用户'),
    ('role:read',      '查看角色'),
    ('role:manage',    '管理角色')
) AS v(perm_key, description)
ON CONFLICT (tenant_id, perm_key) DO NOTHING;

-- 3) 角色-权限关联：本租户 TENANT_ADMIN 关联本租户所有 permission
INSERT INTO rbac_role_permission (tenant_id, role_id, permission_id, created_at, updated_at)
SELECT r.tenant_id, r.id, p.id, now(), now()
FROM rbac_role r
CROSS JOIN rbac_permission p
WHERE r.role_name = 'TENANT_ADMIN' AND r.tenant_id = p.tenant_id
ON CONFLICT (tenant_id, role_id, permission_id) DO NOTHING;

-- 4) 用户-角色关联：种子用户挂本租户 TENANT_ADMIN
INSERT INTO rbac_user_role (tenant_id, user_id, role_id, created_at, updated_at)
SELECT u.tenant_id, u.id, r.id, now(), now()
FROM app_user u
JOIN rbac_role r ON r.tenant_id = u.tenant_id AND r.role_name = 'TENANT_ADMIN'
WHERE (u.tenant_id = 'alpha' AND u.username = 'admin')
   OR (u.tenant_id = 'beta'  AND u.username = 'member')
ON CONFLICT (tenant_id, user_id, role_id) DO NOTHING;
