package com.myroboot.support.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class MaintenanceNoticeMigration {
    private static final Logger log = LoggerFactory.getLogger(MaintenanceNoticeMigration.class);
    private final JdbcTemplate jdbc;
    public MaintenanceNoticeMigration(JdbcTemplate jdbc){this.jdbc=jdbc;}

    @PostConstruct
    public void migrate(){
        // Additive-only migration. Never deletes or rewrites existing business data.
        optional("maintenance_notice", "CREATE TABLE IF NOT EXISTS maintenance_notice (id BIGINT PRIMARY KEY AUTO_INCREMENT,title VARCHAR(200) NOT NULL,notice_type VARCHAR(30) NOT NULL DEFAULT 'maintenance',start_time DATETIME NULL,end_time DATETIME NULL,impact_scope VARCHAR(500) NULL,content TEXT NOT NULL,status VARCHAR(30) NOT NULL DEFAULT 'draft',created_by BIGINT NOT NULL,total_count INT NOT NULL DEFAULT 0,sent_count INT NOT NULL DEFAULT 0,failed_count INT NOT NULL DEFAULT 0,send_started_time DATETIME NULL,send_finished_time DATETIME NULL,create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,INDEX idx_maintenance_notice_status(status,create_time))");
        optional("maintenance_notice_recipient", "CREATE TABLE IF NOT EXISTS maintenance_notice_recipient (id BIGINT PRIMARY KEY AUTO_INCREMENT,notice_id BIGINT NOT NULL,user_id BIGINT NOT NULL,recipient_email VARCHAR(200) NOT NULL,status VARCHAR(20) NOT NULL DEFAULT 'pending',attempt_count INT NOT NULL DEFAULT 0,last_error VARCHAR(1000) NULL,sent_time DATETIME NULL,create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,UNIQUE KEY uk_maintenance_recipient(notice_id,user_id),INDEX idx_maintenance_recipient_status(notice_id,status))");
    }
    private void optional(String name,String sql){try{jdbc.execute(sql);log.info("DB_MIGRATION checked {}",name);}catch(Exception e){log.warn("DB_MIGRATION failed {}: {}",name,root(e));}}
    private String root(Throwable e){while(e.getCause()!=null)e=e.getCause();return e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();}
}
