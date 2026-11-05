package com.realmgate.infrastructure.security;

public final class LoginTenantHolder {
  private static final ThreadLocal<String> TENANT = new ThreadLocal<>();

  public static void set(String t) {
    TENANT.set(t);
  }

  public static String get() {
    return TENANT.get();
  }

  public static void clear() {
    TENANT.remove();
  }
}
