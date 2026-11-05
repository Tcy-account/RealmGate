package com.realmgate.infrastructure.web;

import com.realmgate.common.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 0 健康检查 / 连通性验证用。
 * 启动后端、PostgreSQL、Redis 后访问 GET http://localhost:9000/api/ping
 * 期望返回 {"code":0,"message":"success","data":"pong"}
 */
@RestController
public class WelcomeController {

    @GetMapping("/api/ping")
    public ApiResponse<String> ping() {
        return ApiResponse.success("pong");
    }
}
