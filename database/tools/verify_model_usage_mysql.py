#!/usr/bin/env python3
"""Opt-in actual model-metering migration checks on owned disposable MySQL only.

Only 127.0.0.1:59685 and the exact data directory in --instance are permitted.
The harness creates a fresh model_usage_migration_it_* schema, applies the real
upgrade, preserves byte fingerprints of old encrypted configuration and business
history, and removes only its own schema. No model/provider call is made.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import uuid

ROOT = Path(__file__).resolve().parents[2]
PREFIX = "model_usage_migration_it_"
OLD_TABLES = (
    "mes_assistant_knowledge_base", "mes_assistant_document", "mes_assistant_chunk",
    "mes_assistant_session", "mes_assistant_message", "mes_assistant_model_config",
    "mes_insight_jev_config", "mes_insight_jev_triage", "mes_insight_incident_assignment",
)
NEW_TABLES = ("mes_model_usage_policy", "mes_model_usage_bucket", "mes_model_usage_log", "mes_model_usage_event")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--instance", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    meta = json.loads(args.instance.read_text(encoding="utf-8-sig"))
    assert meta["port"] == 59685
    owned_root = Path(meta["root"]).resolve()
    assert owned_root.name.startswith("mes-mysql-tests-") and owned_root.parent.name == "tmp"
    assert Path(meta["data"]).resolve().parent == owned_root
    expected_data = str(Path(meta["data"]).resolve()).replace("\\", "/").rstrip("/").lower()
    cli = [meta["client"], "--no-defaults", "--protocol=TCP", "--host=127.0.0.1", "--port=59685",
           "--user=root", "--batch", "--raw", "--skip-column-names", "--default-character-set=utf8mb4", "--connect-timeout=5"]
    schema = PREFIX + uuid.uuid4().hex
    assert re.fullmatch(PREFIX + r"[a-f0-9]{32}", schema)
    report: dict[str, object] = {
        "schema": schema, "host": "127.0.0.1", "port": 59685, "appDatabaseTouched": False,
        "modelProviderCalled": False, "migration": "database/migrations/20261020_model_usage_budget.sql",
        "checks": [], "schemaDropped": False, "success": False,
    }
    checks: list[str] = report["checks"]  # type: ignore[assignment]
    created = False

    def sql(statement: str, *, db: str | None = schema, succeeds: bool = True) -> str:
        assert db in (None, schema)
        result = subprocess.run(cli + (["--database=" + db] if db else []), input=statement,
                                text=True, encoding="utf-8", capture_output=True, timeout=45)
        assert (result.returncode == 0) == succeeds, result.stderr or "Expected native constraint failure"
        return result.stdout.strip()

    def check(name: str, condition: bool) -> None:
        assert condition, name
        checks.append(name)

    def columns(table: str) -> list[str]:
        assert table in OLD_TABLES + NEW_TABLES
        return sql("SELECT column_name FROM information_schema.columns WHERE table_schema=DATABASE() "
                   f"AND table_name='{table}' ORDER BY ordinal_position;").splitlines()

    def fingerprint(table: str, names: list[str] | None = None) -> str:
        selected = names or columns(table)
        assert selected and all(re.fullmatch(r"[a-z_][a-z0-9_]*", c) for c in selected)
        fields = [f"IF(`{c}` IS NULL,'N',CONCAT('V',HEX(CAST(`{c}` AS BINARY))))" for c in selected]
        # Sort hashes instead of assuming every old table uses an id primary key.
        rows = sql(f"SELECT SHA2(CONCAT_WS('|',{','.join(fields)}),256) AS digest FROM `{table}` ORDER BY digest;")
        return hashlib.sha256(rows.encode()).hexdigest()

    try:
        migration_path = ROOT / str(report["migration"])
        migration = migration_path.read_text(encoding="utf-8-sig")
        report["migrationSha256"] = hashlib.sha256(migration.encode()).hexdigest()
        identity = sql("SELECT @@port,REPLACE(@@datadir,CHAR(92),'/'),@@version;", db=None).split("\t")
        check("exact owned disposable server before any write", len(identity) == 3 and identity[0] == "59685"
              and identity[1].rstrip("/").lower() == expected_data)
        report["mysqlVersion"] = identity[2]
        check("unique new migration schema is absent", sql(f"SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='{schema}';", db=None) == "0")
        sql(f"CREATE DATABASE `{schema}` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;", db=None)
        created = True
        check("owned schema starts empty", sql("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE();") == "0")
        for file in ("20261018_assistant_rag.sql", "20261020_jev_incident_triage.sql"):
            old = (ROOT / "database/migrations" / file).read_text(encoding="utf-8-sig")
            for table, ddl in re.findall(r"CREATE TABLE IF NOT EXISTS ([a-z_]+) (\([\s\S]*?\) ENGINE=InnoDB[^;]*;)", old):
                assert table in OLD_TABLES
                sql("CREATE TABLE IF NOT EXISTS " + table + " " + ddl)
        sql("ALTER TABLE mes_assistant_model_config ADD COLUMN context_tokens INT NOT NULL DEFAULT 8192;")
        seed_old(sql)
        old_columns = {table: columns(table) for table in OLD_TABLES}
        old_fingerprints = {table: fingerprint(table, names) for table, names in old_columns.items()}
        sql(migration)
        check("all four usage tables are created", all(columns(table) for table in NEW_TABLES))
        check("no synthetic policy/calls/counters/events are seeded", all(sql(f"SELECT COUNT(*) FROM `{table}`;") == "0" for table in NEW_TABLES))
        check("old ciphertext/config/messages/knowledge/Jev bytes remain unchanged",
              all(fingerprint(table, old_columns[table]) == value for table, value in old_fingerprints.items()))
        check("old sessions receive version0 and nullable pending fields", sql("SELECT COUNT(*) FROM mes_assistant_session WHERE version=0 AND pending_request_id IS NULL AND pending_started_at IS NULL;") == "2")
        check("usage tables introduce no business foreign keys", sql("SELECT COUNT(*) FROM information_schema.referential_constraints WHERE constraint_schema=DATABASE();") == "0")
        check("all usage tables use transactional InnoDB", sql("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name LIKE 'mes_model_usage_%' AND engine='InnoDB';") == "4")
        check("bucket primary key scopes tenant subject and exact admission window", sql("SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='mes_model_usage_bucket' AND index_name='PRIMARY';") == "tenant_id,subject_type,subject_id,window_type,window_start")
        check("management log indexes retain tenant and stable created_at/id pagination", sql("SELECT GROUP_CONCAT(CONCAT(index_name,':',columns_in_index) ORDER BY index_name SEPARATOR ';') FROM (SELECT index_name,GROUP_CONCAT(column_name ORDER BY seq_in_index) AS columns_in_index FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='mes_model_usage_log' AND index_name<>'PRIMARY' GROUP BY index_name) indexes_for_usage;") == "idx_model_usage_filter:tenant_id,purpose,status,created_at,id;idx_model_usage_owner:tenant_id,created_at,id;idx_model_usage_user:tenant_id,user_id,created_at,id")
        check("cumulative token counters use exact 38-digit integers", sql("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='mes_model_usage_bucket' AND column_name IN ('known_tokens','reserved_tokens','held_unknown_tokens','actual_total_tokens') AND numeric_precision=38 AND numeric_scale=0;") == "4")
        check("cumulative money counters retain 57 integer and 8 fractional digits", sql("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='mes_model_usage_bucket' AND column_name IN ('calculated_amount','reserved_amount','held_unknown_amount') AND numeric_precision=65 AND numeric_scale=8;") == "3")
        # The production schema-specific post-upgrade boundary fixtures are kept in
        # one function so a migration contract change cannot silently skip them.
        seed_usage(sql)
        check("BIGINT counters retain their full signed positive range", sql("SELECT used_calls FROM mes_model_usage_bucket WHERE subject_type='TENANT' AND window_type='DAY';") == "9223372036854775807")
        check("configured 16 plus 8 decimal digits remain exact", sql("SELECT reserved_amount FROM mes_model_usage_bucket WHERE subject_type='TENANT' AND window_type='DAY';") == "9999999999999999.12345678")
        check("cumulative and true overage money can exceed a 30-integer-digit decimal", sql("SELECT calculated_amount FROM mes_model_usage_bucket WHERE subject_type='USER' AND window_type='MONTH';") == "1014570924054025338769998985429.07594595")
        check("cumulative actual tokens exceed a single Long without overflow", sql("SELECT known_tokens FROM mes_model_usage_bucket WHERE subject_type='USER' AND window_type='MONTH';") == "18446744073709551614")
        check("unknown actual usage is JSON null and genuine measured zero is JSON integer zero", sql("SELECT CONCAT(JSON_TYPE(JSON_EXTRACT(state_json,'$.actualInputTokens')),':',JSON_UNQUOTE(JSON_EXTRACT(state_json,'$.actualInputTokens'))) FROM mes_model_usage_log ORDER BY id;") == "NULL:null\nINTEGER:0")
        after = {table: fingerprint(table) for table in OLD_TABLES + NEW_TABLES}
        sql("START TRANSACTION; UPDATE mes_model_usage_bucket SET used_calls=0,known_tokens=0,calculated_amount=0; INSERT INTO mes_model_usage_event(tenant_id,request_id,event_type,actor_id,state_json,create_time) VALUES(1,NULL,'ROLLBACK_PROOF',10,'{}','2026-10-20 00:00:00'); ROLLBACK;")
        check("counter and append-only event writes both roll back atomically", all(fingerprint(table) == after[table] for table in NEW_TABLES))
        sql(migration)
        check("second actual migration preserves new usage and every old byte", all(fingerprint(table) == value for table, value in after.items()))
        sql(migration)
        check("third actual migration keeps versions/prices/null actuals/admission windows/counters/events", all(fingerprint(table) == value for table, value in after.items()))
        # Simulate an interrupted MySQL implicit-commit ALTER sequence: version and
        # pending UUID already exist, while pending_started_at has not been added.
        sql("ALTER TABLE mes_assistant_session DROP COLUMN pending_started_at; ALTER TABLE mes_assistant_session DROP COLUMN pending_request_id; ALTER TABLE mes_assistant_session DROP COLUMN version; ALTER TABLE mes_assistant_session ADD COLUMN version INT NOT NULL DEFAULT 0; ALTER TABLE mes_assistant_session ADD COLUMN pending_request_id VARCHAR(36) NULL; UPDATE mes_assistant_session SET version=41,pending_request_id='44444444-4444-4444-4444-444444444444',update_time=update_time WHERE id=4;")
        sql(migration)
        check("partial session ALTER recovery preserves already committed version and pending UUID", sql("SELECT CONCAT(version,':',pending_request_id,':',IF(pending_started_at IS NULL,'NULL','BAD')) FROM mes_assistant_session WHERE id=4;") == "41:44444444-4444-4444-4444-444444444444:NULL")
        check("partial ALTER recovery preserves all pre-upgrade business fields", all(fingerprint(table, old_columns[table]) == value for table, value in old_fingerprints.items()))
        check("partial ALTER recovery never resets usage or policy history", all(fingerprint(table) == after[table] for table in NEW_TABLES))
        report["migrationApplications"] = 4
        report["fingerprints"] = after
        report["success"] = True
    except Exception as error:
        report["error"] = str(error)
        raise
    finally:
        if created:
            assert re.fullmatch(PREFIX + r"[a-f0-9]{32}", schema)
            sql(f"DROP DATABASE `{schema}`;", db=None)
            report["schemaDropped"] = True
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(json.dumps({"success": report["success"], "checks": len(checks), "schemaDropped": report["schemaDropped"], "report": str(args.output)}))


def seed_old(sql) -> None:
    sql("""
