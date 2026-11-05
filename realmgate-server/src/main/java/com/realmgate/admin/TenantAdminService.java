package com.realmgate.admin;

import com.realmgate.common.api.ApiResponse;
import com.realmgate.platform.tenant.Tenant;
import com.realmgate.platform.tenant.TenantRepository;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 租户管理（平台级、跨租户）。tenant 表是根表，不挂 tenant_id，故本服务不读 TenantContext。
 * 仅 TENANT_ADMIN（持 tenant:read / tenant:manage）可访问，由控制器 @PreAuthorize 保证。
 */
@Service
@RequiredArgsConstructor
public class TenantAdminService {

    private final TenantRepository tenantRepository;

    public ApiResponse<List<Tenant>> list() {
        return ApiResponse.success(tenantRepository.findAll());
    }

    @Transactional
    public ApiResponse<Tenant> create(Tenant tenant) {
        if (tenant.getSlug() == null || tenant.getSlug().isBlank()) {
            return ApiResponse.error(400, "slug 必填");
        }
        if (tenantRepository.existsBySlug(tenant.getSlug())) {
            return ApiResponse.error(409, "slug 已存在");
        }
        if (tenant.getStatus() == null || tenant.getStatus().isBlank()) {
            tenant.setStatus("ACTIVE");
        }
        return ApiResponse.success(tenantRepository.save(tenant));
    }

    public ApiResponse<Tenant> getById(Long id) {
        return tenantRepository.findById(id)
                .map(ApiResponse::success)
                .orElse(ApiResponse.error(404, "租户不存在"));
    }

    @Transactional
    public ApiResponse<Tenant> update(Long id, Tenant req) {
        Tenant tenant = tenantRepository.findById(id).orElse(null);
        if (tenant == null) {
            return ApiResponse.error(404, "租户不存在");
        }
        if (req.getName() != null) {
            tenant.setName(req.getName());
        }
        if (req.getStatus() != null) {
            tenant.setStatus(req.getStatus());
        }
        // slug 作为租户标识，不允许修改
        tenant.setUpdatedAt(Instant.now());
        return ApiResponse.success(tenantRepository.save(tenant));
    }

    @Transactional
    public ApiResponse<Void> delete(Long id) {
        if (!tenantRepository.existsById(id)) {
            return ApiResponse.error(404, "租户不存在");
        }
        tenantRepository.deleteById(id);
        return ApiResponse.success(null);
    }
}
