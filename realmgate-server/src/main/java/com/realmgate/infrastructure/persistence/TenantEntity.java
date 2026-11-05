package com.realmgate.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.Setter;

/**
 * 租户业务实体的基类（计划第 19/20 节：Shared Database + tenant_id）。
 * 凡是租户隔离的数据表，其实体都继承此类，强制携带 tenantId，
 * 配合 Repository 的 WHERE tenant_id = ? 实现数据空间隔离。
 *
 * <p>注意：tenant 表本身（它是租户的定义者）不要继承此类，否则会自引用。</p>
 */
@MappedSuperclass
@Getter
@Setter
public abstract class TenantEntity extends BaseEntity {

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;
}
