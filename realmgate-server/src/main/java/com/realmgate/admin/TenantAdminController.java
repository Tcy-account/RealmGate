package com.realmgate.admin;

import com.realmgate.audit.Audit;
import com.realmgate.audit.AuditAction;
import com.realmgate.common.api.ApiResponse;
import com.realmgate.platform.tenant.Tenant;
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
@RequestMapping("/api/admin/tenants")
@RequiredArgsConstructor
public class TenantAdminController {

    private final TenantAdminService service;

    @GetMapping
    @PreAuthorize("hasAuthority('tenant:read')")
    public ApiResponse<List<Tenant>> list() {
        return service.list();
    }

    @PostMapping
    @PreAuthorize("hasAuthority('tenant:manage')")
    @Audit(resourceType = "TENANT", action = AuditAction.CREATE)
    public ApiResponse<Tenant> create(@RequestBody Tenant tenant) {
        return service.create(tenant);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('tenant:read')")
    public ApiResponse<Tenant> getById(@PathVariable Long id) {
        return service.getById(id);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('tenant:manage')")
    @Audit(resourceType = "TENANT", action = AuditAction.UPDATE)
    public ApiResponse<Tenant> update(@PathVariable Long id, @RequestBody Tenant tenant) {
        return service.update(id, tenant);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('tenant:manage')")
    @Audit(resourceType = "TENANT", action = AuditAction.DELETE)
    public ApiResponse<Void> delete(@PathVariable Long id) {
        return service.delete(id);
    }
}
