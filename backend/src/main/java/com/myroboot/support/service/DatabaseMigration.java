package com.myroboot.support.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class DatabaseMigration {
    private static final Logger log = LoggerFactory.getLogger(DatabaseMigration.class);

    private final JdbcTemplate jdbcTemplate;

    public DatabaseMigration(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void migrate() {
        log.info("DB_MIGRATION compatibility migration started");
        addColumnIfMissing("support_user", "email", "VARCHAR(200) NULL");
        addColumnIfMissing("support_ticket", "user_id", "BIGINT NULL");
        addColumnIfMissing("support_ticket", "resolution_reason", "TEXT NULL");
        addColumnIfMissing("support_ticket", "resolution_result", "LONGTEXT NULL");
        addColumnIfMissing("support_ticket", "resolved_time", "DATETIME NULL");
        addColumnIfMissing("support_ticket", "cancel_reason", "VARCHAR(500) NULL");
        addColumnIfMissing("support_ticket", "cancelled_time", "DATETIME NULL");
        addColumnIfMissing("support_ticket", "is_deleted", "TINYINT NOT NULL DEFAULT 0");
        addColumnIfMissing("support_ticket", "deleted_time", "DATETIME NULL");
        addColumnIfMissing("support_ticket", "deleted_by", "BIGINT NULL");

        executeOptional("faq.answer LONGTEXT", "ALTER TABLE faq MODIFY answer LONGTEXT NOT NULL");
        createIndexIfMissing("support_user", "uk_support_user_email", "CREATE UNIQUE INDEX uk_support_user_email ON support_user(email)");
        createIndexIfMissing("support_ticket", "idx_ticket_user_id", "CREATE INDEX idx_ticket_user_id ON support_ticket(user_id)");
        createIndexIfMissing("support_ticket", "idx_ticket_deleted", "CREATE INDEX idx_ticket_deleted ON support_ticket(is_deleted,id)");
        createIndexIfMissing("faq", "ft_faq_search", "CREATE FULLTEXT INDEX ft_faq_search ON faq(category,question,answer,keywords) WITH PARSER ngram");

        executeOptional("support_session table", "CREATE TABLE IF NOT EXISTS support_session (id BIGINT PRIMARY KEY AUTO_INCREMENT,token_hash VARCHAR(64) NOT NULL UNIQUE,user_id BIGINT NOT NULL,expires_time DATETIME NOT NULL,create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,INDEX idx_support_session_user(user_id),INDEX idx_support_session_expire(expires_time))");
        executeOptional("email_verification table", "CREATE TABLE IF NOT EXISTS email_verification (id BIGINT PRIMARY KEY AUTO_INCREMENT,email VARCHAR(200) NOT NULL,code_hash VARCHAR(64) NOT NULL,purpose VARCHAR(30) NOT NULL DEFAULT 'register',expires_time DATETIME NOT NULL,used TINYINT NOT NULL DEFAULT 0,create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,INDEX idx_email_verification_email (email,purpose,create_time))");
        executeOptional("ticket_attachment table", "CREATE TABLE IF NOT EXISTS ticket_attachment (id BIGINT PRIMARY KEY AUTO_INCREMENT,ticket_id BIGINT NOT NULL,file_url VARCHAR(1000) NOT NULL,original_name VARCHAR(500) NOT NULL,content_type VARCHAR(200),file_size BIGINT NOT NULL DEFAULT 0,create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,INDEX idx_ticket_attachment_ticket(ticket_id))");
        executeOptional("faq_attachment table", "CREATE TABLE IF NOT EXISTS faq_attachment (id BIGINT PRIMARY KEY AUTO_INCREMENT,faq_id BIGINT NOT NULL,file_url VARCHAR(1000) NOT NULL,original_name VARCHAR(500) NOT NULL,content_type VARCHAR(200),file_size BIGINT NOT NULL DEFAULT 0,sort_no INT NOT NULL DEFAULT 0,create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,INDEX idx_faq_attachment_faq(faq_id))");
        executeOptional("ticket_history table", "CREATE TABLE IF NOT EXISTS ticket_history (id BIGINT PRIMARY KEY AUTO_INCREMENT,ticket_id BIGINT NOT NULL,operator_user_id BIGINT,operator_name VARCHAR(100),action_type VARCHAR(50) NOT NULL,content LONGTEXT,visible_to_customer TINYINT NOT NULL DEFAULT 1,create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,INDEX idx_ticket_history_ticket_id (ticket_id,create_time))");
        executeOptional("ticket_history_attachment table", "CREATE TABLE IF NOT EXISTS ticket_history_attachment (id BIGINT PRIMARY KEY AUTO_INCREMENT,history_id BIGINT NOT NULL,ticket_id BIGINT NOT NULL,file_url VARCHAR(1000) NOT NULL,original_name VARCHAR(500) NOT NULL,content_type VARCHAR(200),file_size BIGINT NOT NULL DEFAULT 0,create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,INDEX idx_history_attachment_history(history_id),INDEX idx_history_attachment_ticket(ticket_id))");
        executeOptional("ticket_share table", "CREATE TABLE IF NOT EXISTS ticket_share (id BIGINT PRIMARY KEY AUTO_INCREMENT,ticket_id BIGINT NOT NULL,token_hash VARCHAR(64) NOT NULL UNIQUE,created_by BIGINT NOT NULL,expires_time DATETIME NOT NULL,revoked TINYINT NOT NULL DEFAULT 0,revoked_time DATETIME,access_count BIGINT NOT NULL DEFAULT 0,last_access_time DATETIME,create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,INDEX idx_ticket_share_ticket(ticket_id,create_time),INDEX idx_ticket_share_expire(expires_time,revoked))");
        executeOptional("faq_share table", "CREATE TABLE IF NOT EXISTS faq_share (id BIGINT PRIMARY KEY AUTO_INCREMENT,faq_id BIGINT NOT NULL,token_hash VARCHAR(64) NOT NULL UNIQUE,created_by BIGINT NOT NULL,expires_time DATETIME NOT NULL,revoked TINYINT NOT NULL DEFAULT 0,revoked_time DATETIME,access_count BIGINT NOT NULL DEFAULT 0,last_access_time DATETIME,create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,INDEX idx_faq_share_faq(faq_id,create_time),INDEX idx_faq_share_expire(expires_time,revoked))");
        executeOptional("upload_staging table", "CREATE TABLE IF NOT EXISTS upload_staging (storage_name VARCHAR(255) PRIMARY KEY,user_id BIGINT NOT NULL,expires_time DATETIME NOT NULL,create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,INDEX idx_upload_staging_user(user_id),INDEX idx_upload_staging_expire(expires_time))");
        log.info("DB_MIGRATION compatibility migration finished");
    }

    private void addColumnIfMissing(String table, String column, String definition) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                    Integer.class, table, column
            );
            if (count != null && count == 0) {
                jdbcTemplate.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
                log.info("DB_MIGRATION added column {}.{}", table, column);
            }
        } catch (Exception e) {
            log.warn("DB_MIGRATION failed adding column {}.{}: {}", table, column, rootMessage(e));
        }
    }

    private void createIndexIfMissing(String table, String index, String sql) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME=? AND INDEX_NAME=?",
                    Integer.class, table, index);
            if (count != null && count == 0) {
                jdbcTemplate.execute(sql);
                log.info("DB_MIGRATION created index {}.{}", table, index);
            }
        } catch (Exception e) {
            log.warn("DB_MIGRATION failed creating index {}.{}: {}", table, index, rootMessage(e));
        }
    }

    private void executeOptional(String name, String sql) {
        try {
            jdbcTemplate.execute(sql);
            log.debug("DB_MIGRATION checked {}", name);
        } catch (Exception e) {
            log.warn("DB_MIGRATION failed {}: {}", name, rootMessage(e));
        }
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
