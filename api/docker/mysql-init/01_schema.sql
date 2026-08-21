-- P0.6.1 spike schema — keyquest database (docker/mysql-init is mounted into
-- the MySQL container's docker-entrypoint-initdb.d and runs on first init).
--
-- skeleton_echo proves the authenticated DB write path end-to-end
-- (plan §20 P0.6.1). Replace with real domain tables in P1; this table is
-- intentionally throwaway.

CREATE TABLE IF NOT EXISTS skeleton_echo (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    message VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;