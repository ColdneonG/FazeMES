#!/usr/bin/env python3
"""Verify the barcode-label upgrade on an owned disposable MySQL instance only.

Creates one fresh barcode_migration_it_* schema on 127.0.0.1:59685. The actual
checked-in migration is applied repeatedly, including recovery after CREATE
TABLE committed before the global mutex seed. Existing barcode, configuration
and master rows (including invalid historical data) must remain byte-identical.
No MES application is started, and no application schema is selected or altered.
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
PREFIX = "barcode_migration_it_"
OLD_TABLES = ("mes_wm_barcode", "mes_wm_barcode_config", "mes_md_item")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--instance", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    meta = json.loads(args.instance.read_text(encoding="utf-8-sig"))
    assert meta["port"] == 59685, "Only the owned test port is allowed"
    expected_data = str(Path(meta["data"]).resolve()).replace("\\", "/").rstrip("/").lower()
    owned_root = Path(meta["root"]).resolve()
    assert owned_root.name.startswith("mes-mysql-tests-"), "Expected an explicitly owned test directory"
    assert Path(meta["data"]).resolve().parent == owned_root
    assert owned_root.parent.name == "tmp"
    assert Path(meta["client"]).name.lower() == "mysql.exe"
    cli = [meta["client"], "--no-defaults", "--protocol=TCP", "--host=127.0.0.1",
           "--port=59685", "--user=root", "--batch", "--raw", "--skip-column-names",
           "--default-character-set=utf8mb4", "--connect-timeout=5"]
    schema = PREFIX + uuid.uuid4().hex
    assert re.fullmatch(PREFIX + r"[a-f0-9]{32}", schema)
    report: dict[str, object] = {
        "schema": schema, "host": "127.0.0.1", "port": 59685,
        "appDatabaseTouched": False, "applicationStarted": False,
        "migration": "database/migrations/20261020_barcode_label.sql",
        "checks": [], "schemaDropped": False, "success": False,
    }
    checks: list[str] = report["checks"]  # type: ignore[assignment]
    created = False

    def sql(statement: str, *, db: str | None = schema, succeeds: bool = True) -> str:
        assert db in (None, schema)
        result = subprocess.run(cli + (["--database=" + db] if db else []),
                                input=statement, text=True, encoding="utf-8",
                                capture_output=True, timeout=45)
        if succeeds:
            assert result.returncode == 0, result.stderr
        else:
            assert result.returncode != 0, "Expected a real MySQL rejection"
        return result.stdout.strip()

    def check(name: str, condition: bool) -> None:
        assert condition, name
        checks.append(name)

    def snapshot(tables: tuple[str, ...] = OLD_TABLES) -> dict[str, str]:
        result = {}
        for table in tables:
            assert re.fullmatch(r"[a-z_]+", table)
            columns = sql("SELECT column_name FROM information_schema.columns "
                          f"WHERE table_schema=DATABASE() AND table_name='{table}' ORDER BY ordinal_position;").splitlines()
            assert columns and all(re.fullmatch(r"[a-z_]+", c) for c in columns)
            parts = [f"IF(`{c}` IS NULL,'N',CONCAT('V',HEX(CAST(`{c}` AS BINARY))))" for c in columns]
            row_hashes = sql(f"SELECT id,SHA2(CONCAT_WS('|',{','.join(parts)}),256) FROM `{table}` ORDER BY id;")
            result[table] = hashlib.sha256(row_hashes.encode("utf-8")).hexdigest()
        return result

    def indexes() -> str:
        return sql("SELECT table_name,index_name,non_unique,seq_in_index,column_name,IFNULL(sub_part,'NULL') "
                   "FROM information_schema.statistics WHERE table_schema=DATABASE() "
                   "AND table_name IN ('mes_wm_barcode','mes_wm_barcode_config','mes_md_item') "
                   "ORDER BY table_name,index_name,seq_in_index;")

    try:
        identity = sql("SELECT @@port,REPLACE(@@datadir,CHAR(92),'/'),@@version;", db=None).split("\t")
        check("server is the exact owned disposable MySQL data directory",
              len(identity) == 3 and identity[0] == "59685"
              and identity[1].rstrip("/").lower() == expected_data)
        report["mysqlVersion"] = identity[2]
        check("fresh schema name does not already exist", sql(
            f"SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='{schema}';", db=None) == "0")
        sql(f"CREATE DATABASE `{schema}` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;", db=None)
        created = True
        check("new schema is explicitly empty", sql("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE();") == "0")
        init = (ROOT / "database/init/mes_schema.sql").read_text(encoding="utf-8-sig")
        for table in OLD_TABLES:
            match = re.search(r"CREATE TABLE IF NOT EXISTS `" + table + r"` \([\s\S]*?\) ENGINE=InnoDB[^;]*;", init)
            assert match, table
            sql(match.group())
        sql("""
