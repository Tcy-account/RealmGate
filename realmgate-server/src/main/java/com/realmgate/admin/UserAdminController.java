package com.realmgate.admin;

import com.realmgate.audit.Audit;
import com.realmgate.audit.AuditAction;
import com.realmgate.common.api.ApiResponse;
import com.realmgate.identity.role.Role;
import com.realmgate.identity.user.User;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class UserAdminController {

    private final UserAdminService service;

    @GetMapping
    @PreAuthorize("hasAuthority('user:read')")
    public ApiResponse<List<User>> list() {
        return service.list();
    }

    @PostMapping
    @PreAuthorize("hasAuthority('user:manage')")
    @Audit(resourceType = "USER", action = AuditAction.CREATE)
    public ApiResponse<User> create(@RequestBody UserUpsertRequest req) {
        return service.create(req);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('user:read')")
    public ApiResponse<User> getById(@PathVariable Long id) {
        return service.getById(id);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('user:manage')")
    @Audit(resourceType = "USER", action = AuditAction.UPDATE)
    public ApiResponse<User> update(@PathVariable Long id, @RequestBody UserUpsertRequest req) {
        return service.update(id, req);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('user:manage')")
    @Audit(resourceType = "USER", action = AuditAction.DELETE)
    public ApiResponse<Void> delete(@PathVariable Long id) {
        return service.delete(id);
    }

    @GetMapping("/{id}/roles")
    @PreAuthorize("hasAuthority('user:read')")
    public ApiResponse<List<Role>> roles(@PathVariable Long id) {
        return service.roles(id);
    }

    @PostMapping("/{id}/roles/{roleId}")
    @PreAuthorize("hasAuthority('user:manage')")
    @Audit(resourceType = "USER", action = AuditAction.UPDATE)
    public ApiResponse<Void> assignRole(@PathVariable Long id, @PathVariable Long roleId) {
        return service.assignRole(id, roleId);
    }

    @DeleteMapping("/{id}/roles/{roleId}")
    @PreAuthorize("hasAuthority('user:manage')")
    @Audit(resourceType = "USER", action = AuditAction.UPDATE)
    public ApiResponse<Void> unassignRole(@PathVariable Long id, @PathVariable Long roleId) {
        return service.unassignRole(id, roleId);
    }
}
