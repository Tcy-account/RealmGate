package com.realmgate.task;

import com.realmgate.common.api.ApiResponse;
import com.realmgate.infrastructure.security.ProjectAuthorization;
import com.realmgate.infrastructure.security.TenantContext;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TaskService {

    private final TaskRepository repository;
    private final ProjectAuthorization projectAuthorization;

    /** 创建任务：校验 task:create 权限，再取租户、校验 slug 唯一、强制绑定租户 */
    public ApiResponse<Task> create(Task task) {
        String tenantId = TenantContext.get();
        if (tenantId == null) {
            return ApiResponse.error(400, "缺少 X-Tenant-Id 请求头");
        }
        // 项目级权限裁决：TENANT_ADMIN（token 含 task:create）或该项目 PM（member.role 映射含 task:create）可创建任务
        if (!projectAuthorization.hasProjectAuthority(task.getProjectId(), "task:create")) {
            return ApiResponse.error(403, "缺少 task:create 权限");
        }
        if (repository.findByTenantIdAndProjectIdAndSlug(tenantId, task.getProjectId(), task.getSlug()).isPresent()) {
            return ApiResponse.error(409, "slug 在本租户+项目内已存在");
        }
        task.setTenantId(tenantId);
        Task saved = repository.save(task);
        return ApiResponse.success(saved);
    }

    /** 列出某项目的任务，限定租户 */
    public ApiResponse<List<Task>> listByProject(Long projectId) {
        String tenantId = TenantContext.get();
        if (tenantId == null) {
            return ApiResponse.error(400, "缺少 X-Tenant-Id 请求头");
        }
        return ApiResponse.success(repository.findAllByTenantIdAndProjectId(tenantId, projectId));
    }

    /** 按 id 查，限定租户 + 项目 */
    public ApiResponse<Task> getById(Long projectId, Long id) {
        String tenantId = TenantContext.get();
        return repository.findByTenantIdAndProjectIdAndId(tenantId, projectId, id)
                .map(ApiResponse::success)
                .orElse(ApiResponse.error(404, "任务不存在或不属于当前租户/项目"));
    }

    /** 更新任务：校验 task:edit 权限，仅覆盖 body 中非 null 字段（避免部分更新被 null 整体覆盖） */
    public ApiResponse<Task> update(Long projectId, Long id, Task body) {
        String tenantId = TenantContext.get();
        if (tenantId == null) {
            return ApiResponse.error(400, "缺少 X-Tenant-Id 请求头");
        }
        if (!projectAuthorization.hasProjectAuthority(projectId, "task:edit")) {
            return ApiResponse.error(403, "缺少 task:edit 权限");
        }
        Task existing = repository.findByTenantIdAndProjectIdAndId(tenantId, projectId, id)
                .orElse(null);
        if (existing == null) {
            return ApiResponse.error(404, "任务不存在或不属于当前租户/项目");
        }
        // slug 改名时校验唯一（排除自身）
        if (body.getSlug() != null && !body.getSlug().equals(existing.getSlug())) {
            if (repository.findByTenantIdAndProjectIdAndSlug(tenantId, projectId, body.getSlug()).isPresent()) {
                return ApiResponse.error(409, "slug 在本租户+项目内已存在");
            }
            existing.setSlug(body.getSlug());
        }
        if (body.getTitle() != null) existing.setTitle(body.getTitle());
        if (body.getDescription() != null) existing.setDescription(body.getDescription());
        if (body.getStatus() != null) existing.setStatus(body.getStatus());
        // projectId 不可变：任务始终属于创建时的项目
        Task saved = repository.save(existing);
        return ApiResponse.success(saved);
    }

    /** 删除任务：校验 task:delete 权限，且只能删本租户+项目的任务 */
    public ApiResponse<Void> remove(Long projectId, Long id) {
        String tenantId = TenantContext.get();
        if (tenantId == null) {
            return ApiResponse.error(400, "缺少 X-Tenant-Id 请求头");
        }
        if (!projectAuthorization.hasProjectAuthority(projectId, "task:delete")) {
            return ApiResponse.error(403, "缺少 task:delete 权限");
        }
        return repository.findByTenantIdAndProjectIdAndId(tenantId, projectId, id)
                .map(t -> {
                    repository.delete(t);
                    return ApiResponse.<Void>success();
                })
                .orElse(ApiResponse.error(404, "任务不存在或不属于当前租户/项目"));
    }
}
