package com.realmgate.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * 所有持久化实体的基类（计划第 20 节）。
 * 平台级实体（如 tenant 表本身）使用它；租户业务实体再继承 {@link TenantEntity} 追加 tenantId。
 *
 * <p>使用 @MappedSuperclass：字段映射到子类的表，本身不是一张表。</p>
 */
@MappedSuperclass
@Getter
@Setter
public abstract class BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
