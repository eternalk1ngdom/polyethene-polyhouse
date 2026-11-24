-- 追加到已有账号库；两表独立创建，DDL 部分成功后可重新执行。
CREATE TABLE IF NOT EXISTS sys_profile_cover_purchase (
    user_id BIGINT NOT NULL,
    cover_key VARCHAR(24) NOT NULL,
    spent INT NOT NULL,
    purchased_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, cover_key),
    CHECK (spent > 0),
    FOREIGN KEY (user_id) REFERENCES sys_user(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS sys_profile_cover_preference (
    user_id BIGINT PRIMARY KEY,
    cover_key VARCHAR(24) NOT NULL,
    FOREIGN KEY (user_id) REFERENCES sys_user(id) ON DELETE CASCADE
);
