package com.realmgate.identity.role;

import com.realmgate.infrastructure.persistence.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "rbac_role") // 表名必须带 rbac_ 前缀：role 是 PG 保留字
@Getter
@Setter
public class Role extends TenantEntity { // extends → 自动拥有 id / tenant_id / created_at / updated_at
  @Column(name = "role_name", nullable = false, length = 64) // name 必须和 V7 SQL 列名逐字一致
  private String roleName;

  @Column(name = "description", length = 256)
  private String description;
}
