#!/usr/bin/env python3
"""Exercise the file-tenant migration on an owned disposable MySQL instance.

Accepts only loopback port 59685 with a verified data directory in .runtime/tmp.
No application database or service is opened. The real migration is tested with
mixed collations, old schemas, interrupted DDL, ambiguous paths and retries.
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
PREFIX = "file_tenant_migration_it_"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--instance", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    meta = json.loads(args.instance.read_text(encoding="utf-8-sig"))
    owned_root = Path(meta["root"]).resolve()
    data = Path(meta["data"]).resolve()
    assert meta["port"] == 59685, "Only the owned test port is permitted"
    assert owned_root.name.startswith("mes-mysql-tests-")
    assert owned_root.parent == (ROOT / ".runtime/tmp").resolve()
    assert data.parent == owned_root
    assert Path(meta["client"]).name.lower() == "mysql.exe"
    expected_data = data.as_posix().rstrip("/").lower()
    cli = [meta["client"], "--no-defaults", "--protocol=TCP", "--host=127.0.0.1",
           "--port=59685", "--user=root", "--batch", "--raw", "--skip-column-names",
           "--default-character-set=utf8mb4", "--connect-timeout=5"]
    schema = PREFIX + uuid.uuid4().hex
    assert re.fullmatch(PREFIX + r"[a-f0-9]{32}", schema)
    migration_path = ROOT / "database/migrations/20261025_file_tenant_access.sql"
    migration = migration_path.read_text(encoding="utf-8-sig")
    checks: list[str] = []
    report = dict(schema=schema, port=59685, appDatabaseTouched=False,
                  migration=str(migration_path.relative_to(ROOT)),
                  migrationSha256=hashlib.sha256(migration.encode()).hexdigest(),
                  checks=checks, success=False, schemaDropped=False)
    created = False
    connection_collation = "utf8mb4_0900_ai_ci"

    def sql(statement: str, *, db: str | None = schema,
            succeeds: bool = True) -> str:
        assert db in (None, schema)
        statement = f"SET NAMES utf8mb4 COLLATE {connection_collation};\n" + statement
        result = subprocess.run(cli + (["--database=" + db] if db else []),
                                input=statement, encoding="utf-8", text=True,
                                capture_output=True, timeout=45)
        if succeeds:
            assert result.returncode == 0, result.stderr
            return result.stdout.strip()
        assert result.returncode != 0, "Expected the original collation error"
        return result.stderr

    def check(name: str, condition: bool) -> None:
        assert condition, name
        checks.append(name)

    def files() -> str:
        return sql("SELECT id,IFNULL(tenant_id,'NULL') FROM infra_file ORDER BY id;")

    def payload() -> str:
        return sql("SELECT id,IFNULL(HEX(creator),'NULL'),config_id,HEX(path),HEX(payload) "
                   "FROM infra_file ORDER BY id;")

    try:
        identity = sql("SELECT @@port,REPLACE(@@datadir,CHAR(92),'/'),@@version;", db=None).split("\t")
        check("verified exact owned disposable MySQL instance",
              len(identity) == 3 and identity[0] == "59685"
              and identity[1].rstrip("/").lower() == expected_data)
        report["mysqlVersion"] = identity[2]
        check("fresh test schema does not exist", sql(
            f"SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='{schema}';",
            db=None) == "0")
        sql(f"CREATE DATABASE `{schema}` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;", db=None)
        created = True

        for creator_collation, connection in (
                ("utf8mb4_unicode_ci", "utf8mb4_0900_ai_ci"),
                ("utf8mb4_0900_ai_ci", "utf8mb4_unicode_ci"),
                ("utf8mb4_unicode_ci", "utf8mb4_unicode_ci")):
            connection_collation = connection
            for interrupted in (False, True):
                label = f"{creator_collation}/{connection}/interrupted={interrupted}"
                sql("DROP TABLE IF EXISTS infra_file,system_users;")
                sql(f"""
