-- Phase 1：Member 业务表（项目成员关系）。tenant_id 来自 TenantEntity。
CREATE TABLE member (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   VARCHAR(64) NOT NULL,                 -- 继承 TenantEntity 的字段，必须显式建列
    project_id  BIGINT NOT NULL REFERENCES project(id),  -- 外键：指向所属项目
    user_id     BIGINT NOT NULL REFERENCES app_user(id), -- 外键：指向用户（app_user，避开保留字 user）
    role        VARCHAR(32) NOT NULL DEFAULT 'MEMBER',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_member_tenant_project_user UNIQUE (tenant_id, project_id, user_id)  -- 一个用户对同一项目只一条成员记录
);

COMMENT ON TABLE  member IS '项目成员关系';
COMMENT ON COLUMN member.role IS '成员在项目内的角色（OWNER/MEMBER/VIEWER）';
COMMENT ON COLUMN member.user_id IS '指向 app_user 表的用户';
