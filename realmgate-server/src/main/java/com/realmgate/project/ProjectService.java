package com.realmgate.project;

import com.realmgate.common.api.ApiResponse;
import com.realmgate.infrastructure.security.ProjectAuthorization;
import com.realmgate.infrastructure.security.TenantContext;
import com.realmgate.member.MemberRepository;
import com.realmgate.task.TaskRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor           // Lombok 生成构造器，注入 repository
public class ProjectService {

    private final ProjectRepository repository;
    private final ProjectAuthorization projectAuthorization;
    private final MemberRepository memberRepository;
    private final TaskRepository taskRepository;

    /** 创建项目：先把当前租户写进实体，再查重，再保存 */
    public ApiResponse<Project> create(Project project) {
        String tenantId = TenantContext.get();          // 从 ThreadLocal 取租户
        if (tenantId == null) {
            return ApiResponse.error(400, "缺少 X-Tenant-Id 请求头");
        }
        // 同租户内 slug 不能重复
        if (repository.findByTenantIdAndSlug(tenantId, project.getSlug()).isPresent()) {
            return ApiResponse.error(409, "slug 在本租户内已存在");
        }
        project.setTenantId(tenantId);                  // 写入时强制绑定租户
        Project saved = repository.save(project);
        return ApiResponse.success(saved);
    }

    /** 列出当前租户项目 */
    public ApiResponse<List<Project>> list() {
        String tenantId = TenantContext.get();
        if (tenantId == null) {
            return ApiResponse.error(400, "缺少 X-Tenant-Id 请求头");
        }
        return ApiResponse.success(repository.findAllByTenantId(tenantId));
    }

    /** 按 id 查，限定租户 */
    public ApiResponse<Project> getById(Long id) {
        String tenantId = TenantContext.get();
        return repository.findByIdAndTenantId(id, tenantId)
                .map(ApiResponse::success)
                .orElse(ApiResponse.error(404, "项目不存在或不属于当前租户"));
    }

    /** 删除项目：必须拥有 project:delete 权限（tenant 级或 PM 角色），且只能删本租户的项目 */
    @Transactional
    public ApiResponse<Void> delete(Long id) {
        String tenantId = TenantContext.get();
        if (tenantId == null) {
            return ApiResponse.error(400, "缺少 X-Tenant-Id 请求头");
        }
        // 权限校验：经 ProjectAuthorization 统一裁决——token 全局 authorities 含 project:delete（TENANT_ADMIN），
        // 或当前用户在该项目 member.role=PM（PM 映射含 project:delete）均放行；PM 不对其他项目越权。
        if (!projectAuthorization.hasProjectAuthority(id, "project:delete")) {
            return ApiResponse.error(403, "缺少 project:delete 权限");
        }
        // 租户隔离删除：findByIdAndTenantId 保证只能删本租户的项目（越权 id 返 404）。
        return repository.findByIdAndTenantId(id, tenantId)
                .map(p -> {
                    // 先清子表，避免外键冲突：member_project_id_fkey / task_project_id_fkey
                    memberRepository.deleteAllByTenantIdAndProjectId(tenantId, id);
                    taskRepository.deleteAllByTenantIdAndProjectId(tenantId, id);
                    repository.delete(p);
                    return ApiResponse.<Void>success();
                })
                .orElse(ApiResponse.error(404, "项目不存在或不属于当前租户"));
    }
}
