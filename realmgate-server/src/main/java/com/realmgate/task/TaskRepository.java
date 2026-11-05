package com.realmgate.task;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TaskRepository extends JpaRepository<Task, Long> {

    // 列出当前租户的所有任务
    List<Task> findAllByTenantId(String tenantId);

    // 列出某项目下的任务——必须同时带 tenantId + projectId（双约束才是正确隔离）
    List<Task> findAllByTenantIdAndProjectId(String tenantId, Long projectId);

    // 删除某项目的全部任务（删项目前先清，避免 task_project_id_fkey 外键冲突）
    void deleteAllByTenantIdAndProjectId(String tenantId, Long projectId);

    // 按 id 查，限定租户
    Optional<Task> findByIdAndTenantId(Long id, String tenantId);

    // 按 租户+项目+id 查，杜绝跨项目越权
    Optional<Task> findByTenantIdAndProjectIdAndId(String tenantId, Long projectId, Long id);

    // 校验 slug 是否在本租户+项目内重复
    Optional<Task> findByTenantIdAndProjectIdAndSlug(String tenantId, Long projectId, String slug);
}
