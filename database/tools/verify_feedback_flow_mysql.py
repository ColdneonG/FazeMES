#!/usr/bin/env python3
"""Opt-in migration / real MySQL genealogy SQL checks on the disposable test instance.

This harness creates fresh mes_flow_it_* schemas on 127.0.0.1:59685 only. It never
opens or migrates application data. The real migration is applied to old-schema
fixtures and repeated to verify that newly posted evidence is never downgraded.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import uuid
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[2]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--instance", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    meta = json.loads(args.instance.read_text(encoding="utf-8-sig"))
    assert meta["port"] == 59685, "Only the disposable test MySQL port is permitted"
    assert "tmp" in Path(meta["data"]).parts, "Only the disposable data directory is permitted"
    cli = [meta["client"], "--no-defaults", "--protocol=TCP", "--host=127.0.0.1",
           "--port=59685", "--user=root", "--batch", "--raw", "--skip-column-names",
           "--default-character-set=utf8mb4"]
    schema = "mes_flow_it_" + uuid.uuid4().hex
    assert schema.startswith("mes_flow_it_") and schema.replace("_", "").isalnum()

    def sql(statement: str, *, db: str | None = schema, succeeds: bool = True) -> str:
        if db is not None:
            assert db == schema
        result = subprocess.run(cli + ([f"--database={db}"] if db else []), input=statement,
                                encoding="utf-8", capture_output=True, timeout=90)
        if succeeds:
            assert result.returncode == 0, result.stderr
        else:
            assert result.returncode != 0, "Expected MySQL constraint rejection"
        return result.stdout.strip()

    def check(statement: str, expected: object) -> None:
        actual = sql(statement)
        assert actual == str(expected), (statement, actual, expected)

    sql(f"CREATE DATABASE `{schema}` CHARACTER SET utf8mb4;", db=None)
    # All actual genealogy/warehouse columns come from current init DDL.
    old_ddl = (ROOT / "database/init/mes_schema.sql").read_text(encoding="utf-8-sig")
    sql(old_ddl)
    sn_ddl = (ROOT / "database/migrations/20261018_sn_trace.sql").read_text(encoding="utf-8-sig")
    sql(sn_ddl.split("INSERT INTO system_menu", 1)[0])
    # Fresh generated schemas may already include the columns; remove ONLY from this
    # just-created empty schema so the real migration is exercised as an upgrade.
    for column in ("flow_required", "flow_complete"):
        if sql(f"SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='mes_pro_feedback' AND column_name='{column}';") == "1":
            sql(f"ALTER TABLE mes_pro_feedback DROP COLUMN {column};")
    for table in ("mes_pro_feedback_source", "mes_pro_feedback_ancestor"):
        sql(f"DROP TABLE IF EXISTS {table};")
    check("SELECT DATABASE();", schema)
    sql("INSERT INTO mes_pro_feedback(id,tenant_id,status,deleted,feedback_quantity,qualified_quantity,unqualified_quantity) VALUES"
        "(1,1,0,b'0',2,2,0),(2,1,2,b'0',2,2,0),(3,1,3,b'0',2,2,0),"
        "(4,1,4,b'0',2,2,0),(5,2,4,b'0',2,2,0),(6,2,4,b'1',2,2,0),(7,1,5,b'0',2,2,0);")
    migration_path = ROOT / "database/migrations/20261019_feedback_flow.sql"
    migration = migration_path.read_text(encoding="utf-8-sig")
    sql(migration)
    check("SELECT GROUP_CONCAT(id ORDER BY id) FROM mes_pro_feedback WHERE flow_required=0;", "3,4,5,6")
    check("SELECT COUNT(*) FROM mes_pro_feedback WHERE flow_complete=1;", 0)
    check("SELECT COUNT(*) FROM mes_pro_feedback_source;", 0)
    check("SELECT COUNT(*) FROM mes_pro_feedback_ancestor;", 0)
    check("SELECT SUM(feedback_quantity) FROM mes_pro_feedback;", "14.000000")
    sql("INSERT INTO mes_pro_feedback(id,tenant_id,status,flow_required,flow_complete) VALUES(8,1,4,1,1);")
    sql(migration)
    check("SELECT CONCAT(flow_required+0,':',flow_complete+0) FROM mes_pro_feedback WHERE id=8;", "1:1")
    check("SELECT GROUP_CONCAT(id ORDER BY id) FROM mes_pro_feedback WHERE flow_required=0;", "3,4,5,6")
    sql("INSERT INTO mes_pro_feedback_source(tenant_id,feedback_id,source_feedback_id,quantity) VALUES(1,100,90,0.000001);")
    check("SELECT quantity FROM mes_pro_feedback_source;", "0.000001")
    sql("INSERT INTO mes_pro_feedback_source(tenant_id,feedback_id,source_feedback_id,quantity) VALUES(1,100,91,0);", succeeds=False)
    sql("INSERT INTO mes_pro_feedback_source(tenant_id,feedback_id,source_feedback_id,quantity) VALUES(1,100,90,1);", succeeds=False)
    sql("INSERT INTO mes_pro_feedback_source(tenant_id,feedback_id,source_feedback_id,quantity) VALUES(2,100,90,1);")
    sql("DELETE FROM mes_pro_feedback_source; DELETE FROM mes_pro_feedback;")

    # Simulate crashes between MySQL's implicit-commit ALTERs. Old posted records
    # retain the atomic false default, while new writers' explicit true survives.
    sql("ALTER TABLE mes_pro_feedback DROP COLUMN flow_complete; ALTER TABLE mes_pro_feedback DROP COLUMN flow_required;"
        "ALTER TABLE mes_pro_feedback ADD COLUMN flow_required BIT NOT NULL DEFAULT b'0';"
        "INSERT INTO mes_pro_feedback(id,tenant_id,status,flow_required) VALUES(40,1,4,0),(41,1,4,1),(42,1,0,0);")
    sql(migration)
    check("SELECT GROUP_CONCAT(CONCAT(id,':',flow_required+0,':',flow_complete+0) ORDER BY id) FROM mes_pro_feedback;",
          "40:0:0,41:1:0,42:1:0")
    sql("DELETE FROM mes_pro_feedback; ALTER TABLE mes_pro_feedback ALTER COLUMN flow_required SET DEFAULT b'0';"
        "INSERT INTO mes_pro_feedback(id,tenant_id,status,flow_required,flow_complete) VALUES(50,1,4,0,0),(51,1,4,1,1),(52,1,NULL,0,0);")
    sql(migration)
    check("SELECT GROUP_CONCAT(CONCAT(id,':',flow_required+0,':',flow_complete+0) ORDER BY id) FROM mes_pro_feedback;",
          "50:0:0,51:1:1,52:1:0")
    sql("INSERT INTO mes_pro_feedback(id,tenant_id,status) VALUES(53,1,0);")
    check("SELECT flow_required+0 FROM mes_pro_feedback WHERE id=53;", 1)
    sql("DELETE FROM mes_pro_feedback;")

    # Use the maintained production generator itself as the master-data authority.
    sys.path.insert(0, str(ROOT / "database/tools"))
    import generate_broky_product as broky
    rows = broky.build_rows()
    assignments = [values for table, values, _ in rows if table == "mes_pro_route_product_bom"]
    assert len(broky.RAW) == 28 and sum(len(processes) for _, _, processes in broky.ROUTES) == 11
    statements: list[str] = []
    for route, item, processes in broky.ROUTES:
        order = 10_000_000 + item
        ancestors: list[int] = []
        for report, _, _, _ in processes:
            statements.append(f"INSERT INTO mes_pro_feedback(id,tenant_id,work_order_id,item_id,route_id,process_id,status,feedback_quantity,qualified_quantity,unqualified_quantity,flow_required,flow_complete) VALUES({report},1,{order},{item},{route},{report},4,1,1,0,1,1);")
            materials = [row for row in assignments if row["route_id"] == route and row["process_id"] == report]
            if materials:
                statements.append(f"INSERT INTO mes_wm_item_consume(id,work_order_id,task_id,workstation_id,process_id,feedback_id,consume_date,status,tenant_id) VALUES({1000 + report},{order},{report},{report},{report},{report},NOW(),4,1);")
                for index, material in enumerate(materials):
                    raw = material["item_id"]
                    statements.append(f"INSERT INTO mes_wm_item_consume_detail(id,consume_id,line_id,item_id,quantity,batch_id,batch_code,warehouse_id,location_id,area_id,tenant_id) VALUES({100_000_000 + report * 100 + index},{1000 + report},{7000 + report},{raw},{material['quantity']},{raw},'BROKY-ITEM-{raw}-LOT',1,1,1,1);")
            if ancestors:
                statements.append(f"INSERT INTO mes_pro_feedback_source(tenant_id,feedback_id,source_feedback_id,quantity,frozen,frozen_at) VALUES(1,{report},{ancestors[-1]},1,1,NOW());")
                for ancestor in ancestors:
                    statements.append(f"INSERT INTO mes_pro_feedback_ancestor(tenant_id,feedback_id,ancestor_feedback_id) VALUES(1,{report},{ancestor});")
            if report == processes[-1][0]:
                statements.append(f"INSERT INTO mes_wm_product_produce(id,work_order_id,feedback_id,status,tenant_id) VALUES({2000 + report},{order},{report},4,1);")
                statements.append(f"INSERT INTO mes_wm_product_produce_detail(id,produce_id,item_id,quantity,batch_id,batch_code,tenant_id) VALUES({4000 + report},{2000 + report},{item},1,{item},'BROKY-ITEM-{item}-LOT',1);")
            ancestors.append(report)
    sql("\n".join(statements))

    mapper_path = ROOT / "backend/yudao-module-mes/src/main/resources/mapper/wm/batch/MesWmBatchMapper.xml"
    mapper = ET.parse(mapper_path).getroot()
    fragment = mapper.find("./sql[@id='RecordedBatchEdges']")
    assert fragment is not None

    def render(element: ET.Element, forward: bool) -> str:
        text = element.text or ""
        for child in element:
            if child.tag == "choose":
                chosen = child.find("when" if forward else "otherwise")
                assert chosen is not None
                text += render(chosen, forward)
            else:
                text += render(child, forward)
            text += child.tail or ""
        return text

    def adjacent(batch: str, forward: bool, tenant: int = 1) -> set[str]:
        assert batch.replace("-", "").isalnum()
        edge_sql = render(fragment, forward).replace("#{tenantId}", str(tenant)).replace("#{batchCode}", "'" + batch + "'")
        field = "product_batch_code" if forward else "raw_batch_code"
        output = sql(f"SELECT DISTINCT edges.{field} FROM ({edge_sql}) edges ORDER BY 1;")
        return set(output.splitlines()) if output else set()

    def trace(batch: str, forward: bool) -> set[str]:
        seen = {batch}
        queue = [batch]
        while queue:
            current = queue.pop()
            for linked in adjacent(current, forward):
                if linked not in seen:
                    seen.add(linked)
                    queue.append(linked)
        return seen - {batch}

    finished = f"BROKY-ITEM-{broky.FG}-LOT"
    expected = {f"BROKY-ITEM-{item}-LOT" for item in (*broky.RAW.values(), broky.HEAD, broky.STAND)}
    assert trace(finished, False) == expected
    for raw in broky.RAW.values():
        assert finished in trace(f"BROKY-ITEM-{raw}-LOT", True)
    assert not adjacent(finished, False, tenant=2)
    # An independently produced run of the SAME order is never included by work-order coincidence.
    sql("INSERT INTO mes_pro_feedback(id,tenant_id,work_order_id,item_id,status,flow_required,flow_complete) VALUES(9000001,1,10972101,972101,4,1,1);"
        "INSERT INTO mes_wm_item_consume(id,work_order_id,task_id,workstation_id,process_id,feedback_id,consume_date,status,tenant_id) VALUES(9000002,10972101,1,1,1,9000001,NOW(),4,1);"
        "INSERT INTO mes_wm_item_consume_detail(id,consume_id,line_id,item_id,quantity,batch_code,warehouse_id,location_id,area_id,tenant_id) VALUES(9000003,9000002,1,999,1,'UNALLOCATED-RAW',1,1,1,1);")
    assert "UNALLOCATED-RAW" not in trace(finished, False)
    result = {"schema": schema, "port": 59685, "mysqlVersion": sql("SELECT VERSION();"),
              "migration": "20261019_feedback_flow.sql", "migrationSha256": hashlib.sha256(migration_path.read_bytes()).hexdigest(),
              "mapperSha256": hashlib.sha256(mapper_path.read_bytes()).hexdigest(),
              "checks": ["historical flags do not fabricate evidence", "migration retry preserves newly posted evidence", "partial DDL crash recovery", "six-decimal quantity", "positive CHECK",
                         "tenant-scoped uniqueness", "actual mapper fragment on native MySQL",
                         "3-level 11-process Broky", "28 leaves bidirectional genealogy", "tenant separation",
                         "no same-workorder inference"], "backwardNodes": len(expected), "status": "PASS"}
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
