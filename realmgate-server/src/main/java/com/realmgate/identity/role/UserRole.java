package com.realmgate.identity.role;

import com.realmgate.infrastructure.persistence.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "rbac_user_role")
@Getter
@Setter
public class UserRole extends TenantEntity {
  @Column(name = "user_id", nullable = false) // BIGINT → Java Long
  private Long userId; // 指向 app_user.id

  @Column(name = "role_id", nullable = false)
  private Long roleId; // 指向 rbac_role.id
}
