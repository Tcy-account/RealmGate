package com.realmgate.infrastructure.security;

import com.realmgate.member.MemberRole;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * 项目级角色 → 权限集合映射。
 *
 * 与 tenant 级 rbac_permission 共用同一套 key 命名（如 member:manage），
 * 让"项目级角色"也以 RBAC permission 语义表达，而不是在 Service 里散落判断角色枚举。
 * 注意：JWT 是登录时签发、全局有效、不带 project 维度，所以项目级权限不能塞进 token，
 * 只能在带 projectId 的请求里按"当前用户在该项目的 member.role"解析（见 ProjectAuthorization）。
 */
public final class ProjectRolePermissions {

    private ProjectRolePermissions() {}

    // 对齐计划 §5.3 项目管理员 / §5.4 普通成员 / §5.5 Viewer
    private static final Map<MemberRole, Set<String>> MAPPING = new EnumMap<>(MemberRole.class) {{
        put(MemberRole.PM, Set.of(
                "member:manage", "member:invite", "member:read",
                "project:read", "project:edit", "project:delete",
                "task:read", "task:create", "task:edit", "task:delete"));
        put(MemberRole.MEMBER, Set.of(
                "member:read", "project:read",
                "task:read", "task:create", "task:edit"));
        put(MemberRole.VIEWER, Set.of(
                "member:read", "project:read", "task:read"));
    }};

    public static Set<String> forRole(MemberRole role) {
        return MAPPING.getOrDefault(role, Set.of());
    }
}
