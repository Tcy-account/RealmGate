package com.realmgate.common.api;

/**
 * 业务错误码。与 HTTP 状态码解耦：HTTP 表达「传输层语义」（401 未认证 / 403 无权限），
 * code 表达「业务语义」。前端优先看 HTTP 状态码，code 仅用于细分提示。
 *
 * <p>约定：0 = 成功；1xxx = 通用；4xxx 对应客户端错误；5xxx 对应服务端错误。</p>
 */
public enum ResultCode {

    SUCCESS(0, "success"),

    // 参数 / 业务冲突
    VALIDATION_FAILED(4000, "参数校验失败"),
    RESOURCE_CONFLICT(4090, "资源冲突（如用户名已存在）"),
    RESOURCE_NOT_FOUND(4040, "资源不存在"),

    // 认证 / 授权
    UNAUTHENTICATED(4010, "未认证"),
    UNAUTHORIZED(4030, "无权限"),
    TENANT_MISMATCH(4031, "租户不匹配，拒绝跨租户访问"),

    // 服务端
    INTERNAL_ERROR(5000, "服务器内部错误");

    private final int code;
    private final String message;

    ResultCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
