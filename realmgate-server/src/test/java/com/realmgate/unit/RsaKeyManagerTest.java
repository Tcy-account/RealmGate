package com.realmgate.unit;

import com.realmgate.infrastructure.security.oauth2.RsaKeyManager;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M3 单元测试：RsaKeyManager（密钥生成 / 加载 / 轮换）。
 * 不依赖 Spring：手动 new + 反射注入 jwkLocation + 手动调 init()（@PostConstruct 不自动触发）。
 * 覆盖 Phase 7 的关键不变量：active 必须带私钥、rotate 后旧 active 降级 history 仍可被验签（宽限期）。
 */
@DisplayName("M3 单元测试：RsaKeyManager（密钥生成 / 加载 / 轮换）")
class RsaKeyManagerTest {

    @TempDir
    Path tempDir;

    private RsaKeyManager newManager() throws Exception {
        // redis=null → 纯文件兜底模式（兼容单测，不依赖 Redis）
        RsaKeyManager mgr = new RsaKeyManager(null);
        Field f = RsaKeyManager.class.getDeclaredField("jwkLocation");
        f.setAccessible(true);
        f.set(mgr, tempDir.resolve("jwk.json").toString());
        return mgr;
    }

    @Test
    @DisplayName("无密钥文件时 init 生成带私钥的 active key（Family 标准化：use=sig/alg=RS256/kid 非空）")
    void init_generatesActiveKeyWhenMissing() throws Exception {
        RsaKeyManager mgr = newManager();
        mgr.init();

        RSAKey active = mgr.activeKey();
        assertNotNull(active);
        assertTrue(active.isPrivate(), "active key 必须含私钥，否则重启后无法签发 token（坑#2）");
        assertEquals(KeyUse.SIGNATURE, active.getKeyUse());
        assertEquals(JWSAlgorithm.RS256, active.getAlgorithm());
        assertNotNull(active.getKeyID());
        assertEquals(1, mgr.allKeys().size());
    }

    @Test
    @DisplayName("已有密钥文件时 init 加载：active=数组末位、私钥已落盘、旧 active 留作 history")
    void init_loadsExistingKeyAndActiveIsLast() throws Exception {
        // 先写一个 manager 落盘（1 key），再 rotate 一次制造历史（旧 active 在前、新 active 末位）
        RsaKeyManager mgr1 = newManager();
        mgr1.init();
        String oldKid = mgr1.activeKid();
        String rotatedKid = mgr1.rotate();

        RsaKeyManager mgr2 = newManager(); // 同路径
        mgr2.init();

        // active 必须为 rotate 后新生成的那把（末位约定）
        assertEquals(rotatedKid, mgr2.activeKid());
        // 私钥随 JWKSet 一起落盘：重载后 active 仍持私钥，可直接签发（坑#2 真正修复的回归锁）
        assertTrue(mgr2.activeKey().isPrivate(), "重载后 active 必须持私钥，否则重启无法签发 token");
        // 全部 key 都应带私钥（persist 用 toJSONObject(true) 逐 key 落盘）
        assertTrue(mgr2.allKeys().stream().allMatch(RSAKey::isPrivate),
                "重载后所有 key 都应带私钥");
        // 旧 active 仍保留为 history（宽限期内旧 token 可验签）
        assertTrue(mgr2.allKeys().stream().anyMatch(k -> k.getKeyID().equals(oldKid)),
                "旧 active 应保留为 history 供验签");
        assertEquals(2, mgr2.allKeys().size());
    }

    @Test
    @DisplayName("rotate 把旧 active 降级 history、生成新 active（宽限期内旧 key 仍可验签）")
    void rotate_movesActiveToHistoryAndGeneratesNewActive() throws Exception {
        RsaKeyManager mgr = newManager();
        mgr.init();
        String oldKid = mgr.activeKid();
        String newKid = mgr.rotate();

        assertNotEquals(oldKid, newKid);
        assertEquals(newKid, mgr.activeKid());
        // 旧 active 仍保留在 key 家族（history），宽限期内旧 token 可验签
        boolean oldStillPresent = mgr.allKeys().stream().anyMatch(k -> k.getKeyID().equals(oldKid));
        assertTrue(oldStillPresent, "rotate 后旧 active 应保留为 history 供验签");
        // HISTORY_RETAIN=2，本测试最多 2 把
        assertEquals(2, mgr.allKeys().size());
        assertTrue(mgr.activeKey().isPrivate());
    }
}
