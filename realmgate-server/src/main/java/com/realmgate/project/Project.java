package com.realmgate.project;

import com.realmgate.infrastructure.persistence.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "project")          // 表名，和 V2 SQL 一致
@Getter
@Setter
public class Project extends TenantEntity {   // 继承 → 自动拥有 id / created_at / updated_at / tenant_id
    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Column(name = "slug", nullable = false, length = 64)
    private String slug;

    @Column(name = "description", length = 512)
    private String description;

    @Column(name = "status", nullable = false, length = 16)
    private String status = "ACTIVE";   // 默认值，和 SQL DEFAULT 对齐
}
