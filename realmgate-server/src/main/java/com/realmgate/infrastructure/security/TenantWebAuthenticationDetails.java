package com.realmgate.infrastructure.security;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeName;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 多租户登录详情：在 WebAuthenticationDetails（remoteAddress/sessionId）之上附 tenant。
 *
 * JDBC 授权存储要求它可被 Jackson 序列化/反序列化：SAS 的 JdbcOAuth2AuthorizationService 会把
 * 整个登录 Authentication（含本对象作为 details）序列化进 oauth2_authorization.attributes。
 * 默认 ObjectMapper 的 PolymorphicTypeValidator 仅放行 org.springframework.security.**，故：
 *  - @JsonTypeName 让反序列化时按类全名匹配本类；
 *  - 无参构造供 Jackson 实例化（tenant 由 ALLOW_FINAL_FIELDS_AS_MUTATORS 反射写回，见 AuthorizationServerConfig）。
 *  - @JsonIgnoreProperties 容忍未知字段，避免反序列化因 WebAuthenticationDetails 内部字段变化而失败。
 */
@JsonTypeName("com.realmgate.infrastructure.security.TenantWebAuthenticationDetails")
@JsonIgnoreProperties(ignoreUnknown = true)
public class TenantWebAuthenticationDetails extends WebAuthenticationDetails {
  @JsonProperty("tenant")
  private final String tenant;

  public TenantWebAuthenticationDetails(HttpServletRequest request, String tenant) {
    super(request);
    this.tenant = tenant;
  }

  // Jackson 反序列化专用无参构造：父类仅有无参(String,String)构造，传 null 即可。
  protected TenantWebAuthenticationDetails() {
    super((String) null, null);
    this.tenant = null;
  }

  public String getTenant() {
    return tenant;
  }
}
