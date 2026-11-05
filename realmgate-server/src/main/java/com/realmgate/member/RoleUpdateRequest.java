package com.realmgate.member;

// 改角色请求体：PATCH /api/projects/{projectId}/members/{id}
// body 例：{ "role": "PM" }
public record RoleUpdateRequest(MemberRole role) {
}
