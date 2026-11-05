package com.realmgate.identity.user;

import com.realmgate.identity.role.Permission;
import com.realmgate.identity.role.PermissionRepository;
import com.realmgate.identity.role.Role;
import com.realmgate.identity.role.RolePermission;
import com.realmgate.identity.role.RolePermissionRepository;
import com.realmgate.identity.role.RoleRepository;
import com.realmgate.identity.role.UserRole;
import com.realmgate.identity.role.UserRoleRepository;
import com.realmgate.infrastructure.security.LoginTenantHolder;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserDetailsServiceImpl implements UserDetailsService {

  private final UserRepository userRepository;
  // ① 注入 RBAC 四仓库（@RequiredArgsConstructor 自动生成构造器，Spring 提供 bean）
  private final UserRoleRepository userRoleRepository;
  private final RolePermissionRepository rolePermissionRepository;
  private final PermissionRepository permissionRepository;
  private final RoleRepository roleRepository;

  @Override
  public UserDetails loadUserByUsername(String username) {
    String tenantId = LoginTenantHolder.get();
    if (tenantId == null || tenantId.isBlank()) {
      throw new UsernameNotFoundException("缺少租户标识");
    }

    User user = userRepository.findByTenantIdAndUsername(tenantId, username)
        .orElseThrow(() -> new UsernameNotFoundException("用户不存在或不在该租户"));

    // ② 从 RBAC 引擎取出该用户在本租户的全部权限 key
    List<String> authorities = resolveAuthorities(user.getId(), tenantId);

    // 坑①：全限定名 User 不能省
    return org.springframework.security.core.userdetails.User.withUsername(user.getUsername())
        .password(user.getPasswordHash())
        .authorities(authorities.toArray(new String[0])) // ← 替换原来的 NO_AUTHORITIES
        .accountExpired(false)
        .accountLocked(false)
        .credentialsExpired(false)
        .disabled(!"ACTIVE".equals(user.getStatus()))
        .build();
  }

  // 图里的三层链：user → role → permission → permKey
  private List<String> resolveAuthorities(Long userId, String tenantId) {
    List<UserRole> userRoles = userRoleRepository.findByUserIdAndTenantId(userId, tenantId);
    List<Long> roleIds = userRoles.stream().map(UserRole::getRoleId).toList();
    if (roleIds.isEmpty())
      return List.of(); // 没角色 → 空权限（受保护接口全拦截）

    List<RolePermission> rolePerms = rolePermissionRepository.findByRoleIdInAndTenantId(roleIds, tenantId);
    List<Long> permIds = rolePerms.stream().map(RolePermission::getPermissionId).toList();
    if (permIds.isEmpty())
      return List.of(); // 挡 In 空集合坑

    List<String> perms = permissionRepository.findAllByIdInAndTenantId(permIds, tenantId)
        .stream()
        .map(Permission::getPermKey) // ③ 权限名 = permKey 原值，不加 ROLE_ 前缀
        .toList();

    // Phase 7：TENANT_ADMIN 角色追加基础设施运维权限 jwk:rotate（用于 /api/admin/jwk/rotate）。
    // 该权限随 token 的 permissions claim 下发，前端/资源服务器据此授权，避免引入额外 RBAC 表变更。
    if (isTenantAdmin(userRoles)) {
      List<String> withAdmin = new ArrayList<>(perms);
      withAdmin.add("jwk:rotate");
      return withAdmin;
    }
    return perms;
  }

  // 判断用户是否持有 TENANT_ADMIN 角色（按角色名，不依赖特定 permKey，语义清晰）
  private boolean isTenantAdmin(List<UserRole> userRoles) {
    return userRoles.stream().anyMatch(ur -> {
      Role role = roleRepository.findById(ur.getRoleId()).orElse(null);
      return role != null && "TENANT_ADMIN".equals(role.getRoleName());
    });
  }
}
