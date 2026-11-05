package com.realmgate.admin;

import com.realmgate.audit.AuditLog;
import com.realmgate.audit.AuditLogRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 审计日志查询服务（只读）。租户隔离由调用方（控制器）从 JWT 取出 tenant_id 后传入，
 * 与 User/Role/Permission 管理端点的租户隔离口径一致。
 */
@Service
@RequiredArgsConstructor
public class AuditAdminService {

    private final AuditLogRepository repository;

    public List<AuditLog> search(String tenantId, String action, String resourceType) {
        return repository.search(tenantId, action, resourceType);
    }
}
