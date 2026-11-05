-- Phase 7 站2 + JDBC 授权存储：修正 oauth2_authorization 的 token value 列类型。
--
-- 背景：SAS 1.5.1 的 JdbcOAuth2AuthorizationService 经 LobCreatorArgumentPreparedStatementSetter
-- 对 token value 列用 String 参数写入（ps.setString，对应 text），并非 bytea。这些列必须是 text。
--
-- 关键修复（幂等、类型无关）：
-- 原写法 `convert_from(authorization_code_value, 'UTF8')` 隐含「列当前是 bytea」的前提。
-- 但 V9 原始定义、全新部署、以及已修复过的库，这些列本就是 text —— 在 text 列上
-- 调用 convert_from(text, ...) 会报 `function convert_from(text, unknown) does not exist`，
-- 导致全新部署 / 生产环境启动即失败（这是一个潜伏的 prod 炸弹）。
--
-- 改为：仅当列确为 bytea 时才做 USING 转换，否则整段 ALTER 跳过。
-- 这样对「曾经是 bytea 的历史库」保持原转换语义，对「已是 text 的库」安全跳过，
-- 且可以重复执行（幂等），不会破坏已 applied 的迁移记录。

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'oauth2_authorization'
          AND column_name = 'authorization_code_value'
          AND data_type = 'bytea'
    ) THEN
        ALTER TABLE oauth2_authorization
            ALTER COLUMN authorization_code_value TYPE text USING convert_from(authorization_code_value, 'UTF8'),
            ALTER COLUMN access_token_value     TYPE text USING convert_from(access_token_value, 'UTF8'),
            ALTER COLUMN oidc_id_token_value    TYPE text USING convert_from(oidc_id_token_value, 'UTF8'),
            ALTER COLUMN refresh_token_value    TYPE text USING convert_from(refresh_token_value, 'UTF8');
    END IF;
END
$$;
