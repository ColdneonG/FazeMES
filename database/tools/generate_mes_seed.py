"""Create idempotent, synthetic MES demo records from the public Java contracts."""
from __future__ import annotations

from collections import Counter
import json
from pathlib import Path
import re

from generate_mes_schema import BACKEND, JAVA, OUT, build_contracts


class Expr(str):
    """A deliberate SQL expression, never a user supplied string."""


def literal(value):
    if isinstance(value, Expr):
        return str(value)
    if value is None:
        return "NULL"
    if isinstance(value, bool):
        return "b'1'" if value else "b'0'"
    if isinstance(value, (int, float)):
        return str(value)
    return "'" + str(value).replace("\\", "\\\\").replace("'", "''") + "'"


def enum_values(filename, constants=None):
    source = (JAVA / "enums" / filename).read_text(encoding="utf-8-sig")
    rows = re.findall(r'^\s+[A-Z][A-Z0-9_]*\((\d+|MesBizTypeConstants\.\w+),\s*"([^"]+)"', source, re.M)
    return [(int(value) if value.isdigit() else constants[value.split(".")[1]], label) for value, label in rows]


def system_audit():
    baseline = (BACKEND / "sql/mysql/ruoyi-vue-pro.sql").read_text(encoding="utf-8-sig")
    type_rows = [line for line in baseline.splitlines() if line.startswith("INSERT INTO `system_dict_type`")]
    data_rows = [line for line in baseline.splitlines() if line.startswith("INSERT INTO `system_dict_data`")]
    menu_rows = [line for line in baseline.splitlines() if line.startswith("INSERT INTO `system_menu`")]
    required_dicts = set(re.findall(r'= "(mes_\w+)"', (JAVA / "enums/DictTypeConstants.java").read_text(encoding="utf-8-sig")))
    dict_types = set(re.findall(r"'(mes_\w+)'", "\n".join(type_rows)))
    dict_data_types = set(re.findall(r"'(mes_\w+)'", "\n".join(data_rows)))
    required_perms = set(re.findall(r"hasPermission\('([^']+)'\)", "\n".join(path.read_text(encoding="utf-8-sig") for path in (JAVA / "controller").rglob("*Controller.java"))))
    menu_perms = set(re.findall(r"'(mes:[^']+)'", "\n".join(menu_rows)))
    menu_parents = {}
    page_names = {}
    for line in menu_rows:
        row = re.search(r"VALUES \((\d+), '((?:''|[^'])*)', '([^']*)', (\d+), (\d+), (\d+),", line)
        if row:
            menu_id, name, permission, kind, sort, parent_id = row.groups()
            page_names[int(menu_id)] = name
            if permission.startswith("mes:"):
                menu_parents[permission.rsplit(":", 1)[0]] = int(parent_id)
    return {"required_dictionary_types": sorted(required_dicts), "upstream_dictionary_types": sorted(dict_types),
            "missing_dictionary_types": sorted(required_dicts - dict_types),
            "missing_dictionary_data": sorted(required_dicts - dict_data_types),
            "controller_permission_count": len(required_perms), "upstream_permission_count": len(menu_perms),
            "missing_permissions": sorted(required_perms - menu_perms)}, menu_parents, page_names


