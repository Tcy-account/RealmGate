package com.realmgate.admin;

import com.realmgate.common.api.ApiResponse;
import com.realmgate.identity.role.Role;
import com.realmgate.identity.role.RoleRepository;
import com.realmgate.identity.role.UserRole;
import com.realmgate.identity.role.UserRoleRepository;
import com.realmgate.identity.user.User;
import com.realmgate.identity.user.UserRepository;
import com.realmgate.infrastructure.security.TenantContext;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用户管理（租户级，强制 tenant 隔离）。
 * 复用既有 User / UserRole 仓库；密码经 PasswordEncoder 哈希，绝不明文落库。
 */
@Service
@RequiredArgsConstructor
public class UserAdminService {

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;

    public ApiResponse<List<User>> list() {
        String tid = tenant();
        if (tid == null) {
            return ApiResponse.error(400, "缺少租户上下文");
        }
        return ApiResponse.success(userRepository.findAllByTenantId(tid));
    }

    @Transactional
    public ApiResponse<User> create(UserUpsertRequest req) {
        String tid = tenant();
        if (tid == null) {
            return ApiResponse.error(400, "缺少租户上下文");
        }
        if (req.username() == null || req.username().isBlank()) {
            return ApiResponse.error(400, "username 必填");
        }
        if (req.email() == null || req.email().isBlank()) {
            return ApiResponse.error(400, "email 必填");
        }
        if (req.password() == null || req.password().isBlank()) {
            return ApiResponse.error(400, "初始密码必填");
        }
        if (userRepository.findByTenantIdAndUsername(tid, req.username()).isPresent()) {
            return ApiResponse.error(409, "username 在本租户内已存在");
        }
        if (userRepository.findByTenantIdAndEmail(tid, req.email()).isPresent()) {
            return ApiResponse.error(409, "email 在本租户内已存在");
        }
        User user = new User();
        user.setTenantId(tid);
        user.setUsername(req.username());
        user.setEmail(req.email());
        user.setDisplayName(req.displayName());
        user.setStatus(req.status() == null || req.status().isBlank() ? "ACTIVE" : req.status());
        user.setPasswordHash(passwordEncoder.encode(req.password()));
        return ApiResponse.success(userRepository.save(user));
    }

    public ApiResponse<User> getById(Long id) {
        String tid = tenant();
        if (tid == null) {
            return ApiResponse.error(400, "缺少租户上下文");
        }
        return userRepository.findByIdAndTenantId(id, tid)
                .map(ApiResponse::success)
                .orElse(ApiResponse.error(404, "用户不存在或不属于当前租户"));
    }

    @Transactional
    public ApiResponse<User> update(Long id, UserUpsertRequest req) {
        String tid = tenant();
        if (tid == null) {
            return ApiResponse.error(400, "缺少租户上下文");
        }
        User user = userRepository.findByIdAndTenantId(id, tid).orElse(null);
        if (user == null) {
            return ApiResponse.error(404, "用户不存在或不属于当前租户");
        }
        if (req.displayName() != null) {
            user.setDisplayName(req.displayName());
        }
        if (req.email() != null) {
            user.setEmail(req.email());
        }
        if (req.status() != null) {
            user.setStatus(req.status());
        }
        if (req.password() != null && !req.password().isBlank()) {
            user.setPasswordHash(passwordEncoder.encode(req.password()));
        }
        user.setUpdatedAt(Instant.now());
        return ApiResponse.success(userRepository.save(user));
    }

    @Transactional
    public ApiResponse<Void> delete(Long id) {
        String tid = tenant();
        if (tid == null) {
            return ApiResponse.error(400, "缺少租户上下文");
        }
        User user = userRepository.findByIdAndTenantId(id, tid).orElse(null);
        if (user == null) {
            return ApiResponse.error(404, "用户不存在或不属于当前租户");
        }
        userRoleRepository.deleteByUserIdAndTenantId(id, tid); // 级联清角色绑定，避免孤儿记录
        userRepository.delete(user);
        return ApiResponse.success(null);
    }

    public ApiResponse<List<Role>> roles(Long id) {
        String tid = tenant();
        if (tid == null) {
            return ApiResponse.error(400, "缺少租户上下文");
        }
        if (userRepository.findByIdAndTenantId(id, tid).isEmpty()) {
            return ApiResponse.error(404, "用户不存在或不属于当前租户");
        }
        List<UserRole> userRoles = userRoleRepository.findByUserIdAndTenantId(id, tid);
        List<Long> roleIds = userRoles.stream().map(UserRole::getRoleId).toList();
        if (roleIds.isEmpty()) {
            return ApiResponse.success(List.of());
        }
        return ApiResponse.success(roleRepository.findAllByIdInAndTenantId(roleIds, tid));
    }

    @Transactional
    public ApiResponse<Void> assignRole(Long userId, Long roleId) {
        String tid = tenant();
        if (tid == null) {
            return ApiResponse.error(400, "缺少租户上下文");
        }
        if (userRepository.findByIdAndTenantId(userId, tid).isEmpty()) {
            return ApiResponse.error(404, "用户不存在或不属于当前租户");
        }
        if (roleRepository.findByIdAndTenantId(roleId, tid).isEmpty()) {
            return ApiResponse.error(404, "角色不存在或不属于当前租户");
        }
        boolean already = userRoleRepository.findByUserIdAndTenantId(userId, tid).stream()
                .anyMatch(ur -> ur.getRoleId().equals(roleId));
        if (already) {
            return ApiResponse.success(null);
        }
        UserRole ur = new UserRole();
        ur.setTenantId(tid);
        ur.setUserId(userId);
        ur.setRoleId(roleId);
        userRoleRepository.save(ur);
        return ApiResponse.success(null);
    }

    @Transactional
    public ApiResponse<Void> unassignRole(Long userId, Long roleId) {
        String tid = tenant();
        if (tid == null) {
            return ApiResponse.error(400, "缺少租户上下文");
        }
        userRoleRepository.deleteByUserIdAndRoleIdAndTenantId(userId, roleId, tid);
        return ApiResponse.success(null);
    }

    private String tenant() {
        return TenantContext.get();
    }
}
