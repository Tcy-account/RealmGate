package com.realmgate.identity.user;

import com.realmgate.infrastructure.persistence.TenantEntity;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "app_user")          // 避开 PostgreSQL 保留字 user
@Getter
@Setter
public class User extends TenantEntity {   // 继承 → 自动拥有 id / created_at / updated_at / tenant_id
    @Column(name = "username", nullable = false, length = 64)
    private String username;

    @Column(name = "email", nullable = false, length = 128)
    private String email;

    @JsonIgnore                      // 密码哈希绝不进响应（第三条铁律：passwordHash 不泄露）
    @Column(name = "password_hash", nullable = false, length = 128)
    private String passwordHash = "";

    @Column(name = "display_name", length = 128)
    private String displayName;

    @Column(name = "status", nullable = false, length = 16)
    private String status = "ACTIVE";   // 默认值，和 SQL DEFAULT 对齐
}
