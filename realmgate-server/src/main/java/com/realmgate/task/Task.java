package com.realmgate.task;

import com.realmgate.infrastructure.persistence.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "task")              // 表名，和 V4 SQL 一致
@Getter
@Setter
public class Task extends TenantEntity {   // 继承 → 自动拥有 id / created_at / updated_at / tenant_id
    @Column(name = "project_id", nullable = false)
    private Long projectId;        // 外键用 Long 列，不建 JPA @ManyToOne（避免 N+1/级联/代理坑）

    @Column(name = "slug", nullable = false, length = 64)
    private String slug;

    @Column(name = "title", nullable = false, length = 128)
    private String title;

    @Column(name = "description", length = 512)
    private String description;

    @Column(name = "status", nullable = false, length = 16)
    private String status = "OPEN";   // 默认值，和 SQL DEFAULT 对齐
}