CREATE TABLE system_users(id BIGINT PRIMARY KEY,tenant_id BIGINT);
CREATE TABLE infra_file(
 id BIGINT PRIMARY KEY,creator VARCHAR(64) CHARACTER SET utf8mb4 COLLATE {creator_collation},
 config_id BIGINT NOT NULL,path VARCHAR(512) CHARACTER SET utf8mb4 COLLATE {creator_collation},
 payload VARCHAR(255) NOT NULL
);
INSERT INTO system_users VALUES(1,11),(2,22),(3,0),(4,-1),(9223372036854775807,33);
INSERT INTO infra_file(id,creator,config_id,path,payload) VALUES
(1,'1',1,'normal-1','keep 甲'),(2,'2',1,'normal-2','keep 乙'),
(3,'legacy',1,'unknown','legacy'),(4,'1-old',1,'suffix','suffix'),
(5,'01',1,'leading-zero','leading-zero'),(6,'1 ',1,'trailing-space','trailing-space'),
(7,' 1',1,'leading-space','leading-space'),(8,'１',1,'fullwidth','fullwidth'),
(9,'1.0',1,'decimal','decimal'),(10,'1',1,'verified','verified'),
(11,'3',1,'zero-tenant','zero-tenant'),(12,'4',1,'negative-tenant','negative-tenant'),
(13,NULL,1,'null-creator','null-creator'),(14,'',1,'empty-creator','empty-creator'),
(15,'9223372036854775807',1,'large-id','large-id'),
(20,'1',1,'shared-cross','cross-1'),(21,'2',1,'shared-cross','cross-2'),
(22,'1',1,'shared-unknown','known'),(23,'legacy',1,'shared-unknown','unknown'),
(24,'1',1,'duplicate-same','same-1'),(25,'1',1,'duplicate-same','same-2'),
(26,'1',2,'shared-cross','other-config'),(27,'1e0',1,'exponent','exponent'),
(28,CONCAT('1',CHAR(9)),1,'tab','tab');
""")
                if creator_collation != connection:
                    diagnostic = sql("SELECT COUNT(*) FROM infra_file f JOIN system_users u "
                                     "ON f.creator=CAST(u.id AS CHAR);", succeeds=False)
                    check(label + ": original comparison reproduces ERROR 1267", "ERROR 1267" in diagnostic)
                old_payload = payload()
                if interrupted:
                    sql("ALTER TABLE infra_file ADD COLUMN tenant_id BIGINT NULL;"
                        "UPDATE infra_file SET tenant_id=0 WHERE id IN (2,14);"
                        "UPDATE infra_file SET tenant_id=99 WHERE id=10;")
                sql(migration)
                expected = {i: "NULL" for i in (*range(1,16),*range(20,29))}
                expected.update({1:"11", 2:"22", 10:"99" if interrupted else "11",
                                 15:"33", 24:"11", 25:"11", 26:"11"})
                check(label + ": exact creators only, ambiguous files remain inaccessible",
                      files() == "\n".join(f"{i}\t{tenant}" for i,tenant in sorted(expected.items())))
                check(label + ": file metadata and bytes are preserved", payload() == old_payload)
                check(label + ": nullable tenant column created or repaired", sql(
                    "SELECT CONCAT(column_type,':',is_nullable,':',IFNULL(column_default,'NULL')) "
                    "FROM information_schema.columns WHERE table_schema=DATABASE() "
                    "AND table_name='infra_file' AND column_name='tenant_id';") == "bigint:YES:NULL")
                check(label + ": tenant/path index has the expected columns and prefix", sql(
                    "SELECT CONCAT(seq_in_index,':',column_name,':',IFNULL(sub_part,'NULL')) "
                    "FROM information_schema.statistics WHERE table_schema=DATABASE() "
                    "AND table_name='infra_file' AND index_name='idx_file_tenant_path' ORDER BY seq_in_index;")
                    == "1:tenant_id:NULL\n2:config_id:NULL\n3:path:191")
                before_retry = files()
                sql(migration)
                check(label + ": retry is idempotent", files() == before_retry and payload() == old_payload)

        report["success"] = True
    finally:
        if created:
            sql(f"DROP DATABASE `{schema}`;", db=None)
            report["schemaDropped"] = True
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(dict(report=str(args.output), passed=len(checks), failed=0)))


if __name__ == "__main__":
    main()
