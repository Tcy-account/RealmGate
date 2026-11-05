package com.realmgate.infrastructure.security;

import com.realmgate.member.MemberRole;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * 项目级权限裁决器。
 *
 * 判断"当前登录用户"对某个 projectId 是否拥有某 permission，权限来源有两条：
 *   ① tenant 级 RBAC：token 里已解出的 authorities（如 TENANT_ADMIN 持有的 member:manage）→ 全局生效；
 *   ② 项目级角色：当前用户在该 projectId 的 member.role（PM/MEMBER/VIEWER）经 ProjectRolePermissions 映射的权限 → 仅对该项目生效。
 *
 * 站3：② 路径走 ProjectRoleCache（Redis 缓存），命中免查库；角色变更由 MemberService 精确 evict。
 * 这样 member.role = PM 自动获得 member:manage，且只对"他作为 PM 的那个项目"生效，
 * 不会变成全局越权；同时和 tenant 级权限走同一套 RBAC 语义，Service 只需声明所需 permission 名。
 */
@Component
@RequiredArgsConstructor
public class ProjectAuthorization {

    private final ProjectRoleCache projectRoleCache;

    public boolean hasProjectAuthority(Long projectId, String permission) {
        // ① 全局（tenant 级）权限：token 已携带，优先放行
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> permission.equals(a.getAuthority()))) {
            return true;
        }
        // ② 项目级角色映射：走缓存（命中 Redis 免查库）
        String tenantId = TenantContext.get();
        if (tenantId == null || auth == null || auth.getName() == null || auth.getName().isBlank()) {
            return false;
        }
        String roleName = projectRoleCache.resolveRole(tenantId, projectId, auth.getName());
        if (roleName == null) return false; // 非成员 / 用户不存在 → 无项目级权限
        try {
            return ProjectRolePermissions.forRole(MemberRole.valueOf(roleName)).contains(permission);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
