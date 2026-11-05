package com.realmgate.identity.role;

import com.realmgate.infrastructure.persistence.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "rbac_role_permission")
@Getter
@Setter
public class RolePermission extends TenantEntity {
  @Column(name = "role_id", nullable = false)
  private Long roleId; // 指向 rbac_role.id

  @Column(name = "permission_id", nullable = false)
  private Long permissionId; // 指向 rbac_permission.id
}
