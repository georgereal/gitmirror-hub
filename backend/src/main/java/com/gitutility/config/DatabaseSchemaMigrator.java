package com.gitutility.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;

/**
 * Ensures existing persistent H2 database tables have all newly added columns
 * across application updates without requiring manual database drops or resets.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DatabaseSchemaMigrator {

    private final DataSource dataSource;

    @PostConstruct
    public void migrateSchema() {
        log.info("Running automatic database schema evolution checks...");
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {

            // 1. Ensure storage_tier column exists on repo_mappings
            try {
                stmt.execute("ALTER TABLE repo_mappings ADD COLUMN IF NOT EXISTS storage_tier VARCHAR(50) DEFAULT 'AUTO_LRU'");
                log.info("Database migration: verified repo_mappings.storage_tier column.");
            } catch (Exception e) {
                log.debug("Schema migration notice (repo_mappings.storage_tier): {}", e.getMessage());
            }

            // 2. Ensure scm_provider_configs columns exist
            String[] scmColumns = {
                    "ALTER TABLE scm_provider_configs ADD COLUMN IF NOT EXISTS default_pat_token VARCHAR(2000)",
                    "ALTER TABLE scm_provider_configs ADD COLUMN IF NOT EXISTS ghes_host_url VARCHAR(255)",
                    "ALTER TABLE scm_provider_configs ADD COLUMN IF NOT EXISTS ghes_auth_type VARCHAR(50) DEFAULT 'PERSONAL_ACCESS_TOKEN'",
                    "ALTER TABLE scm_provider_configs ADD COLUMN IF NOT EXISTS ghes_pat_token VARCHAR(2000)",
                    "ALTER TABLE scm_provider_configs ADD COLUMN IF NOT EXISTS ghes_app_id VARCHAR(255)",
                    "ALTER TABLE scm_provider_configs ADD COLUMN IF NOT EXISTS ghes_client_id VARCHAR(255)",
                    "ALTER TABLE scm_provider_configs ADD COLUMN IF NOT EXISTS ghes_client_secret VARCHAR(2000)",
                    "ALTER TABLE scm_provider_configs ADD COLUMN IF NOT EXISTS ghes_private_key_pem VARCHAR(10000)",
                    "ALTER TABLE scm_provider_configs ADD COLUMN IF NOT EXISTS ghes_installation_id VARCHAR(255)",
                    "ALTER TABLE scm_provider_configs ADD COLUMN IF NOT EXISTS ghes_webhook_secret VARCHAR(2000)",
                    "ALTER TABLE scm_provider_configs ADD COLUMN IF NOT EXISTS gitlab_host_url VARCHAR(255) DEFAULT 'https://gitlab.com'",
                    "ALTER TABLE scm_provider_configs ADD COLUMN IF NOT EXISTS gitlab_access_token VARCHAR(2000)",
                    "ALTER TABLE scm_provider_configs ADD COLUMN IF NOT EXISTS gitlab_webhook_secret VARCHAR(2000)",
                    "ALTER TABLE scm_provider_configs ADD COLUMN IF NOT EXISTS bitbucket_workspace VARCHAR(255)",
                    "ALTER TABLE scm_provider_configs ADD COLUMN IF NOT EXISTS bitbucket_auth_type VARCHAR(50) DEFAULT 'OAUTH2'",
                    "ALTER TABLE scm_provider_configs ADD COLUMN IF NOT EXISTS bitbucket_username VARCHAR(255) DEFAULT 'x-token-auth'",
                    "ALTER TABLE scm_provider_configs ADD COLUMN IF NOT EXISTS bitbucket_access_token VARCHAR(2000)",
                    "ALTER TABLE scm_provider_configs ADD COLUMN IF NOT EXISTS bitbucket_webhook_secret VARCHAR(2000)",
                    "ALTER TABLE scm_provider_configs ADD COLUMN IF NOT EXISTS origin_host_url VARCHAR(255) DEFAULT 'https://origin.cursor.com'",
                    "ALTER TABLE scm_provider_configs ADD COLUMN IF NOT EXISTS origin_access_token VARCHAR(2000)",
                    "ALTER TABLE scm_provider_configs ADD COLUMN IF NOT EXISTS azure_devops_org_url VARCHAR(255)",
                    "ALTER TABLE scm_provider_configs ADD COLUMN IF NOT EXISTS azure_devops_pat_token VARCHAR(2000)"
            };

            for (String sql : scmColumns) {
                try {
                    stmt.execute(sql);
                } catch (Exception e) {
                    log.debug("Schema migration notice (scm_provider_configs): {}", e.getMessage());
                }
            }

            // 3. Ensure system_engine_configs enterprise logging columns exist
            String[] systemEngineColumns = {
                    "ALTER TABLE system_engine_configs ADD COLUMN IF NOT EXISTS logging_sink VARCHAR(50) DEFAULT 'CONSOLE'",
                    "ALTER TABLE system_engine_configs ADD COLUMN IF NOT EXISTS logging_level VARCHAR(50) DEFAULT 'INFO'",
                    "ALTER TABLE system_engine_configs ADD COLUMN IF NOT EXISTS splunk_hec_url VARCHAR(255)",
                    "ALTER TABLE system_engine_configs ADD COLUMN IF NOT EXISTS splunk_hec_token VARCHAR(255)",
                    "ALTER TABLE system_engine_configs ADD COLUMN IF NOT EXISTS splunk_index VARCHAR(255) DEFAULT 'main'",
                    "ALTER TABLE system_engine_configs ADD COLUMN IF NOT EXISTS splunk_source_type VARCHAR(255) DEFAULT '_json'",
                    "ALTER TABLE system_engine_configs ADD COLUMN IF NOT EXISTS logstash_host VARCHAR(255)",
                    "ALTER TABLE system_engine_configs ADD COLUMN IF NOT EXISTS syslog_host VARCHAR(255)",
                    "ALTER TABLE system_engine_configs ADD COLUMN IF NOT EXISTS rolling_file_path VARCHAR(255) DEFAULT '/tmp/git-utility-mirrors/logs/git-utility.log'",
                    "ALTER TABLE system_engine_configs ADD COLUMN IF NOT EXISTS json_structured_enabled BOOLEAN DEFAULT TRUE"
            };

            for (String sql : systemEngineColumns) {
                try {
                    stmt.execute(sql);
                } catch (Exception e) {
                    log.debug("Schema migration notice (system_engine_configs): {}", e.getMessage());
                }
            }

            // 3. Ensure repo_mappings sourcePublicRead cache and resumable-push ledger
            try {
                stmt.execute("ALTER TABLE repo_mappings ADD COLUMN IF NOT EXISTS source_public_read BOOLEAN");
                log.info("Database migration: verified repo_mappings.source_public_read column.");
            } catch (Exception e) {
                log.debug("Schema migration notice (repo_mappings.source_public_read): {}", e.getMessage());
            }
            try {
                stmt.execute("ALTER TABLE repo_mappings ADD COLUMN IF NOT EXISTS completed_push_refs CLOB");
                log.info("Database migration: verified repo_mappings.completed_push_refs column.");
            } catch (Exception e) {
                log.debug("Schema migration notice (repo_mappings.completed_push_refs): {}", e.getMessage());
            }
            String[] checkpointColumns = {
                    "ALTER TABLE repo_mappings ADD COLUMN IF NOT EXISTS sync_checkpoint_stage VARCHAR(40)",
                    "ALTER TABLE repo_mappings ADD COLUMN IF NOT EXISTS completed_lfs_oids CLOB",
                    "ALTER TABLE repo_mappings ADD COLUMN IF NOT EXISTS discovered_lfs_oids CLOB",
                    "ALTER TABLE repo_mappings ADD COLUMN IF NOT EXISTS last_mirror_job_id BIGINT",
                    "ALTER TABLE repo_mappings ADD COLUMN IF NOT EXISTS last_mirror_stats_at TIMESTAMP",
                    "ALTER TABLE repo_mappings ADD COLUMN IF NOT EXISTS last_mirror_branches_count INTEGER",
                    "ALTER TABLE repo_mappings ADD COLUMN IF NOT EXISTS last_mirror_tags_count INTEGER",
                    "ALTER TABLE repo_mappings ADD COLUMN IF NOT EXISTS last_mirror_lfs_objects INTEGER",
                    "ALTER TABLE repo_mappings ADD COLUMN IF NOT EXISTS last_mirror_bytes_transferred BIGINT",
                    "ALTER TABLE repo_mappings ADD COLUMN IF NOT EXISTS diff_snapshot_json CLOB",
                    "ALTER TABLE repo_mappings ADD COLUMN IF NOT EXISTS diff_snapshot_at TIMESTAMP"
            };
            for (String sql : checkpointColumns) {
                try {
                    stmt.execute(sql);
                } catch (Exception e) {
                    log.debug("Schema migration notice (repo_mappings checkpoint): {}", e.getMessage());
                }
            }
            String[] mappingVisibilityColumns = {
                    "ALTER TABLE repo_mappings ADD COLUMN IF NOT EXISTS source_provider VARCHAR(50)",
                    "ALTER TABLE repo_mappings ADD COLUMN IF NOT EXISTS target_provider VARCHAR(50)",
                    "ALTER TABLE repo_mappings ADD COLUMN IF NOT EXISTS source_visibility VARCHAR(20) DEFAULT 'UNKNOWN'",
                    "ALTER TABLE repo_mappings ADD COLUMN IF NOT EXISTS target_visibility VARCHAR(20) DEFAULT 'UNKNOWN'"
            };
            for (String sql : mappingVisibilityColumns) {
                try {
                    stmt.execute(sql);
                } catch (Exception e) {
                    log.debug("Schema migration notice (repo_mappings visibility): {}", e.getMessage());
                }
            }

            // 4. Ensure sync_jobs volume metrics columns exist
            String[] syncJobColumns = {
                    "ALTER TABLE sync_jobs ADD COLUMN IF NOT EXISTS bytes_transferred BIGINT",
                    "ALTER TABLE sync_jobs ADD COLUMN IF NOT EXISTS objects_received INTEGER",
                    "ALTER TABLE sync_jobs ADD COLUMN IF NOT EXISTS lfs_bytes BIGINT",
                    "ALTER TABLE sync_jobs ADD COLUMN IF NOT EXISTS source_access_mode VARCHAR(50)",
                    "ALTER TABLE sync_jobs ADD COLUMN IF NOT EXISTS rejected_push_refs VARCHAR(4000)",
                    "ALTER TABLE sync_jobs ADD COLUMN IF NOT EXISTS pipeline_json VARCHAR(8000)",
                    "ALTER TABLE sync_jobs ADD COLUMN IF NOT EXISTS rest_call_count INTEGER",
                    "ALTER TABLE sync_jobs ADD COLUMN IF NOT EXISTS rest_calls_per_minute DOUBLE",
                    "ALTER TABLE sync_jobs ADD COLUMN IF NOT EXISTS git_http_fetch_count INTEGER",
                    "ALTER TABLE sync_jobs ADD COLUMN IF NOT EXISTS git_http_push_batch_count INTEGER",
                    "ALTER TABLE sync_jobs ADD COLUMN IF NOT EXISTS rate_limit_remaining INTEGER",
                    "ALTER TABLE sync_jobs ADD COLUMN IF NOT EXISTS rate_limit_limit INTEGER",
                    "ALTER TABLE sync_jobs ADD COLUMN IF NOT EXISTS rate_limit_429_count INTEGER",
                    "ALTER TABLE sync_jobs ADD COLUMN IF NOT EXISTS provider_traffic_provider VARCHAR(50)",
                    "ALTER TABLE sync_jobs ADD COLUMN IF NOT EXISTS git_push_per_minute DOUBLE",
                    "ALTER TABLE sync_jobs ADD COLUMN IF NOT EXISTS git_fetch_per_minute DOUBLE",
                    "ALTER TABLE sync_jobs ADD COLUMN IF NOT EXISTS git_http_throttle_count INTEGER",
                    "ALTER TABLE sync_jobs ADD COLUMN IF NOT EXISTS resume_stage_id VARCHAR(64)",
                    "ALTER TABLE sync_jobs ADD COLUMN IF NOT EXISTS stage_progress_json CLOB",
                    "ALTER TABLE sync_jobs ADD COLUMN IF NOT EXISTS cancel_requested BOOLEAN DEFAULT FALSE",
                    "ALTER TABLE sync_jobs ADD COLUMN IF NOT EXISTS pause_requested BOOLEAN DEFAULT FALSE",
                    "ALTER TABLE sync_jobs ADD COLUMN IF NOT EXISTS worker_instance_id VARCHAR(255)"
            };
            for (String sql : syncJobColumns) {
                try {
                    stmt.execute(sql);
                } catch (Exception e) {
                    log.debug("Schema migration notice (sync_jobs): {}", e.getMessage());
                }
            }

            // H2 persists @Enumerated STRING as a native ENUM. New Java values (CANCELLED,
            // CONFLICT_ISOLATED) fail writes until the column is widened to VARCHAR.
            widenH2EnumToVarchar(stmt, "SYNC_JOBS", "STATUS");
            widenH2EnumToVarchar(stmt, "SYNC_JOBS", "TRIGGER_TYPE");
            widenH2EnumToVarchar(stmt, "REPO_MAPPINGS", "LAST_SYNC_STATUS");
            widenH2EnumToVarchar(stmt, "REPO_MAPPINGS", "SYNC_DIRECTION");
            widenH2EnumToVarchar(stmt, "REPO_MAPPINGS", "STORAGE_TIER");

            // Hibernate ddl-auto=update does not widen existing VARCHAR columns. Squash-merge
            // commit messages exceed VARCHAR(1000) and abort webhook ingestion.
            widenVarcharToClob(stmt, "SYNC_JOBS", "COMMIT_MESSAGE");
            widenVarcharToClob(stmt, "UNMAPPED_WEBHOOK_EVENTS", "COMMIT_MESSAGE");

            try {
                stmt.execute("ALTER TABLE pr_mappings ADD COLUMN IF NOT EXISTS fork_pr_head BOOLEAN DEFAULT FALSE");
                log.info("Database migration: verified pr_mappings.fork_pr_head column.");
            } catch (Exception e) {
                log.debug("Schema migration notice (pr_mappings.fork_pr_head): {}", e.getMessage());
            }
            try {
                stmt.execute("ALTER TABLE pr_mappings ADD COLUMN IF NOT EXISTS origin_side VARCHAR(8)");
                log.info("Database migration: verified pr_mappings.origin_side column.");
            } catch (Exception e) {
                log.debug("Schema migration notice (pr_mappings.origin_side): {}", e.getMessage());
            }
            try {
                stmt.execute("ALTER TABLE pr_mappings ADD COLUMN IF NOT EXISTS last_pushed_title VARCHAR(1000)");
                stmt.execute("ALTER TABLE pr_mappings ADD COLUMN IF NOT EXISTS last_pushed_body CLOB");
                stmt.execute("ALTER TABLE pr_mappings ADD COLUMN IF NOT EXISTS last_pushed_at TIMESTAMP");
                log.info("Database migration: verified pr_mappings CAS columns.");
            } catch (Exception e) {
                log.debug("Schema migration notice (pr_mappings CAS): {}", e.getMessage());
            }
            try {
                // Fork PR DR stubs cache objects without a dest GitHub PR yet.
                stmt.execute("ALTER TABLE pr_mappings ALTER COLUMN target_pr_number SET NULL");
                log.info("Database migration: pr_mappings.target_pr_number is nullable for fork object cache.");
            } catch (Exception e) {
                log.debug("Schema migration notice (pr_mappings.target_pr_number nullable): {}", e.getMessage());
            }
            try {
                stmt.execute("ALTER TABLE repo_mappings ADD COLUMN IF NOT EXISTS trunk_conflict_policy VARCHAR(20) DEFAULT 'ISOLATE'");
                log.info("Database migration: verified repo_mappings.trunk_conflict_policy column.");
            } catch (Exception e) {
                log.debug("Schema migration notice (repo_mappings.trunk_conflict_policy): {}", e.getMessage());
            }
            widenH2EnumToVarchar(stmt, "REPO_MAPPINGS", "TRUNK_CONFLICT_POLICY");
            try {
                stmt.execute("""
                        CREATE TABLE IF NOT EXISTS scm_credentials (
                            id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                            label VARCHAR(120) NOT NULL,
                            provider VARCHAR(40) NOT NULL,
                            host_url VARCHAR(512) NOT NULL,
                            auth_mode VARCHAR(40) NOT NULL,
                            app_id VARCHAR(255),
                            client_id VARCHAR(255),
                            client_secret VARCHAR(2000),
                            private_key_pem VARCHAR(10000),
                            installation_id VARCHAR(255),
                            account_login VARCHAR(255),
                            account_type VARCHAR(40),
                            repository_selection VARCHAR(40),
                            app_slug VARCHAR(255),
                            bot_login VARCHAR(255),
                            webhook_secret VARCHAR(2000),
                            pat_token VARCHAR(2000),
                            enabled BOOLEAN DEFAULT TRUE NOT NULL,
                            updated_at TIMESTAMP
                        )
                        """);
                log.info("Database migration: verified scm_credentials table.");
            } catch (Exception e) {
                log.debug("Schema migration notice (scm_credentials): {}", e.getMessage());
            }
            try {
                stmt.execute("ALTER TABLE repo_mappings ADD COLUMN IF NOT EXISTS source_credential_id BIGINT");
                stmt.execute("ALTER TABLE repo_mappings ADD COLUMN IF NOT EXISTS target_credential_id BIGINT");
                log.info("Database migration: verified repo_mappings credential columns.");
            } catch (Exception e) {
                log.debug("Schema migration notice (repo_mappings credentials): {}", e.getMessage());
            }
            try {
                stmt.execute("""
                        CREATE TABLE IF NOT EXISTS sync_conflicts (
                            id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                            mapping_id BIGINT NOT NULL,
                            job_id BIGINT,
                            kind VARCHAR(20) NOT NULL,
                            status VARCHAR(20) NOT NULL,
                            policy_applied VARCHAR(20),
                            ref_name VARCHAR(512),
                            source_sha VARCHAR(64),
                            dest_sha VARCHAR(64),
                            isolated_branch VARCHAR(512),
                            conflict_pr_number BIGINT,
                            dest_repo VARCHAR(512),
                            origin_title VARCHAR(1000),
                            replica_title VARCHAR(1000),
                            message CLOB,
                            created_at TIMESTAMP,
                            resolved_at TIMESTAMP
                        )
                        """);
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_sync_conflicts_mapping_status ON sync_conflicts (mapping_id, status)");
                log.info("Database migration: verified sync_conflicts table.");
            } catch (Exception e) {
                log.debug("Schema migration notice (sync_conflicts): {}", e.getMessage());
            }
            try {
                stmt.execute("""
                        CREATE TABLE IF NOT EXISTS ref_origins (
                            id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                            mapping_id BIGINT NOT NULL,
                            ref_name VARCHAR(512) NOT NULL,
                            origin_side VARCHAR(8) NOT NULL,
                            created_at TIMESTAMP,
                            CONSTRAINT uk_ref_origins_mapping_ref UNIQUE (mapping_id, ref_name)
                        )
                        """);
                log.info("Database migration: verified ref_origins table.");
            } catch (Exception e) {
                log.debug("Schema migration notice (ref_origins): {}", e.getMessage());
            }

            log.info("Database schema evolution checks completed successfully.");
        } catch (Exception ex) {
            log.warn("Database schema auto-migrator error: {}", ex.getMessage());
        }
    }

    /**
     * Converts a bounded VARCHAR column to CLOB so long Git commit messages persist.
     * No-ops when the column is already CLOB/TEXT.
     */
    static void widenVarcharToClob(Statement stmt, String table, String column) {
        String[] attempts = {
                "ALTER TABLE " + table + " ALTER COLUMN " + column + " SET DATA TYPE CLOB",
                "ALTER TABLE " + table + " ALTER COLUMN " + column + " CLOB"
        };
        for (String sql : attempts) {
            try {
                stmt.execute(sql);
                log.info("Database migration: widened {}.{} to CLOB.", table, column);
                return;
            } catch (Exception e) {
                log.debug("Schema migration notice ({}.{}): {}", table, column, e.getMessage());
            }
        }
    }

    /**
     * Converts an H2 ENUM column to VARCHAR so Java enum additions do not require a matching
     * database enum rebuild. No-ops when the column is already character data.
     */
    static void widenH2EnumToVarchar(Statement stmt, String table, String column) {
        String[] attempts = {
                "ALTER TABLE " + table + " ALTER COLUMN " + column + " SET DATA TYPE VARCHAR(50)",
                "ALTER TABLE " + table + " ALTER COLUMN " + column + " VARCHAR(50)"
        };
        for (String sql : attempts) {
            try {
                stmt.execute(sql);
                log.info("Database migration: widened {}.{} to VARCHAR(50).", table, column);
                return;
            } catch (Exception e) {
                log.debug("Schema migration notice ({}.{}): {}", table, column, e.getMessage());
            }
        }
    }
}
