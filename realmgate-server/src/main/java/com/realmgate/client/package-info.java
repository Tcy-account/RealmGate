/**
 * OAuth2 Client（oauth_client 表）。
 * 代表一个使用 RealmGate 认证能力的应用，如 realmgate-web。
 * 每个租户可拥有自己的 Client，client_id 租户内唯一（UNIQUE(tenant_id, client_id)）。
 */
package com.realmgate.client;
