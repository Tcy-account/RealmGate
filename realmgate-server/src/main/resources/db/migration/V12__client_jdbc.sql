-- Phase 8 续：Client 管理 JDBC 化
-- 1) 建 oauth2_registered_client 表（SAS 1.5.1 官方 schema，13 列，来自 jar 内
--    org/springframework/security/oauth2/server/authorization/client/oauth2-registered-client-schema.sql）
--    用 IF NOT EXISTS 保证本地重跑（Flyway 失败回滚后）不至于建表冲突。
CREATE TABLE IF NOT EXISTS oauth2_registered_client (
    id varchar(100) NOT NULL,
    client_id varchar(100) NOT NULL,
    client_id_issued_at timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,
    client_secret varchar(200) DEFAULT NULL,
    client_secret_expires_at timestamp DEFAULT NULL,
    client_name varchar(200) NOT NULL,
    client_authentication_methods varchar(1000) NOT NULL,
    authorization_grant_types varchar(1000) NOT NULL,
    redirect_uris varchar(1000) DEFAULT NULL,
    post_logout_redirect_uris varchar(1000) DEFAULT NULL,
    scopes varchar(1000) NOT NULL,
    client_settings varchar(2000) NOT NULL,
    token_settings varchar(2000) NOT NULL,
    PRIMARY KEY (id)
);

-- 2) 补 client:read / client:manage 权限键（平台级，跨租户），挂 TENANT_ADMIN。
--    与 V11 的 tenant:* / permission:* 同属平台级管理权限，按租户各铺一份。
INSERT INTO rbac_permission (tenant_id, perm_key, description)
SELECT t.slug, v.perm_key, v.description
FROM (VALUES ('alpha'), ('beta')) AS t(slug)
CROSS JOIN (VALUES
    ('client:read',   '查看 OAuth 客户端'),
    ('client:manage', '管理 OAuth 客户端')
) AS v(perm_key, description)
ON CONFLICT (tenant_id, perm_key) DO NOTHING;

INSERT INTO rbac_role_permission (tenant_id, role_id, permission_id, created_at, updated_at)
SELECT r.tenant_id, r.id, p.id, now(), now()
FROM rbac_role r
CROSS JOIN rbac_permission p
WHERE r.role_name = 'TENANT_ADMIN' AND r.tenant_id = p.tenant_id
  AND p.perm_key IN ('client:read', 'client:manage')
ON CONFLICT (tenant_id, role_id, permission_id) DO NOTHING;
