package com.realmgate.admin;

import com.realmgate.audit.Audit;
import com.realmgate.audit.AuditAction;
import com.realmgate.common.api.ApiResponse;
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

/**
 * OAuth 客户端管理（平台级，跨租户，全局唯一）。与 Tenant 管理同属跨租户端点，
 * 不经 TenantContext 过滤。方法安全：client:read 看，client:manage 改/删。
 */
@RestController
@RequestMapping("/api/admin/clients")
@RequiredArgsConstructor
public class ClientAdminController {

    private final ClientAdminService service;

    @GetMapping
    @PreAuthorize("hasAuthority('client:read')")
    public ApiResponse<List<ClientView>> list() {
        return service.list();
    }

    @PostMapping
    @PreAuthorize("hasAuthority('client:manage')")
    @Audit(resourceType = "CLIENT", action = AuditAction.CREATE)
    public ApiResponse<ClientView> create(@RequestBody ClientUpsertRequest req) {
        return service.create(req);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('client:read')")
    public ApiResponse<ClientView> getById(@PathVariable String id) {
        return service.getById(id);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('client:manage')")
    public ApiResponse<ClientView> update(@PathVariable String id, @RequestBody ClientUpsertRequest req) {
        return service.update(id, req);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('client:manage')")
    @Audit(resourceType = "CLIENT", action = AuditAction.DELETE)
    public ApiResponse<Void> delete(@PathVariable String id) {
        return service.delete(id);
    }
}
