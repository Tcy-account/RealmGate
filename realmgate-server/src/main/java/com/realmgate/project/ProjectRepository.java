package com.realmgate.project;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProjectRepository extends JpaRepository<Project, Long> {

    // 列出当前租户的所有项目
    List<Project> findAllByTenantId(String tenantId);

    // 按 id 查，但必须限定租户——防止用别人租户的 id 越权读到数据
    Optional<Project> findByIdAndTenantId(Long id, String tenantId);

    // 校验 slug 是否在本租户内重复（用于创建时的唯一性检查）
    Optional<Project> findByTenantIdAndSlug(String tenantId, String slug);
}
