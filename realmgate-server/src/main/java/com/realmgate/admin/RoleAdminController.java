package com.realmgate.admin;

import com.realmgate.audit.Audit;
import com.realmgate.audit.AuditAction;
import com.realmgate.common.api.ApiResponse;
import com.realmgate.identity.role.Permission;
import com.realmgate.identity.role.Role;
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
@RequestMapping("/api/admin/roles")
@RequiredArgsConstructor
public class RoleAdminController {

    private final RoleAdminService service;

    @GetMapping
    @PreAuthorize("hasAuthority('role:read')")
    public ApiResponse<List<Role>> list() {
        return service.list();
    }

    @PostMapping
    @PreAuthorize("hasAuthority('role:manage')")
    @Audit(resourceType = "ROLE", action = AuditAction.CREATE)
    public ApiResponse<Role> create(@RequestBody Role role) {
        return service.create(role);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('role:read')")
    public ApiResponse<Role> getById(@PathVariable Long id) {
        return service.getById(id);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('role:manage')")
    @Audit(resourceType = "ROLE", action = AuditAction.UPDATE)
    public ApiResponse<Role> update(@PathVariable Long id, @RequestBody Role role) {
        return service.update(id, role);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('role:manage')")
    @Audit(resourceType = "ROLE", action = AuditAction.DELETE)
    public ApiResponse<Void> delete(@PathVariable Long id) {
        return service.delete(id);
    }

    @GetMapping("/{id}/permissions")
    @PreAuthorize("hasAuthority('role:read')")
    public ApiResponse<List<Permission>> permissions(@PathVariable Long id) {
        return service.permissions(id);
    }

    @PutMapping("/{id}/permissions")
    @PreAuthorize("hasAuthority('role:manage')")
    @Audit(resourceType = "ROLE", action = AuditAction.UPDATE)
    public ApiResponse<Void> setPermissions(@PathVariable Long id, @RequestBody List<Long> permissionIds) {
        return service.setPermissions(id, permissionIds);
    }
}
