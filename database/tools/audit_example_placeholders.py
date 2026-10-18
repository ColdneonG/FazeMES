"""Audit/apply local sample presentation placeholders without exposing credentials.

Reads connection options from .runtime/mysql-client.local.ini. Only yudao_mes on
port 3308 is accepted. --apply records invariants before/after and reruns the
migration to verify repeatability. No password or config JSON is printed.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[2]
MIGRATION = ROOT / "database/migrations/20261017_example_placeholders.sql"
MARKER = "(芋道|芋艿|源码|土豆|iocoder[.]cn|yudao|yunai|ruoyi)"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--mysql", default=shutil.which("mysql"))
    parser.add_argument("--output", type=Path, default=ROOT / "database/audit/example-placeholders.json")
    args = parser.parse_args()
    if not args.mysql:
        raise SystemExit("MySQL client not found; provide --mysql.")
    options = ROOT / ".runtime/mysql-client.local.ini"
    command = [args.mysql, f"--defaults-extra-file={options}", "--database=yudao_mes", "--batch", "--raw", "--skip-column-names"]

    def query(sql):
        result = subprocess.run(command, input="SET SESSION group_concat_max_len=16777216;\n" + sql,
                                capture_output=True, text=True, encoding="utf-8")
        if result.returncode:
            # SQL contains no supplied credentials. Do not dump connection files
            # or whole rows/config JSON into errors.
            raise RuntimeError(result.stderr.strip())
        return [line.split("\t") for line in result.stdout.splitlines() if line]

    if query("SELECT DATABASE(), @@port;") != [["yudao_mes", "3308"]]:
        raise SystemExit("Refusing to access a database other than yudao_mes on port 3308.")

    def scalar(sql):
        return query(sql)[0][0]

    def invariants():
        result = {"table_count": int(scalar("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE();"))}
        # An aggregate one-way digest verifies unchanged account credentials;
        # neither individual password hashes nor config secrets leave MySQL.
        result["accounts_digest"] = scalar("SELECT SHA2(GROUP_CONCAT(CONCAT(id,':',username,':',SHA2(password,256),':',tenant_id) ORDER BY id SEPARATOR '|'),256) FROM system_users;")
        result["permissions_digest"] = scalar("SELECT SHA2(GROUP_CONCAT(CONCAT(id,':',permission) ORDER BY id SEPARATOR '|'),256) FROM system_menu;")
        result["role_links_digest"] = scalar("SELECT SHA2(GROUP_CONCAT(CONCAT(role_id,':',menu_id,':',tenant_id) ORDER BY role_id,menu_id SEPARATOR '|'),256) FROM system_role_menu;")
        result["stock_digest"] = scalar("SELECT SHA2(GROUP_CONCAT(CONCAT(id,':',item_id,':',quantity,':',warehouse_id,':',tenant_id) ORDER BY id SEPARATOR '|'),256) FROM mes_wm_material_stock;")
        result["work_orders_digest"] = scalar("SELECT SHA2(GROUP_CONCAT(CONCAT(id,':',code,':',quantity,':',IFNULL(quantity_produced,0),':',status,':',tenant_id) ORDER BY id SEPARATOR '|'),256) FROM mes_pro_work_order;")
        result["cloud_credentials_digest"] = scalar("SELECT SHA2(GROUP_CONCAT(CONCAT(id,':',IFNULL(JSON_UNQUOTE(JSON_EXTRACT(config,'$.accessKey')),''),':',IFNULL(JSON_UNQUOTE(JSON_EXTRACT(config,'$.accessSecret')),''),':',IFNULL(JSON_UNQUOTE(JSON_EXTRACT(config,'$.password')),''),':',JSON_UNQUOTE(JSON_EXTRACT(config,'$.\"@class\"'))) ORDER BY id SEPARATOR '|'),256) FROM infra_file_config WHERE JSON_VALID(config);")
        return result

    def report():
        checks = {
            "user_display_names": f"SELECT COUNT(*) FROM system_users WHERE nickname REGEXP '{MARKER}';",
            "tenant_display": f"SELECT COUNT(*) FROM system_tenant WHERE CONCAT_WS(' ',name,contact_name,websites) REGEXP '{MARKER}';",
            "department_display": f"SELECT COUNT(*) FROM system_dept WHERE name REGEXP '{MARKER}';",
            "oauth_display": f"SELECT COUNT(*) FROM system_oauth2_client WHERE CONCAT_WS(' ',name,logo,description,redirect_uris) REGEXP '{MARKER}';",
            "menu_display_links": f"SELECT COUNT(*) FROM system_menu WHERE CONCAT_WS(' ',name,path) REGEXP '{MARKER}';",
            "notice_display": f"SELECT COUNT(*) FROM system_notice WHERE CONCAT_WS(' ',title,content) REGEXP '{MARKER}';",
            "storage_display": f"SELECT COUNT(*) FROM infra_file_config WHERE CONCAT_WS(' ',name,remark,JSON_UNQUOTE(JSON_EXTRACT(config,'$.domain')),JSON_UNQUOTE(JSON_EXTRACT(config,'$.bucket'))) REGEXP '{MARKER}';",
            "upstream_sample_storage_directory": "SELECT COUNT(*) FROM infra_file_config WHERE JSON_UNQUOTE(JSON_EXTRACT(config,'$.basePath')) = '/Users/yunai/tmp/file';",
            "social_display_names": f"SELECT COUNT(*) FROM system_social_client WHERE name REGEXP '{MARKER}';",
            "mail_template_display": f"SELECT COUNT(*) FROM system_mail_template WHERE nickname REGEXP '{MARKER}';",
            "mail_sample_host": f"SELECT COUNT(*) FROM system_mail_account WHERE host REGEXP '{MARKER}';",
            "notification_display": f"SELECT COUNT(*) FROM system_notify_message WHERE CONCAT_WS(' ',template_content,template_params) REGEXP '{MARKER}';",
            "sample_contacts": f"SELECT COUNT(*) FROM yudao_demo01_contact WHERE CONCAT_WS(' ',name,description) REGEXP '{MARKER}';",
            "sample_categories": f"SELECT COUNT(*) FROM yudao_demo02_category WHERE name REGEXP '{MARKER}';",
            "upstream_file_images": f"SELECT COUNT(*) FROM infra_file WHERE url REGEXP '{MARKER}' AND type LIKE 'image/%';",
        }
        return {
            "migration": MIGRATION.relative_to(ROOT).as_posix(),
            "migration_sha256": hashlib.sha256(MIGRATION.read_bytes()).hexdigest(),
            "database": "yudao_mes", "port": 3308,
            "default_tenant": query("SELECT id,name FROM system_tenant WHERE id=1;")[0],
            "default_admin": query("SELECT id,username,nickname,avatar FROM system_users WHERE id=1;")[0],
            "local_file_configuration": query("SELECT id,name FROM infra_file_config WHERE id=29;")[0],
            "remaining_upstream_display_counts": {key: int(scalar(sql)) for key, sql in checks.items()},
            "preserved_technical_identifiers": ["system_users.username", "system_oauth2_client.client_id", "infra_file_config.config.@class", "Local project filesystem path D:/indus_app/yudao-mes", "Java packages / API identifiers / table names / permission strings"],
        }

    before = invariants()
    if args.apply:
        query(MIGRATION.read_text(encoding="utf-8"))
    after = invariants()
    if args.apply and before != after:
        raise RuntimeError("Account, permission, business-quantity or credential invariants changed.")
    result = report()
    if args.apply:
        first_report = result
        query(MIGRATION.read_text(encoding="utf-8"))
        result = report()
        if result != first_report or invariants() != after:
            raise RuntimeError("Migration did not preserve its display/account state on repeat.")
        result["repeat_application_verified"] = True
        result["protected_invariants_unchanged"] = True
        result["protected_invariant_names"] = sorted(after)
    result["all_targeted_display_checks_clear"] = not any(result["remaining_upstream_display_counts"].values())
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"audit": str(args.output), "all_targeted_display_checks_clear": result["all_targeted_display_checks_clear"],
                      "remaining_upstream_display_counts": result["remaining_upstream_display_counts"],
                      "migration_applied": args.apply}, ensure_ascii=False))


if __name__ == "__main__":
    main()
