package com.myroboot.support.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class RemoteDatabaseMigration {
    private static final Logger log = LoggerFactory.getLogger(RemoteDatabaseMigration.class);
    private final JdbcTemplate jdbcTemplate;

    public RemoteDatabaseMigration(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void migrate() {
        create("remote_agent", """
                CREATE TABLE IF NOT EXISTS remote_agent (
                  id BIGINT PRIMARY KEY AUTO_INCREMENT,
                  agent_id VARCHAR(64) NOT NULL UNIQUE,
                  token_hash VARCHAR(64) NOT NULL,
                  name VARCHAR(200) NOT NULL,
                  mine_name VARCHAR(200),
                  hostname VARCHAR(200),
                  os_name VARCHAR(200),
                  agent_version VARCHAR(50),
                  private_ip VARCHAR(100),
                  desktop_session VARCHAR(100),
                  enabled TINYINT NOT NULL DEFAULT 1,
                  last_seen DATETIME,
                  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                  INDEX idx_remote_agent_seen (enabled,last_seen),
                  INDEX idx_remote_agent_mine (mine_name)
                )
                """);
        create("remote_session", """
                CREATE TABLE IF NOT EXISTS remote_session (
                  id BIGINT PRIMARY KEY AUTO_INCREMENT,
                  session_id VARCHAR(64) NOT NULL UNIQUE,
                  agent_id BIGINT NOT NULL,
                  user_id BIGINT NOT NULL,
                  session_type VARCHAR(30) NOT NULL,
                  status VARCHAR(20) NOT NULL DEFAULT 'opening',
                  client_ip VARCHAR(100),
                  start_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  end_time DATETIME,
                  INDEX idx_remote_session_agent (agent_id,start_time),
                  INDEX idx_remote_session_user (user_id,start_time)
                )
                """);
        create("remote_audit_log", """
                CREATE TABLE IF NOT EXISTS remote_audit_log (
                  id BIGINT PRIMARY KEY AUTO_INCREMENT,
                  agent_id BIGINT,
                  user_id BIGINT,
                  action_type VARCHAR(50) NOT NULL,
                  detail VARCHAR(1000),
                  client_ip VARCHAR(100),
                  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  INDEX idx_remote_audit_agent (agent_id,create_time),
                  INDEX idx_remote_audit_user (user_id,create_time)
                )
                """);
    }

    private void create(String name, String sql) {
        try {
            jdbcTemplate.execute(sql);
            log.info("REMOTE_DB_MIGRATION checked table {}", name);
        } catch (Exception e) {
            log.error("REMOTE_DB_MIGRATION failed table {}", name, e);
            throw e;
        }
    }
}
