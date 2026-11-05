package com.realmgate.infrastructure.security;


public final class TenantContext {
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private TenantContext() {} // 工具类，禁止实例化

    public static void set(String tenantId) { CURRENT.set(tenantId); }
    public static String get()             { return CURRENT.get(); }
    public static void clear()             { CURRENT.remove(); } // 必须用 remove，不是 set(null)
}

