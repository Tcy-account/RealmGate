package com.realmgate.member;

import com.realmgate.common.api.ApiResponse;
import com.realmgate.identity.user.User;
import com.realmgate.identity.user.UserRepository;
import com.realmgate.infrastructure.security.ProjectAuthorization;
import com.realmgate.infrastructure.security.ProjectRoleCache;
import com.realmgate.infrastructure.security.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MemberService {

    private final MemberRepository repository;
    private final UserRepository userRepository;
    private final ProjectAuthorization projectAuth;
    private final ProjectRoleCache projectRoleCache;

    public ApiResponse<Member> create(Member member) {
        String tenantId = TenantContext.get();
        if (tenantId == null) {
            return ApiResponse.error(400, "缺少 X-Tenant-Id 请求头");
        }
        // 邀请成员需 member:invite：tenant 级 TENANT_ADMIN 或该项目 PM 均满足（ProjectAuthorization 统一裁决）
        if (!projectAuth.hasProjectAuthority(member.getProjectId(), "member:invite")) {
            return ApiResponse.error(403, "缺少 member:invite 权限");
        }
        if (repository.findByTenantIdAndProjectIdAndUserId(tenantId, member.getProjectId(), member.getUserId()).isPresent()) {
            return ApiResponse.error(409, "该用户已是本项目成员");
        }
        member.setTenantId(tenantId);
        Member saved = repository.save(member);
        // 站3：新增成员 → 失效其项目级角色缓存
        projectRoleCache.evictByUserId(tenantId, member.getProjectId(), member.getUserId());
        return ApiResponse.success(saved);
    }

    // 列出某项目成员，并联查 app_user 的 username 组装成 MemberView 返回。
    public ApiResponse<List<MemberView>> listByProject(Long projectId) {
        String tenantId = TenantContext.get();
        if (tenantId == null) {
            return ApiResponse.error(400, "缺少 X-Tenant-Id 请求头");
        }
        List<Member> members = repository.findAllByTenantIdAndProjectId(tenantId, projectId);
        if (members.isEmpty()) {
            return ApiResponse.success(List.of());
        }
        // IN 空集合会报 "IN ()" 错误，上面已判空；distinct 减小查询量
        List<Long> userIds = members.stream().map(Member::getUserId).distinct().toList();
        Map<Long, String> nameMap = userRepository.findAllByIdInAndTenantId(userIds, tenantId).stream()
                .collect(Collectors.toMap(User::getId, User::getUsername));
        List<MemberView> views = members.stream()
                .map(m -> new MemberView(m.getId(), m.getProjectId(), m.getUserId(), m.getRole().name(), m.getTenantId(),
                        nameMap.getOrDefault(m.getUserId(), "—")))
                .toList();
        return ApiResponse.success(views);
    }

    public ApiResponse<Member> getById(Long projectId, Long id) {
        String tenantId = TenantContext.get();
        return repository.findByTenantIdAndProjectIdAndId(tenantId, projectId, id)
                .map(ApiResponse::success)
                .orElse(ApiResponse.error(404, "成员关系不存在或不属于当前租户/项目"));
    }

    // 改角色：校验归属 + member:manage 权限后更新 role，返回带 username 的 MemberView。
    public ApiResponse<MemberView> updateRole(Long projectId, Long id, MemberRole role) {
        String tenantId = TenantContext.get();
        if (tenantId == null) {
            return ApiResponse.error(400, "缺少 X-Tenant-Id 请求头");
        }
        if (!projectAuth.hasProjectAuthority(projectId, "member:manage")) {
            return ApiResponse.error(403, "缺少 member:manage 权限");
        }
        Optional<Member> found = repository.findByTenantIdAndProjectIdAndId(tenantId, projectId, id);
        if (found.isEmpty()) {
            return ApiResponse.error(404, "成员关系不存在或不属于当前租户/项目");
        }
        Member member = found.get();
        member.setRole(role);
        Member saved = repository.save(member);
        // 站3：改角色 → 失效缓存（下次请求重算新角色）
        projectRoleCache.evictByUserId(tenantId, projectId, member.getUserId());
        return ApiResponse.success(toView(saved, tenantId));
    }

    // 移除成员：先确认归属 + member:manage 权限，再删除（防越权删到别的租户/项目）。
    public ApiResponse<Void> remove(Long projectId, Long id) {
        String tenantId = TenantContext.get();
        if (tenantId == null) {
            return ApiResponse.error(400, "缺少 X-Tenant-Id 请求头");
        }
        if (!projectAuth.hasProjectAuthority(projectId, "member:manage")) {
            return ApiResponse.error(403, "缺少 member:manage 权限");
        }
        return repository.findByTenantIdAndProjectIdAndId(tenantId, projectId, id)
                .map(m -> {
                    repository.delete(m);
                    // 站3：移除成员 → 失效缓存
                    projectRoleCache.evictByUserId(tenantId, projectId, m.getUserId());
                    return ApiResponse.<Void>success(null);
                })
                .orElse(ApiResponse.error(404, "成员关系不存在或不属于当前租户/项目"));
    }

    // 单个 Member 组装成 MemberView（查一次 username）
    private MemberView toView(Member m, String tenantId) {
        String username = userRepository.findByIdAndTenantId(m.getUserId(), tenantId)
                .map(User::getUsername)
                .orElse("—");
        return new MemberView(m.getId(), m.getProjectId(), m.getUserId(), m.getRole().name(), m.getTenantId(), username);
    }
}