INSERT INTO mes_md_item(id,code,name,specification,status,remark,tenant_id,deleted,create_time,update_time)
VALUES (1,' ITEM-1 ','历史物料甲','原始规格',0,CONCAT('原样保留',CHAR(10),'换行与引号 '' <>&'),1,0,'2020-01-02 03:04:05','2021-02-03 04:05:06'),
(2,'ITEM-2','另一租户物料',NULL,1,'不可改写',2,0,'2020-01-02 03:04:05','2021-02-03 04:05:06');
INSERT INTO mes_wm_barcode_config(id,format,biz_type,content_format,content_example,auto_generate_flag,default_template,status,remark,tenant_id,deleted,create_time,update_time)
VALUES (10,1,600,'{BUSINESSCODE}','旧示例',1,'<div>旧自定义模板</div>',0,'历史非法模板也不能静默重写',1,0,'2020-01-02 03:04:05','2021-02-03 04:05:06'),
(11,4,600,'{BUSINESSCODE}',NULL,0,NULL,1,'历史重复配置',1,0,'2020-01-02 03:04:05','2021-02-03 04:05:06'),
(12,1,600,'{BUSINESSCODE}',NULL,1,'LABEL_50X30',0,'第二租户',2,0,'2020-01-02 03:04:05','2021-02-03 04:05:06');
INSERT INTO mes_wm_barcode(id,config_id,format,biz_type,content,biz_id,biz_code,biz_name,status,remark,tenant_id,deleted,create_time,update_time)
VALUES (100,10,1,600,CONCAT('扫码原文',CHAR(10),'  空格 '' <>&'),1,' OLD-CODE ','旧名称',0,'历史登记原样保留',1,0,'2020-01-02 03:04:05','2021-02-03 04:05:06'),
(101,10,1,600,CONCAT('扫码原文',CHAR(10),'  空格 '' <>&'),1,'DUP','历史重复',0,'不得为加唯一索引而删除',1,0,'2020-01-02 03:04:05','2021-02-03 04:05:06'),
(102,12,1,600,'TENANT-2',2,'ITEM-2','别的租户',1,NULL,2,1,'2020-01-02 03:04:05','2021-02-03 04:05:06');
""")
        old = snapshot()
        old_indexes = indexes()
        migration_path = ROOT / str(report["migration"])
        migration = migration_path.read_text(encoding="utf-8-sig")
        report["migrationSha256"] = hashlib.sha256(migration.encode()).hexdigest()
        sql(migration)
        check("actual first migration creates exactly one new technical table",
              sql("SELECT GROUP_CONCAT(table_name ORDER BY table_name) FROM information_schema.tables WHERE table_schema=DATABASE();")
              == "mes_md_item,mes_wm_barcode,mes_wm_barcode_config,mes_wm_barcode_mutex")
        check("global existing primary key is seeded exactly once", sql("SELECT GROUP_CONCAT(tenant_id ORDER BY tenant_id) FROM mes_wm_barcode_mutex;") == "-1")
        check("first migration preserves every old barcode/config/master field byte-for-byte", snapshot() == old)
        check("first migration preserves old indexes and legacy duplicate rows", indexes() == old_indexes)
        check("mutex primary key is tenant_id only", sql("SELECT CONCAT(index_name,':',column_name,':',non_unique) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='mes_wm_barcode_mutex';") == "PRIMARY:tenant_id:0")
        check("mutex is transactional InnoDB", sql("SELECT engine FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='mes_wm_barcode_mutex';") == "InnoDB")
        check("technical mutex has no business foreign keys", sql("SELECT COUNT(*) FROM information_schema.referential_constraints WHERE constraint_schema=DATABASE();") == "0")
        sql("INSERT INTO mes_wm_barcode_mutex(tenant_id) VALUES(1),(2),(9223372036854775807);")
        sql("INSERT INTO mes_wm_barcode(id,config_id,format,biz_type,content,biz_id,biz_code,biz_name,status,tenant_id,create_time,update_time) VALUES(200,10,1,600,'NEW-POST-MIGRATION',999,'NEW-999','新登记',0,1,'2026-10-20 01:02:03','2026-10-20 01:02:03');")
        after_new_record = snapshot()
        mutex = sql("SELECT tenant_id FROM mes_wm_barcode_mutex ORDER BY tenant_id;")
        sql(migration)
        check("second actual migration retains newly written barcode as well as all history", snapshot() == after_new_record)
        check("second actual migration does not clear tenant/global mutex records", sql("SELECT tenant_id FROM mes_wm_barcode_mutex ORDER BY tenant_id;") == mutex)
        sql("DELETE FROM mes_wm_barcode_mutex WHERE tenant_id=-1;")
        check("partial DDL fixture has table but no global seed", sql("SELECT COUNT(*) FROM mes_wm_barcode_mutex WHERE tenant_id=-1;") == "0")
        sql(migration)
        check("retry after partial DDL restores missing global key and preserves tenant keys", sql("SELECT tenant_id FROM mes_wm_barcode_mutex ORDER BY tenant_id;") == mutex)
        check("partial DDL recovery still preserves all barcode/config/master bytes", snapshot() == after_new_record)
        sql("START TRANSACTION; INSERT INTO mes_wm_barcode_mutex(tenant_id) VALUES(777); ROLLBACK;")
        check("mutex writes participate in transaction rollback", sql("SELECT COUNT(*) FROM mes_wm_barcode_mutex WHERE tenant_id=777;") == "0")
        sql("INSERT INTO mes_wm_barcode_mutex(tenant_id) VALUES(-1);", succeeds=False)
        check("native primary key rejects a duplicate global lock row", sql("SELECT COUNT(*) FROM mes_wm_barcode_mutex WHERE tenant_id=-1;") == "1")
        sql(migration)
        check("fourth actual migration remains idempotent with no data/index loss",
              snapshot() == after_new_record and indexes() == old_indexes
              and sql("SELECT tenant_id FROM mes_wm_barcode_mutex ORDER BY tenant_id;") == mutex)
        report["oldTableFingerprints"] = after_new_record
        report["migrationApplications"] = 4
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
        print(json.dumps({"success": report["success"], "checks": len(checks),
                          "schemaDropped": report["schemaDropped"], "report": str(args.output)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
