package com.realmgate.infrastructure.security.oauth2;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.realmgate.infrastructure.security.TenantJwtTokenCustomizer;
import com.realmgate.infrastructure.security.TenantWebAuthenticationDetails;
import com.realmgate.infrastructure.security.TokenFamilyService;

import java.time.Duration;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2Token;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.MapperFeature;
import org.springframework.security.jackson2.SecurityJackson2Modules;
import org.springframework.security.oauth2.server.authorization.jackson2.OAuth2AuthorizationServerJackson2Module;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;

@Configuration
public class AuthorizationServerConfig {

  // OAuth Client 定义（realmgate-web，公开客户端 PKCE，无 secret）。抽成方法，供 JDBC repo 种子复用。
  // 固定 PK id="realmgate-web" 且与 OAuth client_id 同名：JdbcOAuth2AuthorizationService 把
  // registered_client_id 落库，重启后 mapRow 用此 id 反查；与既有 oauth2_authorization 行一致，不报
  // DataRetrievalFailureException。切 JDBC 后表内 id 即此值，跨重启稳定。
  private RegisteredClient webClient() {
    return RegisteredClient.withId("realmgate-web")
        .clientId("realmgate-web")
        .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
        .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
        .redirectUris(uris -> {
          // 前端 realmgate-web 的真实回调（CallbackView 路由 /callback）
          uris.add("http://127.0.0.1:5173/callback");
          // 备用：Spring Security 默认 OAuth2 客户端回调写法（若未来改用 spring-boot-starter-oauth2-client）
          uris.add("http://127.0.0.1:5173/login/oauth2/code/realmgate-web");
          // Postman 桌面版两个回调：勾选 "Authorize using browser" 时官方文档两种写法都出现过，都注册最稳
          uris.add("https://oauth.pstmn.io/v1/callback");
          uris.add("https://oauth.pstmn.io/v1/browser-callback");
          // 浏览器内置调试台（PKCE 全流程）：9000 为用户端口，9001 为本机验证端口
          uris.add("http://localhost:9000/oauth-playground.html");
          uris.add("http://localhost:9001/oauth-playground.html");
        })
        .scope(OidcScopes.OPENID)
        .scope(OidcScopes.PROFILE)
        .clientSettings(ClientSettings.builder().requireProofKey(true).build())
        .postLogoutRedirectUris(uris -> uris.add("http://127.0.0.1:5173/login"))
        .tokenSettings(TokenSettings.builder()
            .accessTokenTimeToLive(Duration.ofMinutes(30))
            // 站2 Refresh Token Rotation：reuseRefreshTokens(false) 让每次刷新换发新 RT，
            // 旧 RT 立即失效（配合 RealmGateTokenGenerator 的 family 轮换）。
            .refreshTokenTimeToLive(Duration.ofDays(7))
            .reuseRefreshTokens(false)
            .build())
        .build();
  }

  // 注册 OAuth Client：切 JDBC 持久化（落 oauth2_registered_client 表，由 V12 Flyway 建）。
  // 覆盖 SAS auto-config 的默认内存 repo。JdbcRegisteredClientRepository 自带 ObjectMapper
  // （注册 SecurityJackson2Modules + OAuth2AuthorizationServerJackson2Module），client_settings /
  // token_settings 的 JSON 序列化无需自定义配置。
  @Bean
  public RegisteredClientRepository registeredClientRepository(DataSource dataSource) {
    return new JdbcRegisteredClientRepository(new JdbcTemplate(dataSource));
  }

  // 种子 realmgate-web：Flyway 已在上下文刷新阶段建好表，ApplicationRunner 在刷新后执行，
  // 若库内尚无该客户端则写入（JSON 序列化交给 JdbcRegisteredClientRepository 自身）。幂等：已存在则跳过。
  @Bean
  public org.springframework.boot.ApplicationRunner seedRegisteredClient(RegisteredClientRepository repo) {
    return args -> {
      if (repo.findByClientId("realmgate-web") == null) {
        repo.save(webClient());
      }
    };
  }

  // RSA 密钥（JWK）由 RsaKeyManager 统一管理（Phase 7：多 key 家族 + Rotation + Family 标准化）。
  // 这里只把动态 JWKSource 与 JwtEncoder 暴露成 Bean，具体生成/落盘/轮换见 RsaKeyManager。

