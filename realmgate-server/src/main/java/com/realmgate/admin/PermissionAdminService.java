package com.realmgate.admin;

import com.realmgate.common.api.ApiResponse;
import com.realmgate.identity.role.Permission;
import com.realmgate.identity.role.PermissionRepository;
import com.realmgate.identity.role.RolePermissionRepository;
import com.realmgate.infrastructure.security.TenantContext;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 权限目录管理（租户级）。删除权限时同步清理角色-权限孤儿关联。
 */
@Service
@RequiredArgsConstructor
public class PermissionAdminService {

    private final PermissionRepository permissionRepository;
    private final RolePermissionRepository rolePermissionRepository;

    public ApiResponse<List<Permission>> list() {
        String tid = tenant();
        if (tid == null) {
            return ApiResponse.error(400, "缺少租户上下文");
        }
        return ApiResponse.success(permissionRepository.findAllByTenantId(tid));
    }

    public ApiResponse<Permission> create(Permission permission) {
        String tid = tenant();
        if (tid == null) {
            return ApiResponse.error(400, "缺少租户上下文");
        }
        if (permission.getPermKey() == null || permission.getPermKey().isBlank()) {
            return ApiResponse.error(400, "perm_key 必填");
        }
        if (permissionRepository.findByTenantIdAndPermKey(tid, permission.getPermKey()).isPresent()) {
            return ApiResponse.error(409, "权限键已存在");
        }
        permission.setTenantId(tid);
        return ApiResponse.success(permissionRepository.save(permission));
    }

    public ApiResponse<Permission> getById(Long id) {
        String tid = tenant();
        if (tid == null) {
            return ApiResponse.error(400, "缺少租户上下文");
        }
        return permissionRepository.findByIdAndTenantId(id, tid)
                .map(ApiResponse::success)
                .orElse(ApiResponse.error(404, "权限不存在或不属于当前租户"));
    }

    public ApiResponse<Permission> update(Long id, Permission req) {
        String tid = tenant();
        if (tid == null) {
            return ApiResponse.error(400, "缺少租户上下文");
        }
        Permission permission = permissionRepository.findByIdAndTenantId(id, tid).orElse(null);
        if (permission == null) {
            return ApiResponse.error(404, "权限不存在或不属于当前租户");
        }
        if (req.getDescription() != null) {
            permission.setDescription(req.getDescription());
        }
        permission.setUpdatedAt(Instant.now());
        return ApiResponse.success(permissionRepository.save(permission));
    }

    public ApiResponse<Void> delete(Long id) {
        String tid = tenant();
        if (tid == null) {
            return ApiResponse.error(400, "缺少租户上下文");
        }
        Permission permission = permissionRepository.findByIdAndTenantId(id, tid).orElse(null);
        if (permission == null) {
            return ApiResponse.error(404, "权限不存在或不属于当前租户");
        }
        rolePermissionRepository.deleteByPermissionIdAndTenantId(id, tid); // 清孤儿关联
        permissionRepository.delete(permission);
        return ApiResponse.success(null);
    }

    private String tenant() {
        return TenantContext.get();
    }
}
