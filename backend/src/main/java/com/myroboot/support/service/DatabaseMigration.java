package com.myroboot.support.service;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class DatabaseMigration {
    private final JdbcTemplate jdbcTemplate;

    public DatabaseMigration(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void migrate() {
        addColumnIfMissing("support_ticket", "user_id", "BIGINT NULL");
        addColumnIfMissing("support_ticket", "resolution_reason", "TEXT NULL");
        addColumnIfMissing("support_ticket", "resolution_result", "LONGTEXT NULL");
        addColumnIfMissing("support_ticket", "resolved_time", "DATETIME NULL");
        try {
            jdbcTemplate.execute("ALTER TABLE faq MODIFY answer LONGTEXT NOT NULL");
        } catch (Exception ignored) {
        }
        try {
            jdbcTemplate.execute("CREATE INDEX idx_ticket_user_id ON support_ticket(user_id)");
        } catch (Exception ignored) {
        }
    }

    private void addColumnIfMissing(String table, String column, String definition) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                Integer.class, table, column
        );
        if (count != null && count == 0) {
            jdbcTemplate.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        }
    }
}
