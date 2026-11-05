package com.realmgate.member;

import com.realmgate.infrastructure.persistence.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "member")           // 表名，和 V5 SQL 一致
@Getter
@Setter
public class Member extends TenantEntity {   // 继承 → 自动拥有 id / created_at / updated_at / tenant_id
    @Column(name = "project_id", nullable = false)
    private Long projectId;        // 外键用 Long 列，不建 JPA @ManyToOne

    @Column(name = "user_id", nullable = false)
    private Long userId;           // 外键用 Long 列，指向 app_user

    // 项目级角色（PM/MEMBER/VIEWER），枚举持久化为字符串，与前端下拉值一致
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 32)
    private MemberRole role = MemberRole.MEMBER;   // 默认值，和 SQL DEFAULT 对齐
}
