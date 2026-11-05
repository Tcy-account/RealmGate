package com.realmgate.member;

// 成员视图（含用户名）。由 MemberService 从 Member + app_user 联查组装后返回给前端，
// 不直接暴露/不要求前端理解 userId，且不含任何敏感字段。
public record MemberView(
        Long id,
        Long projectId,
        Long userId,
        String role,
        String tenantId,
        String username) {
}
