package com.realmgate.member;

import com.realmgate.common.api.ApiResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{projectId}/members")
@RequiredArgsConstructor
public class MemberController {

    private final MemberService service;

    @PostMapping
    public ApiResponse<Member> create(@PathVariable Long projectId, @RequestBody Member member) {
        member.setProjectId(projectId);   // 从路径取 projectId 覆盖 body，防止越权指定别的 projectId
        return service.create(member);
    }

    @GetMapping
    public ApiResponse<List<MemberView>> listByProject(@PathVariable Long projectId) {
        return service.listByProject(projectId);
    }

    @GetMapping("/{id}")
    public ApiResponse<Member> getById(@PathVariable Long projectId, @PathVariable Long id) {
        return service.getById(projectId, id);
    }

    // 改角色：PATCH /api/projects/{projectId}/members/{id}  body { "role": "PM" }
    @PatchMapping("/{id}")
    public ApiResponse<MemberView> updateRole(@PathVariable Long projectId, @PathVariable Long id,
                                              @RequestBody RoleUpdateRequest req) {
        return service.updateRole(projectId, id, req.role());
    }

    // 移除成员：DELETE /api/projects/{projectId}/members/{id}
    @DeleteMapping("/{id}")
    public ApiResponse<Void> remove(@PathVariable Long projectId, @PathVariable Long id) {
        return service.remove(projectId, id);
    }
}
