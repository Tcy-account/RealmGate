package com.realmgate.identity.role;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserRoleRepository extends JpaRepository<UserRole, Long> {

  // 查某用户在当前租户绑定的所有角色关联
  List<UserRole> findByUserIdAndTenantId(Long userId, String tenantId);

  // 站8：解绑某用户在指定租户的全部角色（删除用户时级联清理）
  void deleteByUserIdAndTenantId(Long userId, String tenantId);

  // 站8：解绑某用户的单个角色
  void deleteByUserIdAndRoleIdAndTenantId(Long userId, Long roleId, String tenantId);
}
