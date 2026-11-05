package com.realmgate.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * 审计日志实体，映射 {@code audit_log} 表。
 *
 * <p>刻意<b>不继承</b> {@code TenantEntity}：审计行的 tenant_id 由切面在写入时显式赋值
 * （= 当前操作人 JWT 的 tenant_id），而非依赖 TenantContext 自动注入——这样即便在跨租户
 * 管理端点（如建租户）被触发，也能正确记录操作人所属租户，不会被空 TenantContext 坑到。</p>
 */
@Entity
@Table(name = "audit_log")
@Getter
@Setter
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    /** 操作人（JWT preferred_username）。 */
    @Column(name = "actor", nullable = false, length = 128)
    private String actor;

    /** 操作人数字 id；JWT 默认不带，暂留空，便于未来扩展。 */
    @Column(name = "actor_id")
    private Long actorId;

    /** CREATE / UPDATE / DELETE。 */
    @Column(name = "action", nullable = false, length = 16)
    private String action;

    /** TENANT / USER / ROLE / PERMISSION / CLIENT。 */
    @Column(name = "resource_type", nullable = false, length = 32)
    private String resourceType;

    /** 目标资源 id（来自 @PathVariable，或 create 时从返回值反射取 id）。 */
    @Column(name = "resource_id", length = 128)
    private String resourceId;

    /** 非敏感摘要（已屏蔽密码等字段）。 */
    @Column(name = "detail", columnDefinition = "text")
    private String detail;

    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