  /**
   * 动态 JWKSource（唯一且 @Primary）：委托 RotatingJwkSource，每次取 RsaKeyManager 当前 key 家族。
   * 用于 /oauth2/jwks 暴露与资源服务器验签（宽限期内旧 key 仍可验）。
   * 标记 @Primary 是因为 RotatingJwkSource 本身不注册为 bean，此处是全局唯一的 JWKSource，
   * SAS 的授权服务器链与资源服务器链都注入它。
   */
  @Bean
  @Primary
  public JWKSource<SecurityContext> jwkSource(RsaKeyManager rsaKeyManager) {
    return new RotatingJwkSource(rsaKeyManager);
  }

  /**
   * JwtEncoder：仅用 RsaKeyManager.activeKey() 签发，NimbusJwtEncoder 会自动把 active key 的
   * kid 写进 JWS header（Family 标准化 → 消费方按 kid 选 key验签）。
   * 覆盖 SAS 默认 JwtEncoder（默认可能选 jwkSource 中第一个 key，多 key 下不稳定）。
   */
  @Bean
  public JwtEncoder jwtEncoder(RsaKeyManager rsaKeyManager) {
    // 仅用 active key 签发；NimbusJwtEncoder 会自动把 active key 的 kid 写进 JWS header。
    JWKSource<SecurityContext> activeOnly =
        (selector, ctx) -> selector.select(new JWKSet(List.of((JWK) rsaKeyManager.activeKey())));
    return new NimbusJwtEncoder(activeOnly);
  }

