"""Derive the local MES schema from the public MyBatis-Plus Java contracts.

This deliberately does not use the separately distributed mes.sql.zip. Run with
Python 3.10+; no third-party packages or database connection are needed.
"""
from __future__ import annotations

from collections import OrderedDict
import hashlib
import json
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
BACKEND = ROOT / "backend"
MODULE = BACKEND / "yudao-module-mes"
JAVA = MODULE / "src/main/java/cn/iocoder/yudao/module/mes"
OUT = ROOT / "database/init"


def snake(value: str) -> str:
    return re.sub(r"(?<!^)(?=[A-Z])", "_", value).lower()


def fields(source: str):
    """Read declared scalar fields and their adjacent persistence annotations."""
    source = re.sub(r"/\*.*?\*/|//[^\n]*", "", source, flags=re.S)
    pattern = r"((?:\s*@[\w.]+(?:\([^)]*\))?)*)\s+private\s+([\w.]+(?:<[^;]+?>)?(?:\[\])?)\s+(\w+)\s*(?:=[^;]*)?;"
    for annotations, java_type, name in re.findall(pattern, source):
        if re.search(r"exist\s*=\s*false", annotations):
            continue
        custom = re.search(r"@Table(?:Field|Id)\(\s*(?:value\s*=\s*)?\"([^\"]+)\"", annotations)
        yield name, custom[1] if custom else snake(name), java_type, annotations


