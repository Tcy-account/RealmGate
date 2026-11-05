package com.realmgate.infrastructure.security;

import java.io.IOException;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import com.realmgate.infrastructure.security.oauth2.RefreshGrantClientAuthenticationConverter;
import com.realmgate.infrastructure.security.oauth2.RefreshGrantPublicClientAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.List;
import org.springframework.core.Ordered;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 安全配置（Phase 3）：单链拆为三链。
 * 链① @Order(1) 授权服务器端点；链② @Order(2) 业务 API 资源服务器；链③ @Order(3) 兜底 /login 默认页。
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity   // 站8：开启方法级安全，admin 端点用 @PreAuthorize("hasAuthority('xxx:manage')") 保护
public class SecurityConfig {

    // 链①：授权服务器端点（/oauth2/**、/.well-known/**、登录/同意页由 AS 管）
    @Bean
    @Order(1)
    public SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http,
            RefreshTokenReplayFilter refreshTokenReplayFilter,
            StandardTokenEndpointsFilter standardTokenEndpointsFilter,
            RegisteredClientRepository registeredClientRepository) throws Exception {
        OAuth2AuthorizationServerConfiguration.applyDefaultSecurity(http);
        http.getConfigurer(OAuth2AuthorizationServerConfigurer.class)
                .oidc(oidc -> oidc
                        // 站③：OIDC discovery 暴露标准端点字段（RFC 7009 revocation_endpoint + OIDC end_session_endpoint），
                        // 供 SPA（oidc-client-ts）自动发现，无需硬编码端点路径。
                        // 注意：SAS 原生 tokenRevocationEndpoint / logoutEndpoint 走 JDBC authorization 标记，
                        // 对自包含 JWT Bearer 无效（资源服务器只验签不查 authorization 表），故不启用，
                        // 改由 StandardTokenEndpointsFilter 走 Redis 黑名单 + 整族失效实现。
                        .providerConfigurationEndpoint(pce -> pce
                                .providerConfigurationCustomizer(builder -> builder
                                        // 注意：OIDC discovery 规范（及 SAS 的 Builder 校验）要求这些字段是绝对 URL，
                                        // 传相对路径会抛 "tokenRevocationEndpoint must be a valid URL"。dev 用固定 base，
                                        // 生产应改为从 AuthorizationServerSettings.getIssuer() 动态取。
                                        .claim("revocation_endpoint", "http://localhost:9000/oauth2/revoke")
                                        .claim("end_session_endpoint", "http://localhost:9000/oauth2/logout"))))
                // 站2：补公开客户端 refresh_token 授予的客户端认证缺口。
                // SAS 的 PublicClientAuthenticationConverter 仅对 authorization_code+code_verifier（PKCE）提取 client_id；
                // refresh_token 不带 code_verifier → 该 converter 返回 null → 客户端不被认证 → 匿名 → 302 登录页。
                // RefreshGrantClientAuthenticationConverter 接管 refresh_token 授予，把公开客户端认证为 NONE。
                // 追加到默认 4 个转换器之前（configurer 的 init() 用 addAll(0,...) 前置），互不冲突。
                .clientAuthentication(client -> {
                    client.authenticationConverters(
                            converters -> converters.add(0, new RefreshGrantClientAuthenticationConverter()));
                    // 站2（修复 refresh 400 invalid_grant）：SAS 1.5.1 的 PublicClientAuthenticationProvider
                    // 对所有 NONE 方法请求强制 code_verifier，但该校验器仅对 authorization_code 返回 true，
                    // 导致 refresh_token 授予必抛 invalid_grant。此 provider 前插到默认列表最前，仅对
                    // grant_type=refresh_token 的公开客户端跳过 code_verifier（仅校验 client_id 已注册），
                    // 其余授予类型返回 null 交给默认 PublicClientAuthenticationProvider（保留 PKCE 保护）。
                    client.authenticationProviders(
                            providers -> providers.add(0,
                                    new RefreshGrantPublicClientAuthenticationProvider(registeredClientRepository)));
                });
        // 关键：AS 端点（/oauth2/**、/.well-known/**、/userinfo）必须显式开启 CORS。
        // 浏览器 SPA（oidc-client-ts）跨域 POST /oauth2/token（带 PKCE code 换 token）会先发
        // OPTIONS 预检；不开启则预检落到 AS 端点自身 → 返 403，SPA 拿不到 token。
        // 用 configurationSource(...) 显式绑定，避免依赖 bean 名查找的歧义。
        http.cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .exceptionHandling(ex -> ex.authenticationEntryPoint(
                        new LoginUrlAuthenticationEntryPoint("/login.html")))
                // 注：链①是授权服务器（AS），本身不是资源服务器，不要在此挂 oauth2ResourceServer。
                // 资源服务器是链②（/api/**）。此前在此挂 oauth2ResourceServer 会把 BearerTokenAuthenticationFilter
                // 强加进 AS 链，干扰 refresh 授予的客户端认证——表现为 refresh 请求被 AnonymousAuthenticationFilter
                // 标为匿名 → AuthorizationFilter 拒绝 → 302 到登录页。已移除。
                // 站2：refresh 请求进入时先做 replay/revoked 检测，再交给 SAS 默认 refresh provider。
                // OAuth2TokenEndpointFilter 在 Spring Security 6.5 未登记 order，不能做 addFilterBefore 锚点；
                // 改用已登记的 CorsFilter 作锚——CORS 必在 token endpoint 之前执行，故插在 CORS 之后即稳稳早于它。
                .addFilterAfter(refreshTokenReplayFilter, CorsFilter.class)
                // 站③：标准 revoke/logout 端点。在 CorsFilter 之后、认证/授权 filter 之前短路处理，
                // 避免被 AS 链默认 anyRequest().authenticated() 拦截要求 Bearer。
                .addFilterAfter(standardTokenEndpointsFilter, CorsFilter.class);
        return http.build();
    }

    // 链②：业务 API，作为资源服务器（只认 SAS 签发的 JWT）
    @Bean
    @Order(2)
    public SecurityFilterChain apiSecurityFilterChain(HttpSecurity http,
            JwtDecoder jwtDecoder,                       // 注入原 jwtDecoder Bean（来自 AuthorizationServerConfig）
            TokenRevocationService tokenRevocationService,
            TokenFamilyService tokenFamilyService,
            com.realmgate.infrastructure.observability.SecurityMetrics securityMetrics) throws Exception {
        http.securityMatcher("/api/**", "/actuator/**", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                .csrf(csrf -> csrf.disable())
                // 关键：API 链必须无状态。否则浏览器带着 JSESSIONID 就能靠 session 认证通过，
                // 绕过 Bearer token 校验（Phase 2 "无 token 返 401" 的红线会失效）
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/ping", "/actuator/health", "/actuator/prometheus").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .anyRequest().authenticated())
                // 关键：API 链②必须用自定义 converter 从 JWT 的 `permissions` claim 解析 authorities，
                // 且 authorityPrefix=""（与 TenantJwtTokenCustomizer 写入的权限键一致，如 task:create）。
                // 默认 converter 会从 `scope` claim 取权限并加 SCOPE_ 前缀，导致 permissions 被忽略、
                // 业务层 hasProjectAuthority 永远拿不到权限 → 全部 403（曾因"闭环②"未落实而断链）。
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt
                        // 站1 + 站2：用装饰器包住原 decoder，先验签再查 jti 黑名单 + family 整族失效
                        .decoder(new RevocationCheckingJwtDecoder(jwtDecoder, tokenRevocationService, tokenFamilyService, securityMetrics))
                        .jwtAuthenticationConverter(rbacJwtAuthenticationConverter())))
                .addFilterAfter(tenantResolverFilter(), org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    // 链③：兜底（无 matcher，匹配其余全部）—— 提供 /login 默认登录页

    @Bean
    @Order(3)
    public SecurityFilterChain appSecurityFilterChain(HttpSecurity http,
            LoginRateLimitService loginRateLimitService,
            StandardTokenEndpointsFilter standardTokenEndpointsFilter,
            com.realmgate.infrastructure.observability.SecurityMetrics securityMetrics) throws Exception {   // 站2：注入限流服务
        // 关键：兜底链也要开 CORS。
        // SAS 的授权服务器链①只把 /oauth2/token 当成 POST 端点（其 securityMatcher 不含 OPTIONS），
        // 因此浏览器对 token 端点的 CORS 预检(OPTIONS) 不匹配链①，
        // 会落到本兜底链 → 被当成未认证请求 → 踢去 /login.html(302)，SPA 拿不到 token。
        // 在本链开 .cors() 后，CorsFilter 会在登录重定向之前短路预检，直接返 200 + ACAO。
        http.cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .authorizeHttpRequests(auth -> auth
                // 关键：登录页与静态资源必须放行。否则请求 /login.html 会被本链拦截
                // → 未认证 → 重定向到登录页(/login.html) 自己 → ERR_TOO_MANY_REDIRECTS
                // oauth-playground.html 是本地调试页（PKCE 全流程），也必须放行，
                // 否则授权码回调 ?code=... 落到本页时会被拦成登录页，读不到 code
                .requestMatchers("/login.html", "/oauth-playground.html", "/favicon.ico", "/css/**", "/js/**",
                        "/images/**")
                .permitAll()
                .anyRequest().authenticated())
                .formLogin(form -> form
                        .loginPage("/login.html")
                        // 必须显式指定！否则 FormLoginConfigurer 会把处理 URL 默认设成 loginPage（/login.html），
                        // 导致表单提交的 POST /login 无人接管 → 被当成未认证请求 → 踢回登录页（死循环观感）
                        .loginProcessingUrl("/login")
                        .authenticationDetailsSource(new TenantAuthenticationDetailsSource())
                        .successHandler(new AuthenticationSuccessHandler() {
                            private final AuthenticationSuccessHandler delegate = new SavedRequestAwareAuthenticationSuccessHandler();

                            @Override
                            public void onAuthenticationSuccess(HttpServletRequest request,
                                    HttpServletResponse response, Authentication authentication)
                                    throws IOException, ServletException {
                                LoginTenantHolder.clear(); // 登录成功后清理，避免线程污染
                                // 站2：登录成功清除该用户/IP 的失败计数与锁，避免累积误伤
                                loginRateLimitService.onSuccess(request.getParameter("tenant"),
                                        request.getParameter("username"), clientIp(request));
                                delegate.onAuthenticationSuccess(request, response, authentication);
                            }
                        })
                        // 站2：登录失败累加计数 + 达阈值置锁
                        .failureHandler(new LoginFailureRateLimitHandler(loginRateLimitService, securityMetrics)))
                .csrf(csrf -> csrf.disable())
                // 站2：在表单认证过滤器之前插预检查 Filter（已锁定直接 429，不进认证）
                .addFilterBefore(loginRateLimitFilter(loginRateLimitService), UsernamePasswordAuthenticationFilter.class)
                // 站③：logout 端点 /oauth2/logout 不在 AS 链①的 endpointsMatcher 内（SAS 默认不启用
                // OIDC logout 端点，故未预留该路径），链①不接收此请求会落到本兜底链并要求认证→302。
                // 把标准端点 Filter 也注册到兜底链，在认证过滤器之前短路处理 logout（revoke 仍在链①处理）。
                .addFilterBefore(standardTokenEndpointsFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    // 站2：refresh 进入时 replay/revoked 检测的 Filter 注册为 Bean
    @Bean
    public RefreshTokenReplayFilter refreshTokenReplayFilter(TokenFamilyService tokenFamilyService) {
        return new RefreshTokenReplayFilter(tokenFamilyService);
    }

    // 站③：标准 revoke/logout 端点 Filter 注册为 Bean（注入原始 jwtDecoder 解码，不走黑名单装饰器）
    @Bean
    public StandardTokenEndpointsFilter standardTokenEndpointsFilter(
            TokenFamilyService tokenFamilyService,
            TokenRevocationService tokenRevocationService,
            JwtDecoder jwtDecoder) {
        return new StandardTokenEndpointsFilter(tokenFamilyService, tokenRevocationService, jwtDecoder);
    }

    // 站2：登录限流预检查 Filter 注册为 Bean
    @Bean
    public LoginRateLimitFilter loginRateLimitFilter(LoginRateLimitService loginRateLimitService) {
        return new LoginRateLimitFilter(loginRateLimitService);
    }

    // 站2：取真实客户端 IP（直连 9000 走 remoteAddr；兼容前置网关的 X-Forwarded-For）
    private String clientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        return req.getRemoteAddr();
    }

    @Bean
    public TenantResolverFilter tenantResolverFilter() {
        return new TenantResolverFilter();
    }

    // 为 OIDC 端点开放 CORS：SPA(5173) 会跨域 fetch discovery/token/jwks。
    // 注意 API(/api/**) 仍走 Vite 代理，不需要 CORS。
    // 用 allowedOriginPatterns（而非 allowedOrigins）配合 allowCredentials=true：
    // 允许带凭证的跨域，并动态回显请求 Origin（allowedOrigins 在带凭证时不能用通配，
    // patterns 写法更稳，也方便本地换端口）。
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOriginPatterns(List.of("http://127.0.0.1:*", "http://localhost:*"));
        cfg.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("*"));
        cfg.setAllowCredentials(true);
        cfg.setExposedHeaders(List.of("Access-Control-Allow-Origin"));
        UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource();
        src.registerCorsConfiguration("/oauth2/**", cfg);
        src.registerCorsConfiguration("/.well-known/**", cfg);
        src.registerCorsConfiguration("/userinfo", cfg);
        return src;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    /**
     * 业务 API 链②的资源服务器转换器：从 JWT 的 `permissions` claim 解析 authorities，
     * 不加任何前缀（authorityPrefix=""），与 TenantJwtTokenCustomizer 写入 token 的权限键
     * （如 task:create / project:delete / member:manage）完全一致。
     *
     * 若用默认 converter，会从 `scope` claim 取权限制成 SCOPE_xxx，导致 permissions 被忽略、
     * 业务层 hasProjectAuthority 永远拿不到权限 → 全接口 403。这是必须显式替换默认的关键点。
     */
    @Bean
    public JwtAuthenticationConverter rbacJwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authoritiesConverter = new JwtGrantedAuthoritiesConverter();
        authoritiesConverter.setAuthoritiesClaimName("permissions");
        authoritiesConverter.setAuthorityPrefix("");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authoritiesConverter);
        return converter;
    }
}
