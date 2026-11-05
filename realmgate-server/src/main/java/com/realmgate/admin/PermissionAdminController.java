package com.realmgate.admin;

import com.realmgate.audit.Audit;
import com.realmgate.audit.AuditAction;
import com.realmgate.common.api.ApiResponse;
import com.realmgate.identity.role.Permission;
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
@RequestMapping("/api/admin/permissions")
@RequiredArgsConstructor
public class PermissionAdminController {

    private final PermissionAdminService service;

    @GetMapping
    @PreAuthorize("hasAuthority('permission:read')")
    public ApiResponse<List<Permission>> list() {
        return service.list();
    }

    @PostMapping
    @PreAuthorize("hasAuthority('permission:manage')")
    public ApiResponse<Permission> create(@RequestBody Permission permission) {
        return service.create(permission);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('permission:read')")
    public ApiResponse<Permission> getById(@PathVariable Long id) {
        return service.getById(id);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('permission:manage')")
    @Audit(resourceType = "PERMISSION", action = AuditAction.UPDATE)
    public ApiResponse<Permission> update(@PathVariable Long id, @RequestBody Permission permission) {
        return service.update(id, permission);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('permission:manage')")
    @Audit(resourceType = "PERMISSION", action = AuditAction.DELETE)
    public ApiResponse<Void> delete(@PathVariable Long id) {
        return service.delete(id);
    }
}
