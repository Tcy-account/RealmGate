package com.realmgate.member;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MemberRepository extends JpaRepository<Member, Long> {

    // 列出当前租户的所有成员关系
    List<Member> findAllByTenantId(String tenantId);

    // 列出某项目的成员，必须同时带 tenantId + projectId
    List<Member> findAllByTenantIdAndProjectId(String tenantId, Long projectId);

    // 删除某项目的全部成员关系（删项目前先清，避免 member_project_id_fkey 外键冲突）
    void deleteAllByTenantIdAndProjectId(String tenantId, Long projectId);

    // 按 id 查，限定租户
    Optional<Member> findByIdAndTenantId(Long id, String tenantId);

    // 按 租户+项目+id 查
    Optional<Member> findByTenantIdAndProjectIdAndId(String tenantId, Long projectId, Long id);

    // 按 租户+项目+user_id 查（创建时防重复）
    Optional<Member> findByTenantIdAndProjectIdAndUserId(String tenantId, Long projectId, Long userId);
}
