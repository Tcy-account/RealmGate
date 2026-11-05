package com.realmgate.identity.role;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RolePermissionRepository extends JpaRepository<RolePermission, Long> {

  // 批量按 roleId 查（一个用户可能绑多个角色 → roleIds 是集合，用 In 关键字）
  // 坑：roleIds 为空时 IN () 非法 → Service 层先判空
  List<RolePermission> findByRoleIdInAndTenantId(List<Long> roleIds, String tenantId);

  // 站8：替换某角色权限集前，先清掉旧关联
  void deleteByRoleIdAndTenantId(Long roleId, String tenantId);

  // 站8：删除权限目录项时，清理孤儿角色-权限关联
  void deleteByPermissionIdAndTenantId(Long permissionId, String tenantId);
}
