CREATE TABLE IF NOT EXISTS faq (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  category VARCHAR(100) NOT NULL,
  question VARCHAR(500) NOT NULL,
  answer LONGTEXT NOT NULL,
  keywords VARCHAR(1000),
  enabled TINYINT NOT NULL DEFAULT 1,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  FULLTEXT KEY ft_faq_search (category, question, answer, keywords) WITH PARSER ngram
);

CREATE TABLE IF NOT EXISTS faq_image (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  faq_id BIGINT NOT NULL,
  image_url VARCHAR(1000) NOT NULL,
  sort_no INT NOT NULL DEFAULT 0,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_faq_image_faq_id (faq_id)
);

CREATE TABLE IF NOT EXISTS faq_attachment (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  faq_id BIGINT NOT NULL,
  file_url VARCHAR(1000) NOT NULL,
  original_name VARCHAR(500) NOT NULL,
  content_type VARCHAR(200),
  file_size BIGINT NOT NULL DEFAULT 0,
  sort_no INT NOT NULL DEFAULT 0,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_faq_attachment_faq (faq_id)
);

CREATE TABLE IF NOT EXISTS support_user (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  username VARCHAR(100) NOT NULL UNIQUE,
  email VARCHAR(200) UNIQUE,
  password_hash VARCHAR(255) NOT NULL,
  display_name VARCHAR(100),
  company_name VARCHAR(200),
  mine_name VARCHAR(200),
  phone VARCHAR(50),
  role VARCHAR(30) NOT NULL DEFAULT 'customer',
  enabled TINYINT NOT NULL DEFAULT 1,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_support_user_role (role)
);

CREATE TABLE IF NOT EXISTS support_session (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  token_hash VARCHAR(64) NOT NULL UNIQUE,
  user_id BIGINT NOT NULL,
  expires_time DATETIME NOT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_support_session_user (user_id),
  INDEX idx_support_session_expire (expires_time)
);

CREATE TABLE IF NOT EXISTS email_verification (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  email VARCHAR(200) NOT NULL,
  code_hash VARCHAR(64) NOT NULL,
  purpose VARCHAR(30) NOT NULL DEFAULT 'register',
  expires_time DATETIME NOT NULL,
  used TINYINT NOT NULL DEFAULT 0,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_email_verification_email (email, purpose, create_time)
);

CREATE TABLE IF NOT EXISTS support_ticket (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT,
  customer_name VARCHAR(200),
  mine_name VARCHAR(200),
  category VARCHAR(100),
  description TEXT NOT NULL,
  screenshot_url VARCHAR(1000),
  status VARCHAR(50) NOT NULL DEFAULT 'pending',
  resolution_reason TEXT,
  resolution_result LONGTEXT,
  resolved_time DATETIME,
  cancel_reason VARCHAR(500),
  cancelled_time DATETIME,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  deleted_time DATETIME,
  deleted_by BIGINT,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_ticket_user_id (user_id),
  INDEX idx_ticket_status (status),
  INDEX idx_ticket_deleted (is_deleted, id)
);

CREATE TABLE IF NOT EXISTS ticket_attachment (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  ticket_id BIGINT NOT NULL,
  file_url VARCHAR(1000) NOT NULL,
  original_name VARCHAR(500) NOT NULL,
  content_type VARCHAR(200),
  file_size BIGINT NOT NULL DEFAULT 0,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_ticket_attachment_ticket (ticket_id)
);

CREATE TABLE IF NOT EXISTS ticket_history (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  ticket_id BIGINT NOT NULL,
  operator_user_id BIGINT,
  operator_name VARCHAR(100),
  action_type VARCHAR(50) NOT NULL,
  content LONGTEXT,
  visible_to_customer TINYINT NOT NULL DEFAULT 1,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_ticket_history_ticket_id (ticket_id, create_time)
);

CREATE TABLE IF NOT EXISTS ticket_history_attachment (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  history_id BIGINT NOT NULL,
  ticket_id BIGINT NOT NULL,
  file_url VARCHAR(1000) NOT NULL,
  original_name VARCHAR(500) NOT NULL,
  content_type VARCHAR(200),
  file_size BIGINT NOT NULL DEFAULT 0,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_history_attachment_history (history_id),
  INDEX idx_history_attachment_ticket (ticket_id)
);

CREATE TABLE IF NOT EXISTS ticket_share (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  ticket_id BIGINT NOT NULL,
  token_hash VARCHAR(64) NOT NULL UNIQUE,
  created_by BIGINT NOT NULL,
  expires_time DATETIME NOT NULL,
  revoked TINYINT NOT NULL DEFAULT 0,
  revoked_time DATETIME,
  access_count BIGINT NOT NULL DEFAULT 0,
  last_access_time DATETIME,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_ticket_share_ticket (ticket_id, create_time),
  INDEX idx_ticket_share_expire (expires_time, revoked)
);

CREATE TABLE IF NOT EXISTS upload_staging (
  storage_name VARCHAR(255) PRIMARY KEY,
  user_id BIGINT NOT NULL,
  expires_time DATETIME NOT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_upload_staging_user (user_id),
  INDEX idx_upload_staging_expire (expires_time)
);
