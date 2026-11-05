package com.realmgate.audit;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    /** 按租户 + 时间倒序查询（默认列表）。 */
    List<AuditLog> findByTenantIdOrderByCreatedAtDesc(String tenantId);

    /**
     * 带可选过滤的查询：action / resourceType 为空表示不限制。
     * 用于管理后台审计视图的筛选。
     */
    @Query("""
           SELECT a FROM AuditLog a
           WHERE a.tenantId = :tenant
             AND (:action IS NULL OR a.action = :action)
             AND (:resourceType IS NULL OR a.resourceType = :resourceType)
           ORDER BY a.createdAt DESC
           """)
    List<AuditLog> search(@Param("tenant") String tenant,
                          @Param("action") String action,
                          @Param("resourceType") String resourceType);
}
