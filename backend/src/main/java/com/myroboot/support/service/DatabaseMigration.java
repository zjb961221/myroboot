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
        addColumnIfMissing("support_user", "email", "VARCHAR(200) NULL");
        addColumnIfMissing("support_ticket", "user_id", "BIGINT NULL");
        addColumnIfMissing("support_ticket", "resolution_reason", "TEXT NULL");
        addColumnIfMissing("support_ticket", "resolution_result", "LONGTEXT NULL");
        addColumnIfMissing("support_ticket", "resolved_time", "DATETIME NULL");
        try { jdbcTemplate.execute("ALTER TABLE faq MODIFY answer LONGTEXT NOT NULL"); } catch (Exception ignored) {}
        try { jdbcTemplate.execute("CREATE UNIQUE INDEX uk_support_user_email ON support_user(email)"); } catch (Exception ignored) {}
        try { jdbcTemplate.execute("CREATE INDEX idx_ticket_user_id ON support_ticket(user_id)"); } catch (Exception ignored) {}
        try { jdbcTemplate.execute("CREATE FULLTEXT INDEX ft_faq_search ON faq(category,question,answer,keywords) WITH PARSER ngram"); } catch (Exception ignored) {}
        try { jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS email_verification (id BIGINT PRIMARY KEY AUTO_INCREMENT,email VARCHAR(200) NOT NULL,code_hash VARCHAR(64) NOT NULL,purpose VARCHAR(30) NOT NULL DEFAULT 'register',expires_time DATETIME NOT NULL,used TINYINT NOT NULL DEFAULT 0,create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,INDEX idx_email_verification_email (email,purpose,create_time))"); } catch (Exception ignored) {}
        try { jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS ticket_history (id BIGINT PRIMARY KEY AUTO_INCREMENT,ticket_id BIGINT NOT NULL,operator_user_id BIGINT,operator_name VARCHAR(100),action_type VARCHAR(50) NOT NULL,content LONGTEXT,visible_to_customer TINYINT NOT NULL DEFAULT 1,create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,INDEX idx_ticket_history_ticket_id (ticket_id,create_time))"); } catch (Exception ignored) {}
    }

    private void addColumnIfMissing(String table, String column, String definition) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                Integer.class, table, column
        );
        if (count != null && count == 0) jdbcTemplate.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
    }
}
