package com.realmgate.admin;

/**
 * 管理后台创建/更新用户的请求体。密码单独成字段，避免复用 User 实体时
 * passwordHash(@JsonIgnore) 无法反序列化、且明文落库的风险。
 */
public record UserUpsertRequest(
        String username,
        String email,
        String displayName,
        String password,
        String status) {
}
