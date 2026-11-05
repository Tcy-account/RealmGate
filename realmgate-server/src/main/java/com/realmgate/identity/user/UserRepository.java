package com.realmgate.identity.user;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    // 列出当前租户的所有用户
    List<User> findAllByTenantId(String tenantId);

    // 按 id 查，但必须限定租户——防止越权读到别的租户用户
    Optional<User> findByIdAndTenantId(Long id, String tenantId);

    // 校验 username 是否在本租户内重复（创建时唯一性检查）
    Optional<User> findByTenantIdAndUsername(String tenantId, String username);

    // 校验 email 是否在本租户内重复
    Optional<User> findByTenantIdAndEmail(String tenantId, String email);

    // 按 id 集合 + 租户批量查（MemberService 联查 username 用；调用方需保证 ids 非空，避免 IN () 报错）
    List<User> findAllByIdInAndTenantId(List<Long> ids, String tenantId);
}
