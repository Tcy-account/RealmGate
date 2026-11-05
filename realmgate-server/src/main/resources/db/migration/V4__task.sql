-- Phase 1：Task 业务表（项目下的任务）。tenant_id 来自 TenantEntity。
CREATE TABLE task (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   VARCHAR(64) NOT NULL,                 -- 继承 TenantEntity 的字段，必须显式建列
    project_id  BIGINT NOT NULL REFERENCES project(id), -- 外键：指向所属项目。用 Long 列，不建 JPA @ManyToOne
    slug        VARCHAR(64) NOT NULL,
    title       VARCHAR(128) NOT NULL,
    description VARCHAR(512),
    status      VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_task_tenant_project_slug UNIQUE (tenant_id, project_id, slug)  -- 行级隔离 + 业务唯一
);

COMMENT ON TABLE  task IS '项目下的任务';
COMMENT ON COLUMN task.project_id IS '所属项目（指向 project 表）';
COMMENT ON COLUMN task.slug IS '任务在租户+项目内的唯一短标识';
