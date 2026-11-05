package com.realmgate.unit;

import com.realmgate.infrastructure.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M3 单元测试：TenantContext（ThreadLocal 租户上下文）。
 * 纯工具类，验证 set/get/clear 语义；clear 必须用 remove（铁律：写前 set、finally clear）。
 */
@DisplayName("M3 单元测试：TenantContext（ThreadLocal 租户上下文）")
class TenantContextTest {

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("set 后 get 返回该租户")
    void setThenGet() {
        TenantContext.set("alpha");
        assertEquals("alpha", TenantContext.get());
    }

    @Test
    @DisplayName("未 set 时 get 返回 null")
    void getNullWhenUnset() {
        assertNull(TenantContext.get());
    }

    @Test
    @DisplayName("clear 后 get 返回 null（必须用 remove 而非 set(null)）")
    void clearRemoves() {
        TenantContext.set("beta");
        TenantContext.clear();
        assertNull(TenantContext.get());
    }
}