  // 资源服务器验签：复用 AS 的动态 jwkSource（能验签 active + history 所有 key）。
  // 注：SAS 默认 jwtDecoder 不强制时间戳（过期 token 可被 RS 接受），时间戳校验统一在
  // RevocationCheckingJwtDecoder.decode 内补（见该类的 JwtTimestampValidator），与黑名单/家族检查同处。
  @Bean
  public JwtDecoder jwtDecoder(JWKSource<SecurityContext> jwkSource) {
    return OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource);
  }

  @Bean
  public OAuth2TokenCustomizer<JwtEncodingContext> tenantJwtTokenCustomizer() {
    return new TenantJwtTokenCustomizer();
  }

  /**
   * 站2：覆盖 SAS 默认 OAuth2TokenGenerator。用 @Primary 确保 SAS 内部（授权码/刷新 provider）
   * 注入此实现而非默认：
   *  - ACCESS_TOKEN / OIDC ID_TOKEN 委派内部 JwtGenerator（复用 TenantJwtTokenCustomizer 写
   *    tenant/permissions/jti/family_id）；
   *  - REFRESH_TOKEN 用 JwtEncoder 自生成 JWT（带 family_id + jti + tenant_id + typ=Refresh），
   *    并在签发时维护 family 状态（首次建族、续刷轮换）。
   */
  @Bean
  @Primary
  public OAuth2TokenGenerator<OAuth2Token> realmGateTokenGenerator(
      JwtEncoder jwtEncoder,
      OAuth2TokenCustomizer<JwtEncodingContext> tenantJwtTokenCustomizer,
      TokenFamilyService tokenFamilyService) {
    // 站2：RT 用 SAS 原生不透明 OAuth2RefreshToken（JWT 形式自定义子类无法被 SAS 的 Jackson 序列化/反查，
    // 续刷 findByToken 取不到 authorization）。family/jti/tenant 经 TokenFamilyService 存 Redis 绑定。
    return new RealmGateTokenGenerator(jwtEncoder, tenantJwtTokenCustomizer, tokenFamilyService);
  }

  /**
   * 站2（续）+ JDBC 授权存储：把 OAuth2 授权记录（授权码、AT、RT、id_token 等）持久化到 PG，
   * 解决内存存储下"重启后已签发 RT 失效"的问题。仅定义此 bean 即覆盖 SAS auto-config 默认的内存
   * OAuth2AuthorizationService。
   *  - DataSource：application-dev.yml 的 spring.datasource（PG）。
   *  - RegisteredClientRepository：复用本类已定义的 InMemoryRegisteredClientRepository（仅用于按
   *    registeredClientId 解析客户端，不要求它也是 JDBC）。
   *  - 表结构由 Flyway V9（建表）+ V10（token value 列定为 text：反编译确证 JdbcOAuth2AuthorizationService
   *    对 token value 列用 ps.setString 写入，对应 text/varchar，绝不能 bytea）提供。
   *  - 命名 jdbcOAuth2AuthorizationService + @Primary：SAS 的 RegisterMissingBeanPostProcessor 按
   *    类型 OAuth2AuthorizationService 探测，若已存在同类型 bean 就跳过默认注册；同名(固定名
   *    oauth2AuthorizationService)会触发定义覆盖导致本自定义 bean 被架空。改用不同名 + @Primary，
   *    SAS 按类型注入时必选 @Primary 的本实例，确保自定义 JDBC 存储与 ObjectMapper 真正生效。
   *  - 自定义 ObjectMapper（读写两侧共用同一个 om）：
   *      · 注册 SecurityJackson2Modules.getModules + OAuth2AuthorizationServerJackson2Module，
   *        提供 OAuth2/Spring Security 各类型的反序列化 mixin（与 SAS 默认一致）。
   *      · SecurityJackson2Modules.enableDefaultTyping(om)：与 SAS 默认完全一致的 {@class} 多态格式，
   *        read 侧 parseMap 与 write 侧 writeMap 格式自洽（反编译 AllowlistTypeIdResolver 确证
   *        其把全部方法委托给标准 ClassNameIdResolver，仅用精确类名白名单 + findMixInClassFor 放行）。
   *      · TenantWebAuthenticationDetails 是自有类（com.realmgate.**），SAS 默认白名单不含它。
   *        给它注册一个 mixin（addMixIn(类, 类自身)）后，AllowlistTypeIdResolver.typeFromId 的
   *        findMixInClassFor 返回非空 → 自动放行，无需自定义 PolymorphicTypeValidator、无格式错配。
   *      · 启用 ALLOW_FINAL_FIELDS_AS_MUTATORS 让 TenantWebAuthenticationDetails.tenant(final) 可反射写回。
   *  - 事务由 SAS auto-config 在检测到本 bean 为 JdbcOAuth2AuthorizationService 时自动织入。
   */
  @Bean
  @Primary
  public OAuth2AuthorizationService jdbcOAuth2AuthorizationService(
      DataSource dataSource,
      RegisteredClientRepository registeredClientRepository) {
    // SAS 1.5.1 的便捷构造收 JdbcOperations（非 DataSource），用 JdbcTemplate 适配；
    // JdbcTemplate 实现 JdbcOperations，且由 spring-jdbc 提供（SAS 已传递依赖）。
    JdbcOAuth2AuthorizationService service =
        new JdbcOAuth2AuthorizationService(new JdbcTemplate(dataSource), registeredClientRepository);

    // 自定义 Jackson：复刻 SAS 默认格式(enableDefaultTyping -> {@class})并放行自有 TenantWebAuthenticationDetails。
    ObjectMapper om = new ObjectMapper();
    ClassLoader cl = AuthorizationServerConfig.class.getClassLoader();
    om.registerModules(SecurityJackson2Modules.getModules(cl));
    om.registerModule(new OAuth2AuthorizationServerJackson2Module());
    // 与 SAS 默认完全一致的多态格式（{@class}）；白名单由 AllowlistTypeIdResolver 管控。
    SecurityJackson2Modules.enableDefaultTyping(om);
    // 自有类的反序列化放行：注册 mixin 使 findMixInClassFor 非空（AllowlistTypeIdResolver 据此放行）。
    om.addMixIn(TenantWebAuthenticationDetails.class, TenantWebAuthenticationDetails.class);
    om.configure(MapperFeature.ALLOW_FINAL_FIELDS_AS_MUTATORS, true);

    // 读写共用同一个 om：read 侧行映射器 + write 侧参数映射器都要织入，格式才一致。
    JdbcOAuth2AuthorizationService.OAuth2AuthorizationRowMapper rowMapper =
        new JdbcOAuth2AuthorizationService.OAuth2AuthorizationRowMapper(registeredClientRepository);
    rowMapper.setObjectMapper(om);
    service.setAuthorizationRowMapper(rowMapper);

    JdbcOAuth2AuthorizationService.OAuth2AuthorizationParametersMapper paramsMapper =
        new JdbcOAuth2AuthorizationService.OAuth2AuthorizationParametersMapper();
    paramsMapper.setObjectMapper(om);
    service.setAuthorizationParametersMapper(paramsMapper);
    return service;
  }
}