def build_contracts():
    base_file = next((BACKEND / "yudao-framework").rglob("BaseDO.java"))
    tenant_file = next((BACKEND / "yudao-framework").rglob("TenantBaseDO.java"))
    inherited = {"BaseDO": list(fields(base_file.read_text(encoding="utf-8-sig")))}
    inherited["TenantBaseDO"] = inherited["BaseDO"] + list(fields(tenant_file.read_text(encoding="utf-8-sig")))
    tables = OrderedDict()
    for path in sorted((JAVA / "dal/dataobject").rglob("*.java")):
        source = path.read_text(encoding="utf-8-sig")
        table = re.search(r'@TableName\(\s*(?:value\s*=\s*)?"([^\"]+)"', source)
        if not table:
            continue
        parent = re.search(r"class\s+\w+\s+extends\s+(\w+)", source)
        if parent and parent[1] not in inherited:
            raise ValueError(f"Unreviewed DO parent {parent[1]}: {path}")
        declarations = list(fields(source))
        columns = OrderedDict()
        pk = None
        for name, column, java_type, annotation in declarations + inherited.get(parent[1] if parent else "", []):
            if java_type not in {"Long", "Integer", "String", "BigDecimal", "Boolean", "LocalDateTime", "LocalDate", "LocalTime", "List<Integer>"}:
                raise ValueError(f"Unreviewed persistent field type {java_type}: {path}:{name}")
            if java_type.startswith("List") and "typeHandler" not in annotation:
                raise ValueError(f"List without persistence handler: {path}:{name}")
            columns[column] = {"property": name, "java_type": java_type}
            if "@TableId" in annotation:
                pk = column
        if not pk:
            raise ValueError(f"Missing @TableId: {path}")
        # TenantProperties enables tenancy for all tables by default. MES DOs
        # currently extend BaseDO, but the runtime interceptor still queries this.
        columns.setdefault("tenant_id", {"property": "tenantId", "java_type": "Long"})
        tables[table[1]] = {"source": path.relative_to(BACKEND).as_posix(), "class": path.stem,
                             "primary_key": pk, "columns": columns,
                             "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
    return tables


def sql_type(column, meta, pk):
    if column == pk:
        return "BIGINT NOT NULL AUTO_INCREMENT"
    if column == "tenant_id":
        return "BIGINT NOT NULL DEFAULT 1"
    if column == "deleted":
        return "BIT(1) NOT NULL DEFAULT b'0'"
    if column == "create_time":
        return "DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP"
    if column == "update_time":
        return "DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP"
    typ = meta["java_type"]
    if typ == "Long":
        return "BIGINT DEFAULT NULL"
    if typ == "Integer":
        return "INT DEFAULT 0"
    if typ == "BigDecimal":
        return "DECIMAL(20,6) DEFAULT 0"
    if typ == "Boolean":
        return "BIT(1) DEFAULT b'0'"
    if typ in {"LocalDateTime", "LocalDate", "LocalTime"}:
        return {"LocalDateTime": "DATETIME", "LocalDate": "DATE", "LocalTime": "TIME"}[typ] + " DEFAULT NULL"
    if column in {"creator", "updater"}:
        return "VARCHAR(64) NOT NULL DEFAULT ''"
    if column in {"content", "remark", "description", "attention", "reason", "request_content", "result_content"} or column.endswith(("_content", "_description", "_reason")):
        return "TEXT"
    if column.endswith(("_url", "_urls", "_path")):
        return "VARCHAR(2048) DEFAULT NULL"
    if typ == "List<Integer>":
        return "VARCHAR(255) DEFAULT NULL"
    if column == "code" or column.endswith("_code"):
        return "VARCHAR(128) DEFAULT NULL"
    return "VARCHAR(255) DEFAULT NULL"


def check_xml(tables):
    """Cross-check physical tables and qualified columns in custom XML SQL."""
    report = []
    failures = []
    keywords = {"where", "group", "order", "left", "right", "inner", "join", "on", "union", "limit"}
    for path in sorted((MODULE / "src/main/resources").rglob("*.xml")):
        xml = ET.fromstring(path.read_text(encoding="utf-8-sig"))
        checked = set()
        for statement in xml:
            sql = " ".join(statement.itertext())
            # UNION branches reuse aliases for different physical tables.
            for branch in re.split(r"\bUNION(?:\s+ALL)?\b", sql, flags=re.I):
                refs = re.findall(r"\b(?:FROM|JOIN)\s+(mes_\w+)(?:\s+(?:AS\s+)?(\w+))?", branch, re.I)
                aliases = {}
                for table, alias in refs:
                    if table not in tables:
                        failures.append(f"{path.name}:{statement.get('id')}: missing physical table {table}")
                        continue
                    checked.add(table)
                    aliases[table] = table
                    if alias and alias.lower() not in keywords:
                        aliases[alias] = table
                for alias, col in re.findall(r"\b(\w+)\.(\w+)\b", branch):
                    if alias in aliases and col not in tables[aliases[alias]]["columns"]:
                        failures.append(f"{path.name}:{statement.get('id')}: missing column {aliases[alias]}.{col}")
        report.append({"source": path.relative_to(BACKEND).as_posix(), "tables_checked": sorted(checked)})
    if failures:
        raise ValueError("\n".join(failures))
    return report


def main():
    tables = build_contracts()
    xml_report = check_xml(tables)
    try:
        revision = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=BACKEND, text=True).strip()
    except (OSError, subprocess.CalledProcessError):
        revision = "unknown"
    OUT.mkdir(parents=True, exist_ok=True)
    lines = ["-- Independently reconstructed local-development MES schema.",
             "-- Source: public @TableName/@TableId/@TableField DOs, BaseDO and MyBatis XML.",
             f"-- Upstream revision: {revision}",
             "-- No separately distributed mes.sql.zip was used.",
             "-- Import after the upstream system schema, into a dedicated database.",
             "-- Non-destructive: existing tables require separately reviewed migrations.",
             "SET NAMES utf8mb4;", ""]
    for table, meta in sorted(tables.items()):
        lines += [f"-- Contract: {meta['source']}", f"CREATE TABLE IF NOT EXISTS `{table}` ("]
        definitions = [f"  `{column}` {sql_type(column, field, meta['primary_key'])}" for column, field in meta["columns"].items()]
        definitions.append(f"  PRIMARY KEY (`{meta['primary_key']}`)")
        for column in meta["columns"]:
            if column == "tenant_id":
                definitions.append("  KEY `idx_tenant_deleted` (`tenant_id`, `deleted`)")
            elif column != meta["primary_key"] and (column.endswith("_id") or column == "code"):
                definitions.append(f"  KEY `idx_{column}` (`{column}`)")
        lines += [",\n".join(definitions), ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC;", ""]
    (OUT / "mes_schema.sql").write_text("\n".join(lines), encoding="utf-8")
    manifest = {"upstream_revision": revision, "table_count": len(tables), "tables": tables, "xml_checks": xml_report,
                "notes": ["tenant_id added for default TenantProperties interceptor, even for BaseDO entities", "types List<Integer> stored as comma-separated text by IntegerListTypeHandler", "Only qualified XML columns are automatically cross-checked; runtime integration must verify query contracts"]}
    (OUT / "mes_schema_manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Generated {len(tables)} MES tables; checked {len(xml_report)} custom XML mapper files.")


if __name__ == "__main__":
    main()