INSERT INTO mes_assistant_knowledge_base(id,name,description,tenant_id,creator,create_time,update_time) VALUES(1,'旧知识库','保留',1,10,'2020-01-02 03:04:05','2021-02-03 04:05:06');
INSERT INTO mes_assistant_document(id,knowledge_base_id,title,file_name,file_type,file_size,file_sha256,original_bytes,content_text,chunk_count,version,status,tenant_id,creator,create_time,update_time) VALUES(2,1,'旧文档','旧.txt','txt',3,REPEAT('a',64),0x00010AFF,'旧资料原文',1,7,'PUBLISHED',1,10,'2020-01-02 03:04:05','2021-02-03 04:05:06');
INSERT INTO mes_assistant_chunk(id,document_id,chunk_index,heading,start_offset,end_offset,content_text,content_sha256,tenant_id) VALUES(3,2,0,'旧标题',0,6,'旧资料原文',REPEAT('b',64),1);
INSERT INTO mes_assistant_session(id,title,user_id,tenant_id,create_time,update_time) VALUES(4,'旧会话一',10,1,'2020-01-02 03:04:05','2021-02-03 04:05:06'),(5,'另租户会话',20,2,'2020-01-02 03:04:05','2021-02-03 04:05:06');
INSERT INTO mes_assistant_message(id,session_id,user_id,role,content_text,status,citations_json,model_name,tenant_id,create_time) VALUES(6,4,10,'user',CONCAT('历史问题',CHAR(10),'引号 '' <>&'),'ANSWERED','[{"id":3,"version":7}]','old-model',1,'2021-02-03 04:05:06'),(7,4,10,'assistant','原始答复','ANSWERED','[{"id":3,"version":7}]','old-model',1,'2021-02-03 04:05:06');
INSERT INTO mes_assistant_model_config(tenant_id,provider,base_url,model_name,secret_ciphertext,temperature,max_tokens,enabled,updater,context_tokens,update_time) VALUES(1,'openai','https://example.invalid/v1','old-model','v1:OLD+CHAT/cipher==',0.40,1024,1,10,16384,'2021-02-03 04:05:06'),(2,'ollama','http://127.0.0.1:11434','old-local',NULL,0.20,512,0,20,8192,'2021-02-03 04:05:06');
INSERT INTO mes_insight_jev_config(tenant_id,model_name,secret_ciphertext,enabled,updater,update_time) VALUES(1,'old-jev','v1:OLD+JEV/cipher==',1,10,'2021-02-03 04:05:06');
INSERT INTO mes_insight_jev_triage(id,tenant_id,creator,input_hash,model_name,category,department_id,category_confidence,department_confidence,incident_id,final_category,final_department_id,create_time,reviewed_at) VALUES(8,1,10,REPEAT('c',64),'old-jev','QUALITY',30,0.9,0.8,99,'QUALITY',30,'2021-02-03 04:05:06','2021-02-03 04:05:07');
INSERT INTO mes_insight_incident_assignment(incident_id,tenant_id,department_id,triage_id) VALUES(99,1,30,8);
""")


def seed_usage(sql) -> None:
    """Post-upgrade exact precision/null/admission/version fixtures, no copied DDL."""
    sql("""
