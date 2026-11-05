package com.realmgate.identity.role;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PermissionRepository extends JpaRepository<Permission, Long> {

  // 按 租户+权限key 查（运行期按 key 取权限实体）
  Optional<Permission> findByTenantIdAndPermKey(String tenantId, String permKey);

  // 按 租户+id 查（站8 管理端点用，强制限定租户防越权）
  Optional<Permission> findByIdAndTenantId(Long id, String tenantId);

  // 列出当前租户全部权限（站8 权限目录管理页）
  List<Permission> findAllByTenantId(String tenantId);

  // 批量按 id 查（配合 RolePermissionRepository 给出的 permissionId 列表）
  // 坑：ids 为空时 SQL 变成 IN () 在 PG 非法 → 调用前必须在 Service 层判空
  List<Permission> findAllByIdInAndTenantId(List<Long> ids, String tenantId);
}
