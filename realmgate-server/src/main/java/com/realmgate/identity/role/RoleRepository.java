package com.realmgate.identity.role;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RoleRepository extends JpaRepository<Role, Long> {

  // 按 租户+角色名 查（V8 种子给种子用户挂 TENANT_ADMIN、或运行期按名字取角色时用）
  Optional<Role> findByTenantIdAndRoleName(String tenantId, String roleName);

  // 按 租户+id 查（站8 管理端点用，强制限定租户防越权）
  Optional<Role> findByIdAndTenantId(Long id, String tenantId);

  // 批量按 id + 租户查（站8：根据用户角色 id 列表反查角色实体）
  List<Role> findAllByIdInAndTenantId(List<Long> ids, String tenantId);

  // 列出当前租户所有角色（以后做角色管理页用）
  List<Role> findAllByTenantId(String tenantId);
}