def main():
    tables = build_contracts()
    lines = ["-- SYNTHETIC LOCAL DEMONSTRATION DATA. Not production records.",
             "-- Import after upstream ruoyi-vue-pro.sql and this project's mes_schema.sql.",
             "-- Then apply database/migrations/20261017_example_placeholders.sql for local presentation placeholders.",
             "-- Fixed IDs and no-op duplicate handling preserve executed business records.",
             "-- No separately distributed mes.sql.zip was used.",
             "SET NAMES utf8mb4;", "START TRANSACTION;", ""]
    counts = Counter()

    def insert(table, **values):
        pk = tables[table]["primary_key"] if table in tables else "id"
        values.setdefault("creator", "local-demo")
        values.setdefault("updater", "local-demo")
        values.setdefault("create_time", Expr("CURRENT_TIMESTAMP"))
        values.setdefault("update_time", Expr("CURRENT_TIMESTAMP"))
        values.setdefault("deleted", False)
        if table in tables:
            values.setdefault("tenant_id", 1)
            invalid = set(values) - set(tables[table]["columns"])
            if invalid:
                raise ValueError(f"Seed fields absent from public DO: {table}: {invalid}")
        columns = ", ".join(f"`{col}`" for col in values)
        row = ", ".join(literal(value) for value in values.values())
        lines.append(f"INSERT INTO `{table}` ({columns}) VALUES ({row}) ON DUPLICATE KEY UPDATE `{pk}` = `{pk}`;")
        counts[table] += 1

    audit, menu_parents, page_names = system_audit()
    biz_constants = {name: int(value) for name, value in re.findall(r"public static final int (\w+) = (\d+)", (JAVA / "enums/MesBizTypeConstants.java").read_text(encoding="utf-8-sig"))}
    missing_dicts = {
        "mes_barcode_format": ("MES 条码格式", enum_values("wm/BarcodeFormatEnum.java")),
        "mes_barcode_biz_type": ("MES 条码业务类型", enum_values("wm/BarcodeBizTypeEnum.java", biz_constants)),
        "mes_wm_transaction_type": ("MES 库存事务类型", enum_values("wm/MesWmTransactionTypeEnum.java")),
    }
    if (set(audit["missing_dictionary_types"]) | set(audit["missing_dictionary_data"])) - set(missing_dicts):
        raise ValueError("New upstream dictionary gap requires review")
    for number, code in enumerate(sorted(missing_dicts), 1):
        if code not in audit["missing_dictionary_types"] and code not in audit["missing_dictionary_data"]:
            continue
        name, values = missing_dicts[code]
        if code in audit["missing_dictionary_types"]:
            insert("system_dict_type", id=900000 + number, name=name, type=code, status=0, remark="公开枚举重建的本地补充字典")
        if code in audit["missing_dictionary_data"]:
            for sort, (value, label) in enumerate(values, 1):
                insert("system_dict_data", id=900100 + number * 100 + sort, sort=sort, label=label, value=str(value), dict_type=code, status=0, color_type="primary", css_class="", remark="公开枚举重建")
    # Some upstream button rows use earlier permission names. Add current
    # controller permissions without deleting or rewriting upstream menus.
    fallback_parents = {"mes:pro-task-issue": menu_parents.get("mes:pro-task", 1104),
                        "mes:wm-product-produce": menu_parents.get("mes:wm-product-receipt", 1000)}
    action_labels = {"create": "创建", "update": "修改", "delete": "删除", "query": "查询", "export": "导出", "finish": "完成"}
    permission_rows = []
    for number, permission in enumerate(audit["missing_permissions"], 1):
        family, action = permission.rsplit(":", 1)
        parent_id = menu_parents.get(family, fallback_parents.get(family))
        if parent_id is None:
            raise ValueError(f"Missing menu parent for {permission}")
        name = page_names.get(parent_id, family) + action_labels.get(action, action)
        insert("system_menu", id=900000 + number, name=name, permission=permission, type=3, sort=90 + number,
               parent_id=parent_id, path="", icon="", component="", component_name="", status=0,
               visible=True, keep_alive=True, always_show=True)
        permission_rows.append({"permission": permission, "parent_id": parent_id})
    lines.append("")

    # Rule codes and descriptions come from the public enum. The parts are a
    # local choice: DEMO-prefix + date + daily Redis sequence.
    source = (JAVA / "enums/md/autocode/MesMdAutoCodeRuleCodeEnum.java").read_text(encoding="utf-8-sig")
    rules = re.findall(r'^\s+[A-Z][A-Z0-9_]*\("([A-Z0-9_]+)",\s*"([^"]+)"\)', source, re.M)
    for number, (code, name) in enumerate(rules, 1):
        rule_id = 900100 + number
        prefix = "DEMO-" + code.removesuffix("_CODE").replace("_", "") + "-"
        insert("mes_md_auto_code_rule", id=rule_id, code=code, name="本地演示-" + name,
               description="演示前缀+日期+每日五位流水号", max_length=64, padded=False,
               padded_char="0", padded_method=1, status=0, remark="独立本地演示规则")
        for part_no, values in enumerate([
            dict(type=3, length=len(prefix), fix_character=prefix, cycle_flag=False),
            dict(type=2, length=8, date_format="yyyyMMdd", cycle_flag=False),
            dict(type=4, length=5, serial_start_no=1, serial_step=1, cycle_flag=True, cycle_method=3),
        ], 1):
            insert("mes_md_auto_code_part", id=900500 + number * 3 + part_no, rule_id=rule_id, sort=part_no,
                   remark="本地演示规则分段", **values)
    lines.append("")

    insert("mes_md_unit_measure", id=900001, code="DEMO-PCS", name="件", primary_flag=True, primary_id=0, change_rate=1, status=0, remark="本地演示计量单位")
    for item_type_id, code, name, kind, sort in [(900001, "DEMO-RAW", "演示原材料", "ITEM", 1), (900002, "DEMO-PRODUCT", "演示产品", "PRODUCT", 2)]:
        insert("mes_md_item_type", id=item_type_id, code=code, name=name, parent_id=0, item_or_product=kind, sort=sort, status=0, remark="本地演示分类")
    items = [(900101, "DEMO-RAW-001", "演示铝合金毛坯", 900001),
             (900102, "DEMO-RAW-002", "演示装配螺栓", 900001),
             (900103, "DEMO-PRO-001", "演示传动组件", 900002)]
    for item_id, code, name, item_type_id in items:
        insert("mes_md_item", id=item_id, code=code, name=name, specification="本地实训演示规格",
               unit_measure_id=900001, item_type_id=item_type_id, status=0, safe_stock_flag=True,
               min_stock=20, max_stock=3000, high_value=False, batch_flag=True, remark="虚构本地演示物料")
        # Leave optional matching dimensions off, so receiving/producing can
        # generate batches without requiring unrelated external integrations.
        batch_config_fields = {field: False for field in tables["mes_md_item_batch_config"]["columns"] if field.endswith("_flag")}
        insert("mes_md_item_batch_config", id=item_id, item_id=item_id, **batch_config_fields)
    for bom_id, item_id, qty in [(900201, 900101, 1), (900202, 900102, 4)]:
        insert("mes_md_product_bom", id=bom_id, item_id=900103, bom_item_id=item_id, quantity=qty, status=0, remark="1件组件=1件毛坯+4件螺栓")
    insert("mes_md_client", id=900001, code="DEMO-CLIENT", name="本地演示客户", nickname="演示客户", type=1, status=0, remark="虚构演示客户，无真实联系方式")
    insert("mes_md_vendor", id=900001, code="DEMO-VENDOR", name="本地演示供应商", nickname="演示供应商", level="A", score=100, status=0, remark="虚构演示供应商，无真实联系方式")
    insert("mes_md_workshop", id=900001, code="DEMO-SHOP", name="本地演示车间", area=200, charge_user_id=1, status=0, remark="本地实训演示")

    for wid, wcode, wname, lcode, acode in [
        (900001, "DEMO-WH", "演示原料成品仓", "DEMO-LOC", "DEMO-A01"),
        (900002, "WIP_VIRTUAL_WAREHOUSE", "演示虚拟线边仓", "WIP_VIRTUAL_LOCATION", "WIP_VIRTUAL_AREA"),
    ]:
        insert("mes_wm_warehouse", id=wid, code=wcode, name=wname, address="本地实训环境", area=100, charge_user_id=1, frozen=False, remark="虚构本地演示仓库")
        insert("mes_wm_warehouse_location", id=wid, code=lcode, name="演示线边库区" if wid == 900002 else "演示库区", warehouse_id=wid, area=100, frozen=False, remark="本地演示三级仓储")
        insert("mes_wm_warehouse_area", id=wid, code=acode, name="演示线边库位" if wid == 900002 else "演示A01库位", location_id=wid, area=20, max_load=3000,
               position_x=1, position_y=1, position_z=1, status=0, frozen=False, allow_item_mixing=True, allow_batch_mixing=True, remark="本地演示三级仓储")

    for number, (item_id, code, name, item_type_id) in enumerate(items, 1):
        insert("mes_wm_batch", id=900000 + number, code=f"DEMO-BATCH-{number:03d}", item_id=item_id,
               produce_date=Expr("CURRENT_TIMESTAMP"), receipt_date=Expr("CURRENT_TIMESTAMP"), vendor_id=900001, quality_status=1, remark="虚构演示合格批次")
    for stock_id, item_id, item_type_id, batch_id, qty, wid in [
        (900001, 900101, 900001, 900001, 500, 900001),
        (900002, 900102, 900001, 900002, 2000, 900001),
        (900003, 900103, 900002, 900003, 40, 900001),
        (900004, 900101, 900001, 900001, 100, 900002),
        (900005, 900102, 900001, 900002, 400, 900002),
    ]:
        batch_code = f"DEMO-BATCH-{batch_id - 900000:03d}"
        insert("mes_wm_material_stock", id=stock_id, item_type_id=item_type_id, item_id=item_id, batch_id=batch_id,
               batch_code=batch_code, warehouse_id=wid, location_id=wid, area_id=wid, vendor_id=900001,
               quantity=qty, receipt_time=Expr("CURRENT_TIMESTAMP"), frozen=False)
        insert("mes_wm_transaction", id=stock_id, type=5, biz_type=108, biz_id=stock_id,
               biz_code="DEMO-OPENING", biz_line_id=stock_id, material_stock_id=stock_id, item_id=item_id,
               quantity=qty, batch_id=batch_id, batch_code=batch_code, warehouse_id=wid, location_id=wid,
               area_id=wid, transaction_time=Expr("CURRENT_TIMESTAMP"), receipt_time=Expr("CURRENT_TIMESTAMP"))
    lines.append("")

    for process_id, code, name in [(900001, "DEMO-CUT", "演示下料"), (900002, "DEMO-ASSEMBLY", "演示装配")]:
        insert("mes_pro_process", id=process_id, code=code, name=name, attention="本地实训演示工序", status=0, remark="虚构演示工序")
        insert("mes_md_workstation", id=process_id, code=code + "-ST", name=name + "工作站", address="演示车间",
               workshop_id=900001, process_id=process_id, warehouse_id=900002, location_id=900002, area_id=900002, status=0, remark="使用虚拟线边库")
    insert("mes_pro_route", id=900001, code="DEMO-ROUTE", name="演示组件生产路线", description="下料 → 装配；仅装配为关键产出工序", status=0, remark="本地演示")
    for record_id, process_id, sort, next_id in [(900001, 900001, 1, 900002), (900002, 900002, 2, 0)]:
        insert("mes_pro_route_process", id=record_id, route_id=900001, process_id=process_id, sort=sort,
               next_process_id=next_id, link_type=3, prepare_time=0, wait_time=0,
               color_code="#409EFF" if sort == 1 else "#67C23A", key_flag=sort == 2, check_flag=False,
               remark="本地演示；末道关键工序计入工单产出")
    insert("mes_pro_route_product", id=900001, route_id=900001, item_id=900103, quantity=1, production_time=10, time_unit_type="MINUTE", remark="演示单位产品标准工时")
    for record_id, process_id, item_id, qty in [(900001, 900001, 900101, 1), (900002, 900002, 900102, 4)]:
        insert("mes_pro_route_product_bom", id=record_id, route_id=900001, process_id=process_id,
               product_id=900103, item_id=item_id, quantity=qty, remark="按报工数量乘本用料比例扣减线边库存")
    insert("mes_pro_work_order", id=900501, code="DEMO-WO-001", name="演示组件生产工单", type=1, order_source_type=2,
           order_source_code="DEMO-STOCK", product_id=900103, quantity=50, quantity_produced=0, quantity_changed=0,
           quantity_scheduled=0, client_id=900001, batch_code="DEMO-WO-BATCH", request_date=Expr("DATE_ADD(CURRENT_TIMESTAMP, INTERVAL 7 DAY)"),
           parent_id=0, status=0, remark="演示草稿：确认后在排产管理选择两工序及工作站创建任务，再报工验证扣料和关键工序产出。")
    for record_id, item_id, qty in [(900001, 900101, 50), (900002, 900102, 200)]:
        insert("mes_pro_work_order_bom", id=record_id, work_order_id=900501, item_id=item_id, quantity=qty, remark="50件演示工单用料清单")
    # Barcode configuration enables later scanner/label trials. Content uses
    # the business code itself; choosing a print template remains a UI action.
    for number, (value, label) in enumerate(enum_values("wm/BarcodeBizTypeEnum.java", biz_constants), 1):
        insert("mes_wm_barcode_config", id=900000 + number, format=1, biz_type=value,
               content_format="{BUSINESSCODE}", content_example="DEMO-EXAMPLE", auto_generate_flag=True,
               status=0, remark="本地演示二维码配置：" + label)

    lines += ["COMMIT;", ""]
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "mes_seed.sql").write_text("\n".join(lines), encoding="utf-8")
    audit.update({"added_permissions": permission_rows, "auto_code_rule_count": len(rules), "seed_rows_by_table": dict(sorted(counts.items())),
                  "seed_field_validation": "All MES seed columns checked against public DO contracts", "remaining_dictionary_gaps": [], "remaining_controller_permission_gaps": [],
                  "post_seed_migrations": ["database/migrations/20261017_example_placeholders.sql"]})
    (OUT / "mes_seed_audit.json").write_text(json.dumps(audit, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Generated {sum(counts.values())} synthetic records, {len(rules)} code rules, {len(permission_rows)} permission fixes.")


if __name__ == "__main__":
    main()
