package com.realmgate.identity.user;

import com.realmgate.common.api.ApiResponse;
import com.realmgate.infrastructure.security.LoginTenantHolder;
import com.realmgate.infrastructure.security.TenantContext;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor // Lombok 生成构造器，注入 repository
public class UserService {

    private final UserRepository repository;

    /** 创建用户：先取租户，校验 username/email 唯一，再强制绑定租户 */
    public ApiResponse<User> create(User user) {
        String tenantId = TenantContext.get(); // 从 ThreadLocal 取租户
        if (tenantId == null) {
            return ApiResponse.error(400, "缺少 X-Tenant-Id 请求头");
        }
        if (repository.findByTenantIdAndUsername(tenantId, user.getUsername()).isPresent()) {
            return ApiResponse.error(409, "username 在本租户内已存在");
        }
        if (repository.findByTenantIdAndEmail(tenantId, user.getEmail()).isPresent()) {
            return ApiResponse.error(409, "email 在本租户内已存在");
        }
        user.setTenantId(tenantId); // 写入时强制绑定租户
        User saved = repository.save(user);
        return ApiResponse.success(saved);
    }

    /** 列出当前租户用户 */
    public ApiResponse<List<User>> list() {
        String tenantId = TenantContext.get();
        if (tenantId == null) {
            return ApiResponse.error(400, "缺少 X-Tenant-Id 请求头");
        }
        return ApiResponse.success(repository.findAllByTenantId(tenantId));
    }

    /** 按 id 查，限定租户 */
    public ApiResponse<User> getById(Long id) {
        String tenantId = LoginTenantHolder.get();
        return repository.findByIdAndTenantId(id, tenantId)
                .map(ApiResponse::success)
                .orElse(ApiResponse.error(404, "用户不存在或不属于当前租户"));
    }
}
