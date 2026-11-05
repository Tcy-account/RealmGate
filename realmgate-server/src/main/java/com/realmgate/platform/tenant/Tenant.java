package com.realmgate.platform.tenant;

import com.realmgate.infrastructure.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 平台租户实体，映射根表 `tenant`（它本身是租户的定义者，故不继承 TenantEntity，避免自引用 tenant_id）。
 * id / created_at / updated_at 由 BaseEntity 提供。
 */
@Entity
@Table(name = "tenant")
@Getter
@Setter
public class Tenant extends BaseEntity {

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Column(name = "slug", nullable = false, length = 64, unique = true)
    private String slug;

    @Column(name = "status", nullable = false, length = 16)
    private String status = "ACTIVE";
}
