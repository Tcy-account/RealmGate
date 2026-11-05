package com.realmgate;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * RealmGate 后端入口。
 * 模块化单体：所有领域模块（tenant/user/role/...）都在 com.realmgate 这一个 JVM 进程内，
 * 通过包边界划分，而不是拆成多个 Maven 模块或微服务。
 */
@SpringBootApplication
@EnableScheduling // Phase 7：驱动 RsaKeyManager 定时 JWK 轮换
public class RealmGateApplication {

    public static void main(String[] args) {
        SpringApplication.run(RealmGateApplication.class, args);
    }
}
