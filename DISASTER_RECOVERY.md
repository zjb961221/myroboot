# MYROBOOT 持久化与容灾手册

## 目标

所有不可再生的运行数据和关键运行配置均落在宿主机文件系统，而不是只保存在容器可写层或 Docker named volume 中。容器可以删除、重建、升级；数据目录不随容器生命周期删除。

默认布局：

```text
/opt/myroboot/
├─ .env                         # 密码、SMTP、端口、数据路径（不提交 Git）
├─ docker-compose.yml
├─ deploy/mysql/my.cnf          # MySQL 运行配置
├─ frontend/nginx.conf          # Nginx 运行配置
├─ backend/src/main/resources/application.yml
├─ data/
│  ├─ mysql/                    # MySQL 数据文件 + binlog
│  ├─ uploads/                  # 客户/问题库/工单附件
│  ├─ logs/                     # Spring Boot 日志
│  └─ nginx-logs/               # Nginx access/error 日志
└─ backups/
   └─ YYYYmmdd_HHMMSS/
      ├─ mysql.sql.gz
      ├─ uploads.tar.gz
      ├─ backend-logs.tar.gz
      ├─ nginx-logs.tar.gz
      ├─ config.tar.gz
      └─ SHA256SUMS
```

生产环境如果有独立数据盘，建议在 `.env` 使用绝对路径：

```env
DATA_ROOT=/data/myroboot
BACKUP_ROOT=/backup/myroboot
```

不要把 `DATA_ROOT` 放在系统临时盘。

## 从旧 Docker named volume 无损迁移

旧版本使用 `mysql_data`、`upload_data`、`log_data` named volume。直接切换 bind mount 会看到“空数据库”，所以第一次升级必须先复制旧卷。

更新代码后执行：

```bash
cd /opt/myroboot
git pull
bash scripts/migrate-volumes-to-local.sh
docker compose up -d --build
docker compose ps
curl -fsS http://127.0.0.1:8088/api/health
```

迁移脚本会：

1. 停止容器；
2. 查找旧 named volume；
3. 直接从 Docker volume mountpoint 复制到宿主机 `data/`；
4. 保留原 volume，不自动删除，作为一次额外回退保障；
5. 拒绝向非空目标目录自动合并，避免覆盖现有数据。

确认登录、工单、附件都正常以后，再考虑手工清理旧 named volume。不要在验证前删除。

## 日常备份

执行：

```bash
cd /opt/myroboot
bash scripts/backup-local.sh
```

数据库使用 `mysqldump --single-transaction` 逻辑备份，附件/日志/配置使用压缩归档，并生成 SHA-256 校验文件。

推荐定时任务（每天 02:30）：

```cron
30 2 * * * cd /opt/myroboot && /bin/bash scripts/backup-local.sh >> /var/log/myroboot-backup.log 2>&1
```

重要：`./backups` 如果仍在同一块磁盘，只能防误删/容器损坏，不能防磁盘损坏、服务器丢失、勒索软件或机房事故。生产环境至少保持一份异机/对象存储备份。

推荐最低策略：

- 本机：保留 7 天；
- 异机/对象存储：保留 30 天；
- 每月至少做一次恢复演练；
- `.env` 含 SMTP 授权码和数据库密码，备份文件按敏感数据管理。

## 恢复

恢复脚本是破坏性操作，会重建当前应用数据库。只有确认备份路径后执行：

```bash
RESTORE_CONFIRM=YES bash scripts/restore-backup.sh /opt/myroboot/backups/20260829_023000
```

恢复脚本会校验 SHA-256、停止前后端、重建应用数据库、导入 SQL、恢复上传文件，再启动服务。

`config.tar.gz` 不会自动覆盖当前 `.env`，避免错误恢复旧密码/旧地址；需要时手工检查后再恢复。

## MySQL 容灾参数

`deploy/mysql/my.cnf` 当前启用：

- `innodb_flush_log_at_trx_commit=1`
- `sync_binlog=1`
- ROW binlog
- binlog 保留 7 天
- utf8mb4

这样优先保证事务持久性，并为后续做 point-in-time recovery（按 binlog 恢复到某一时间点）保留基础条件。

逻辑备份仍然必须做；binlog 不是完整备份的替代品。

## 哪些东西不需要“持久化”

容器镜像、前端 `dist`、Java JAR、node_modules 都是可由 Git 源码和 Dockerfile 重建的产物，不应该作为业务数据持久化。真正需要保住的是数据库、上传文件、日志、运行配置和秘密配置。

## 升级原则

正常代码升级：

```bash
cd /opt/myroboot
bash scripts/backup-local.sh
git pull
docker compose up -d --build
docker compose ps
```

不要为了升级删除 `data/`。即使容器全部重新创建，bind mount 数据仍然存在。
