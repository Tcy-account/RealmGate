package com.realmgate.identity.role;

import com.realmgate.infrastructure.persistence.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "rbac_permission")
@Getter
@Setter
public class Permission extends TenantEntity {
  @Column(name = "perm_key", nullable = false, length = 128) // 权限键，如 project:delete
  private String permKey;

  @Column(name = "description", length = 256)
  private String description;
}
