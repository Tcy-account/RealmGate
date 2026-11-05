package com.realmgate.admin;

import com.realmgate.audit.AuditLog;
import com.realmgate.common.api.ApiResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 审计日志查询端点（平台级，按租户隔离）。仅 {@code audit:read} 可访问。
 *
 * <p>写操作不在此处——它由 {@code AuditAspect} 在所有 admin 变更方法成功后自动完成。</p>
 */
@RestController
@RequestMapping("/api/admin/audits")
@RequiredArgsConstructor
public class AuditAdminController {

    private final AuditAdminService service;

    @GetMapping
    @PreAuthorize("hasAuthority('audit:read')")
    public ApiResponse<List<AuditLog>> list(
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String resourceType) {
        String tenantId = currentTenant();
        if (tenantId == null) {
            return ApiResponse.error(401, "无法定位租户");
        }
        // 归一化：允许传 create/update/delete 小写；空串视为不限
        String a = (action == null || action.isBlank()) ? null : action.toUpperCase();
        String rt = (resourceType == null || resourceType.isBlank()) ? null : resourceType.toUpperCase();
        return ApiResponse.success(service.search(tenantId, a, rt));
    }

    private String currentTenant() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return null;
        Object principal = auth.getPrincipal();
        if (principal instanceof Jwt jwt) {
            return jwt.getClaimAsString("tenant_id");
        }
        return null;
    }
}
