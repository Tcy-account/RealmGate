package com.realmgate.admin;

import com.realmgate.common.api.ApiResponse;
import com.realmgate.identity.role.Permission;
import com.realmgate.identity.role.PermissionRepository;
import com.realmgate.identity.role.Role;
import com.realmgate.identity.role.RolePermission;
import com.realmgate.identity.role.RolePermissionRepository;
import com.realmgate.identity.role.RoleRepository;
import com.realmgate.infrastructure.security.TenantContext;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 角色管理（租户级）。含角色-权限集的整集替换（先删后插），供管理后台勾选权限用。
 */
@Service
@RequiredArgsConstructor
public class RoleAdminService {

    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final RolePermissionRepository rolePermissionRepository;

    public ApiResponse<List<Role>> list() {
        String tid = tenant();
        if (tid == null) {
            return ApiResponse.error(400, "缺少租户上下文");
        }
        return ApiResponse.success(roleRepository.findAllByTenantId(tid));
    }

    @Transactional
    public ApiResponse<Role> create(Role role) {
        String tid = tenant();
        if (tid == null) {
            return ApiResponse.error(400, "缺少租户上下文");
        }
        if (role.getRoleName() == null || role.getRoleName().isBlank()) {
            return ApiResponse.error(400, "role_name 必填");
        }
        if (roleRepository.findByTenantIdAndRoleName(tid, role.getRoleName()).isPresent()) {
            return ApiResponse.error(409, "角色名已存在");
        }
        role.setTenantId(tid);
        return ApiResponse.success(roleRepository.save(role));
    }

    public ApiResponse<Role> getById(Long id) {
        String tid = tenant();
        if (tid == null) {
            return ApiResponse.error(400, "缺少租户上下文");
        }
        return roleRepository.findByIdAndTenantId(id, tid)
                .map(ApiResponse::success)
                .orElse(ApiResponse.error(404, "角色不存在或不属于当前租户"));
    }

    public ApiResponse<Role> update(Long id, Role req) {
        String tid = tenant();
        if (tid == null) {
            return ApiResponse.error(400, "缺少租户上下文");
        }
        Role role = roleRepository.findByIdAndTenantId(id, tid).orElse(null);
        if (role == null) {
            return ApiResponse.error(404, "角色不存在或不属于当前租户");
        }
        if (req.getDescription() != null) {
            role.setDescription(req.getDescription());
        }
        role.setUpdatedAt(Instant.now());
        return ApiResponse.success(roleRepository.save(role));
    }

    @Transactional
    public ApiResponse<Void> delete(Long id) {
        String tid = tenant();
        if (tid == null) {
            return ApiResponse.error(400, "缺少租户上下文");
        }
        Role role = roleRepository.findByIdAndTenantId(id, tid).orElse(null);
        if (role == null) {
            return ApiResponse.error(404, "角色不存在或不属于当前租户");
        }
        rolePermissionRepository.deleteByRoleIdAndTenantId(id, tid); // 清角色-权限关联
        roleRepository.delete(role);
        return ApiResponse.success(null);
    }

    public ApiResponse<List<Permission>> permissions(Long id) {
        String tid = tenant();
        if (tid == null) {
            return ApiResponse.error(400, "缺少租户上下文");
        }
        if (roleRepository.findByIdAndTenantId(id, tid).isEmpty()) {
            return ApiResponse.error(404, "角色不存在或不属于当前租户");
        }
        List<RolePermission> rps = rolePermissionRepository.findByRoleIdInAndTenantId(List.of(id), tid);
        List<Long> permIds = rps.stream().map(RolePermission::getPermissionId).toList();
        if (permIds.isEmpty()) {
            return ApiResponse.success(List.of());
        }
        return ApiResponse.success(permissionRepository.findAllByIdInAndTenantId(permIds, tid));
    }

    @Transactional
    public ApiResponse<Void> setPermissions(Long id, List<Long> permissionIds) {
        String tid = tenant();
        if (tid == null) {
            return ApiResponse.error(400, "缺少租户上下文");
        }
        if (roleRepository.findByIdAndTenantId(id, tid).isEmpty()) {
            return ApiResponse.error(404, "角色不存在或不属于当前租户");
        }
        if (permissionIds == null || permissionIds.isEmpty()) {
            // 整集替换：清空即可
            rolePermissionRepository.deleteByRoleIdAndTenantId(id, tid);
            return ApiResponse.success(null);
        }
        // 防御：过滤掉 null，并校验每个权限 id 属于当前租户，防止脏数据导致非空约束/越权
        List<Long> valid = permissionIds.stream().filter(p -> p != null).toList();
        for (Long pid : valid) {
            if (permissionRepository.findByIdAndTenantId(pid, tid).isEmpty()) {
                return ApiResponse.error(400, "权限 id 不存在或不属于当前租户: " + pid);
            }
        }
        // 整集替换：先清旧关联，再插新关联
        rolePermissionRepository.deleteByRoleIdAndTenantId(id, tid);
        List<RolePermission> toSave = valid.stream().map(pid -> {
            RolePermission rp = new RolePermission();
            rp.setTenantId(tid);
            rp.setRoleId(id);
            rp.setPermissionId(pid);
            return rp;
        }).toList();
        rolePermissionRepository.saveAll(toSave);
        return ApiResponse.success(null);
    }

    private String tenant() {
        return TenantContext.get();
    }
}
