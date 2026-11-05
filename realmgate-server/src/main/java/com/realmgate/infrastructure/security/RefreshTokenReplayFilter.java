package com.realmgate.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 站2 Replay Detection：在 refresh 请求到达 SAS 的 token endpoint 之前拦截。
 *
 * RT 是不透明 OAuth2RefreshToken（无 claim），故按 tokenValue 走 Redis 绑定
 * （TokenFamilyService.resolveRefresh）反查 tenant/family/jti，再查 family 状态：
 *  - REPLAY（同一 RT 被用过 → 当前有效 jti 已变）：判定重放，整族立即失效，直接 401
 *  - FAMILY_REVOKED（整族已撤销）：401
 *  - 绑定不存在（RT 未知/过期/非本系统签发）：401
 *  - OK：放行给默认 OAuth2RefreshTokenAuthenticationProvider 正常换发（新 RT 由 RealmGateTokenGenerator 做 rotation）
 *
 * 本类由 SecurityConfig 手动 new 后挂到链①（addFilterAfter(CorsFilter.class)），
 * 仅依赖注入的 TokenFamilyService 做 RT 反查 + family 校验。
 */
public class RefreshTokenReplayFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenReplayFilter.class);

    private final TokenFamilyService familyService;

    public RefreshTokenReplayFilter(TokenFamilyService familyService) {
        this.familyService = familyService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        boolean skip = true;
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            skip = true;
        } else if (!"/oauth2/token".equals(request.getRequestURI())) {
            skip = true;
        } else {
            // 只拦刷新授予；其他 grant（授权码/客户端凭证）走默认逻辑
            skip = !"refresh_token".equals(request.getParameter("grant_type"));
        }
        log.debug("REPLAY_SHOULD_NOT_FILTER skip={} method={} uri={} grant={}",
                skip, request.getMethod(), request.getRequestURI(), request.getParameter("grant_type"));
        return skip;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        log.info("REPLAY_FILTER_ENTER uri={} grant={}", request.getRequestURI(), request.getParameter("grant_type"));
        try {
            doCheck(request, response, chain);
        } catch (Throwable t) {
            // 兜底：任何异常都打栈并放行（fail-open），避免被 ExceptionTranslationFilter 重定向到登录页掩盖真实错误。
            log.error("REPLAY_FILTER_FAIL grant_type={} rtLen={}", request.getParameter("grant_type"),
                    request.getParameter("refresh_token") != null
                            ? request.getParameter("refresh_token").length() : -1, t);
            chain.doFilter(request, response);
        }
    }

    private void doCheck(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String rtValue = request.getParameter("refresh_token");
        log.info("REPLAY_CHECK rtLen={}", rtValue != null ? rtValue.length() : -1);
        if (rtValue == null) {
            chain.doFilter(request, response);
            return;
        }
        // 不透明 RT：按 tokenValue 反查绑定（tenant/family/jti）。取不到 → 未知/过期 RT。
        TokenFamilyService.RefreshBinding binding = familyService.resolveRefresh(rtValue);
        if (binding == null) {
            log.info("REPLAY_CHECK binding=null -> 401 invalid");
            write401(response, "invalid refresh token");
            return;
        }
        log.info("REPLAY_CHECK family={} tenant={} jti={}", binding.familyId, binding.tenantId, binding.jti);
        TokenFamilyService.ValidateResult result = familyService.validateRefresh(
                binding.tenantId, binding.familyId, binding.jti);
        log.info("REPLAY_CHECK validateResult={}", result);
        if (result == TokenFamilyService.ValidateResult.REPLAY) {
            // 同一 RT 被第二次使用 → 判定重放（可能被盗），整族立即失效，旧 AT 也随之被拒。
            familyService.revokeFamily(binding.tenantId, binding.familyId);
            write401(response, "refresh token replay detected");
            return;
        }
        if (result == TokenFamilyService.ValidateResult.FAMILY_REVOKED) {
            write401(response, "refresh token revoked");
            return;
        }
        // OK：放行给默认 OAuth2RefreshTokenAuthenticationProvider 正常换发（新 RT 由 RealmGateTokenGenerator 做 rotation）
        chain.doFilter(request, response);
    }

    private void write401(HttpServletResponse response, String desc) throws IOException {
        response.setStatus(401);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"error\":\"invalid_grant\",\"error_description\":\"" + desc + "\"}");
    }
}
