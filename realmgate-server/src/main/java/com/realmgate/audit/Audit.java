package com.realmgate.audit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 方法级审计标记。打在 admin 变更端点（create/update/delete 等）上，
 * 由 {@link AuditAspect} 在方法成功返回后自动写一条审计日志。
 *
 * <p>仅记录<b>成功且已授权</b>的变更：@PreAuthorize 拒绝（403）时方法不执行，
 * 切面也不会触发；方法抛异常（@AfterReturning 不触发）同样不记录。</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Audit {
    /** 资源类型，如 USER / ROLE / PERMISSION / CLIENT / TENANT。 */
    String resourceType();

    /** 操作类型。 */
    AuditAction action();
}
