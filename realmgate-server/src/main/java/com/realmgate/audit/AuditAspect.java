package com.realmgate.audit;

import com.realmgate.admin.ClientUpsertRequest;
import com.realmgate.admin.UserUpsertRequest;
import com.realmgate.common.api.ApiResponse;
import com.realmgate.platform.tenant.Tenant;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 审计写切面：拦截所有打了 {@link Audit} 的 admin 变更方法，成功后自动落审计日志。
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li>用 {@code @AfterReturning} —— 只在方法<b>成功返回</b>后记录（异常/403 不记）。</li>
 *   <li>独立 {@code REQUIRES_NEW} 事务 —— 即便主事务后续回滚，审计行也已落盘；且审计自身失败被 try/catch 吞掉，绝不连累主流程。</li>
 *   <li>操作人/租户从 JWT claim 取（资源服务器 principal 即 {@link Jwt}），不依赖 TenantContext。</li>
 *   <li>detail 摘要对 password / clientSecret 等敏感字段做屏蔽，绝不原样 toString 请求体。</li>
 * </ul>
 */
@Aspect
@Component
@Slf4j
@RequiredArgsConstructor
public class AuditAspect {

    private final AuditLogRepository repository;

    @AfterReturning(pointcut = "@annotation(audit)", returning = "ret")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(JoinPoint jp, Audit audit, Object ret) {
        try {
            String tenantId = currentTenant();
            String actor = currentActor();
            if (tenantId == null || actor == null) {
                log.warn("AUDIT_SKIP 无法定位租户/操作人，跳过审计 action={} resource={}",
                        audit.action(), audit.resourceType());
                return;
            }

            AuditLog entry = new AuditLog();
            entry.setTenantId(tenantId);
            entry.setActor(actor);
            entry.setAction(audit.action().name());
            entry.setResourceType(audit.resourceType());
            entry.setResourceId(extractResourceId(jp, ret));
            entry.setDetail(buildDetail(jp));
            entry.setIpAddress(currentIp());
            entry.setCreatedAt(Instant.now());
            repository.save(entry);
        } catch (Exception ex) {
            // 审计失败绝不能影响业务主流程
            log.error("AUDIT_FAIL action={} resource={}", audit.action(), audit.resourceType(), ex);
        }
    }

    // ---- 操作人 / 租户 ----

    private String claim(String name) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return null;
        Object principal = auth.getPrincipal();
        if (principal instanceof Jwt jwt) {
            return jwt.getClaimAsString(name);
        }
        return null;
    }

    private String currentTenant() {
        return claim("tenant_id");
    }

    private String currentActor() {
        String username = claim("preferred_username");
        if (username != null) return username;
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? null : auth.getName();
    }

    // ---- 资源 id ----

    private String extractResourceId(JoinPoint jp, Object ret) {
        // 1) 优先取 @PathVariable（update/delete 的 id）
        MethodSignature ms = (MethodSignature) jp.getSignature();
        Annotation[][] paramAnnos = ms.getMethod().getParameterAnnotations();
        Object[] args = jp.getArgs();
        for (int i = 0; i < args.length; i++) {
            for (Annotation a : paramAnnos[i]) {
                if (a instanceof PathVariable) {
                    Object v = args[i];
                    return v == null ? null : String.valueOf(v);
                }
            }
        }
        // 2) create 无 pathvar → 从返回值 ApiResponse.data 反射取 id
        try {
            if (ret instanceof ApiResponse<?> ap && ap.getData() != null) {
                Method getId = ap.getData().getClass().getMethod("getId");
                Object id = getId.invoke(ap.getData());
                if (id != null) return String.valueOf(id);
            }
        } catch (Exception ignored) {
            // 反射失败就返回 null，detail 里仍有可读摘要
        }
        return null;
    }

    // ---- detail 摘要（屏蔽敏感字段） ----

    private String buildDetail(JoinPoint jp) {
        StringBuilder sb = new StringBuilder();
        for (Object arg : jp.getArgs()) {
            if (arg == null || arg instanceof HttpServletRequest) continue;
            sb.append(summary(arg)).append(" | ");
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private String summary(Object arg) {
        // 显式屏蔽密码 / 客户端密钥：只输出非敏感标识
        if (arg instanceof UserUpsertRequest u) {
            return "user:" + u.username() + ",email:" + u.email();
        }
        if (arg instanceof ClientUpsertRequest c) {
            return "client:" + c.clientName();
        }
        if (arg instanceof Tenant t) {
            return "tenant:" + t.getSlug();
        }
        // 其他实体：反射取 id（避免 toString 泄露内部字段），无 id 则只记类型名
        try {
            Method getId = arg.getClass().getMethod("getId");
            Object id = getId.invoke(arg);
            if (id != null) return arg.getClass().getSimpleName() + "#" + id;
        } catch (Exception ignored) {
            // 无 getId 或反射失败 → 只记类型名
        }
        return arg.getClass().getSimpleName();
    }

    // ---- 客户端 IP ----

    private String currentIp() {
        try {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) return null;
            HttpServletRequest req = attrs.getRequest();
            String xff = req.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
            return req.getRemoteAddr();
        } catch (Exception ignored) {
            return null;
        }
    }
}