INSERT INTO mes_model_usage_policy(tenant_id,version,enabled,limits_json,rates_json,coverage_start,updater,update_time) VALUES(1,7,1,'{"tenantLimits":{"dayCalls":"9223372036854775807","dayAmount":"9999999999999999.12345678"}}','[{"channel":"CHAT","provider":"openai","model":"saved-model","inputPerMillion":"9999999999999999.12345678","outputPerMillion":"0.00000001"}]','2026-10-20 00:00:00',10,'2026-10-20 01:02:03');
INSERT INTO mes_model_usage_bucket(tenant_id,subject_type,subject_id,window_type,window_start,used_calls,reserved_calls,unknown_calls,known_tokens,reserved_tokens,held_unknown_tokens,unknown_chat_token_calls,unknown_jev_token_calls,actual_total_tokens,calculated_amount,reserved_amount,held_unknown_amount,unpriced_calls,active_count,admitted,version) VALUES(1,'TENANT',0,'DAY','2024-02-29 00:00:00',9223372036854775807,0,0,1,2,3,4,5,6,0.00000001,9999999999999999.12345678,7.00000001,8,9,10,11);
INSERT INTO mes_model_usage_bucket(tenant_id,subject_type,subject_id,window_type,window_start,calculated_amount,known_tokens,actual_total_tokens) VALUES(1,'USER',10,'MONTH','2024-02-01 00:00:00',1014570924054025338769998985429.07594595,18446744073709551614,18446744073709551614);
INSERT INTO mes_model_usage_log(id,tenant_id,user_id,purpose,channel,status,version,state_json,created_at,dispatched_at,settled_at,stale_after) VALUES
('11111111-1111-1111-1111-111111111111',1,10,'KNOWLEDGE_CHAT','CHAT','SETTLED',12,'{"actualInputTokens":null,"actualOutputTokens":null,"actualTotalTokens":null,"unknownHeldTokens":"8192","accountedAmount":null,"unknownHeldAmount":"9999999999999999.12345678","priceSnapshot":{"inputPerMillion":"0.00000001","policyVersion":7},"dayStart":"2024-02-29T00:00:00+08:00"}','2024-02-29 23:59:59','2024-02-29 23:59:59','2024-03-01 00:00:01','2024-03-01 00:02:00'),
('22222222-2222-2222-2222-222222222222',1,10,'MODEL_CONNECTION_TEST','CHAT','SETTLED',2,'{"actualInputTokens":0,"actualOutputTokens":0,"actualTotalTokens":0,"accountedTokens":"0","accountedAmount":"0.00000000"}','2024-03-01 00:00:01','2024-03-01 00:00:01','2024-03-01 00:00:02',NULL);
INSERT INTO mes_model_usage_event(tenant_id,request_id,event_type,actor_id,prior_version,new_version,reason,state_json,create_time) VALUES(1,'11111111-1111-1111-1111-111111111111','RECONCILE',10,11,12,'审计依据原文 '' <>&','{"before":{"actualInputTokens":null},"after":{"unknownHeldTokens":"8192"}}','2024-03-01 00:00:01');
UPDATE mes_assistant_session SET version=3,pending_request_id='33333333-3333-3333-3333-333333333333',pending_started_at='2026-10-20 01:02:03',update_time=update_time WHERE id=4;
""")


if __name__ == "__main__":
    main()
