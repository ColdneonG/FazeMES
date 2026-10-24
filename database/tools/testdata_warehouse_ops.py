"""Additional warehouse execution and report lifecycle via normal business APIs.

Run after the manufacturing/sales fixtures, using a non-journal API instance.
It never inserts inventory/transaction rows or invents a serial/package link.
"""
from __future__ import annotations

from decimal import Decimal
import hashlib
import json


SCENARIOS = [
    {"code": "WM-PACK-01", "name": "三层实物装箱", "coverage": "实际在制成品库存4件分两内箱，归中箱再归外箱；四箱完成、一箱草稿；递归查询内箱明细，库存不扣减。"},
    {"code": "WM-COUNT-01", "name": "完成盘点与未决盘盈", "coverage": "启用方案自动生成真实库存快照；一笔逐行实盘相符并完成，另一笔逐行登记、首行盘盈1件且保持盘点中；未制造调账事务。"},
    {"code": "REPORT-VERSION-01", "name": "已发布版本与后续草稿", "coverage": "两份报表通过发布API发布R1，第一份另保存R2草稿；查询用户继续只读R1，设计者可见R2，其他三份保持草稿。"},
]


def _rows(value):
    return value.get("list", []) if isinstance(value, dict) else value


def _number(value):
    number = Decimal(str(value or 0))
    return int(number) if number == number.to_integral_value() else float(number)


def _flag(value):
    return int.from_bytes(value, "big") if isinstance(value, bytes) else int(value or 0)


def _snapshot(ctx):
    return [{"id": int(r["id"]), "quantity": str(r["quantity"]), "frozen": _flag(r["frozen"]), "hold": _flag(r["quality_hold"])}
            for r in ctx.query("SELECT id,quantity,frozen,quality_hold FROM mes_wm_material_stock WHERE tenant_id=1 AND deleted=0 ORDER BY id")]


def _hash(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def seed_api(api, ctx):
    """Idempotently add missing fixtures; caller owns commit and state saving."""
    if getattr(api, "journal_path", None) is not None:
        raise ValueError("Warehouse supplement requires Api(ctx,journal=False); existing fixture journal remains untouched")
    package_time = ctx.at("warehouse:package")
    before = _snapshot(ctx)
    transaction_count = int(ctx.scalar("SELECT COUNT(*) FROM mes_wm_transaction WHERE tenant_id=1 AND deleted=0"))
    product = ctx.ids.get("product", 900103)
    stock_rows = ctx.query("""SELECT s.*,w.code AS work_order_code FROM mes_wm_material_stock s
        JOIN mes_pro_work_order w ON w.id=s.work_order_id AND w.tenant_id=s.tenant_id AND w.deleted=0 AND w.status=1
        WHERE s.tenant_id=1 AND s.deleted=0 AND s.item_id=%s AND s.quantity>=4 AND s.frozen=0 AND s.quality_hold=0
        ORDER BY s.id LIMIT 1""", (product,))
    if not stock_rows:
        raise RuntimeError("Packaging requires >=4 real product units owned by a confirmed work order; complete the in-progress manufacturing fixture first")
    stock = stock_rows[0]
    result = {"materialStockId": stock["id"], "workOrderId": stock["work_order_id"], "packedQuantity": 4,
              "stockSnapshotBefore": before, "transactionCountBefore": transaction_count,
              "packages": {}, "stocktaking": {}, "reports": {}}

    def existing(table, code):
        rows = ctx.query(f"SELECT id FROM `{table}` WHERE tenant_id=1 AND deleted=0 AND code=%s", (code,))
        return rows[0]["id"] if rows else None

    # Use explicit metric units rather than treating piece-count as length/weight.
    mm = ctx.scalar("SELECT id FROM mes_md_unit_measure WHERE code='TEST-MM' AND deleted=0 AND tenant_id=1")
    kg = ctx.scalar("SELECT id FROM mes_md_unit_measure WHERE code='TEST-KG' AND deleted=0 AND tenant_id=1")
    if not mm or not kg:
        raise RuntimeError("Packaging fixture requires seeded TEST-MM and TEST-KG units")
    package_path = "/mes/wm/package"
    sizes = {"OUTER": (650, 450, 400, 4, 5), "MIDDLE": (600, 400, 350, 4, 4.6),
             "INNER-A": (280, 180, 150, 2, 2.2), "INNER-B": (280, 180, 150, 2, 2.2),
             "DRAFT": (280, 180, 150, 0, .2)}
    for label, (length, width, height, net, gross) in sizes.items():
        code = "TST-PKG-" + label
        id_ = existing("mes_wm_package", code)
        if not id_:
            id_ = api.post(package_path + "/create", {"code": code, "packageDate": package_time.isoformat(),
                "clientId": ctx.ids.get("client", 900001), "salesOrderCode": "TST-PACK-PENDING-DISPATCH",
                "length": length, "width": width, "height": height, "sizeUnitId": mm,
                "netWeight": net, "grossWeight": gross, "weightUnitId": kg,
                "inspectorUserId": ctx.ids.get("admin", 1),
                "remark": "合成三层装箱测试；关联实际在制成品批次，无出库或SN装箱分配声明。"})
        result["packages"][label] = id_
    for label in ["INNER-A", "INNER-B"]:
        id_ = result["packages"][label]
        lines = _rows(api.page(package_path + "-line", packageId=id_))
        if not lines:
            api.post(package_path + "-line/create", {"packageId": id_, "materialStockId": stock["id"],
                "itemId": product, "quantity": 2, "workOrderId": stock["work_order_id"],
                "remark": "真实在制成品库存2件；装箱不产生出入库事务。"})
        elif len(lines) != 1 or Decimal(str(lines[0]["quantity"])) != 2 or lines[0]["materialStockId"] != stock["id"]:
            raise RuntimeError("Existing test inner carton contents differ from the required fixture")
        if api.get(package_path + "/get", id=id_)["status"] == 0:
            api.put(package_path + "/finish", id=id_)
    middle = result["packages"]["MIDDLE"]
    outer = result["packages"]["OUTER"]
    for label in ["INNER-A", "INNER-B"]:
        child = api.get(package_path + "/get", id=result["packages"][label])
        if child["parentId"] == 0:
            api.put(package_path + "/add-child-package", parentId=middle, childId=child["id"])
        elif child["parentId"] != middle:
            raise RuntimeError("Existing test inner carton belongs to another parent")
    if api.get(package_path + "/get", id=middle)["status"] == 0:
        api.put(package_path + "/finish", id=middle)
    parent = api.get(package_path + "/get", id=middle)["parentId"]
    if parent == 0:
        api.put(package_path + "/add-child-package", parentId=outer, childId=middle)
    elif parent != outer:
        raise RuntimeError("Existing test middle carton belongs to another parent")
    if api.get(package_path + "/get", id=outer)["status"] == 0:
        api.put(package_path + "/finish", id=outer)
    contents = _rows(api.page(package_path + "-line", packageId=outer))
    if len(contents) != 2 or sum(Decimal(str(x["quantity"])) for x in contents) != 4:
        raise RuntimeError("Outer carton recursive contents did not resolve to four units")

    # This enabled static plan has real stock and leaves existing freeze flags
    # untouched. The variance remains evidence for review, never a stock posting.
    plan_rows = ctx.query("SELECT * FROM mes_wm_stock_taking_plan WHERE code='TST-STP-002' AND status=0 AND deleted=0 AND tenant_id=1")
    if not plan_rows:
        raise RuntimeError("Enabled TST-STP-002 plan must exist before count execution")
    plan = plan_rows[0]
    count_path = "/mes/wm/stocktaking-task"
    count_time = ctx.at("warehouse:count")
    for label, complete in [("MATCH", True), ("VARIANCE", False)]:
        code = "TST-COUNT-" + label
        task = existing("mes_wm_stock_taking_task", code)
        if not task:
            task = api.post(count_path + "/create", {"code": code, "name": "账实相符盘点已完成" if complete else "盘盈1件待复核",
                "takingDate": count_time.isoformat(), "type": 1, "userId": ctx.ids["users"][1],
                "planId": plan["id"], "blindFlag": bool(_flag(plan["blind_flag"])), "frozen": False,
                "remark": "合成实盘记录，盘盈不调整库存；所有结果通过盘点API登记。"})
        record = api.get(count_path + "/get", id=task)
        if record["status"] == 0:
            api.put(count_path + "/submit", id=task)
        lines = _rows(api.page(count_path + "-line", taskId=task))
        if not lines:
            raise RuntimeError("Stocktaking task generated no real stock lines")
        results = _rows(api.page(count_path + "-result", taskId=task))
        done = {r["lineId"] for r in results}
        if record["status"] != 4:
            for index, line in enumerate(lines):
                if line["id"] in done:
                    continue
                taking = Decimal(str(line["quantity"])) + (Decimal(1) if not complete and index == 0 else Decimal(0))
                api.post(count_path + "-result/create", {"taskId": task, "lineId": line["id"],
                    "materialStockId": line["materialStockId"], "itemId": line["itemId"],
                    "batchId": line.get("batchId"), "batchCode": line.get("batchCode"),
                    "warehouseId": line["warehouseId"], "locationId": line["locationId"], "areaId": line["areaId"],
                    "takingQuantity": _number(taking),
                    "remark": "合成盘盈1件，等待复核，未调整库存" if not complete and index == 0 else "合成测试实盘数与快照账面数一致"})
            if complete:
                api.call("PUT", count_path + "/finish", {"id": task})
        result["stocktaking"][label] = {"id": task, "lineCount": len(lines), "expectedStatus": 4 if complete else 2}

    reports = ctx.ids.get("reports", [])
    if len(reports) < 2:
        raise RuntimeError("Report publication lifecycle requires the five draft reports")
    for report in reports[:2]:
        current = api.get("/mes/report/get", id=report)
        definition = current["definition"]
        if definition.get("publishedVersion") is None:
            api.put("/mes/report/publish", id=report, version=current["version"])
    first = api.get("/mes/report/get", id=reports[0])
    if first["definition"]["latestVersion"] == 1:
        config = first["config"]
        config["sortDirection"] = "ASC"
        api.post("/mes/report/save", {"id": reports[0], "name": "测试工单完成量（R2草稿）",
                                    "config": config, "expectedVersion": 1})
    result["reports"] = {"published": reports[:2], "unpublishedLatest": reports[0], "expectedLatest": 2, "expectedPublished": 1}
    after = _snapshot(ctx)
    after_transactions = int(ctx.scalar("SELECT COUNT(*) FROM mes_wm_transaction WHERE tenant_id=1 AND deleted=0"))
    if before != after or transaction_count != after_transactions:
        raise RuntimeError("Warehouse supplement unexpectedly changed inventory or stock transaction evidence")
    result.update(stockSnapshotHash=_hash(after), transactionCountAfter=after_transactions,
                  stockLineCount=len(after), packageCount=5, packageLineCount=2,
                  stocktakingLineCount=sum(x["lineCount"] for x in result["stocktaking"].values()))
    ctx.ids["warehouse_ops"] = result
    ctx.api_evidence.append({"check": "warehouse_ops_actual_api_execution", "passed": True,
        "evidence": {"packages": result["packages"], "recursivePackedQuantity": 4,
                     "stocktaking": result["stocktaking"], "stockSnapshotHash": result["stockSnapshotHash"],
                     "stockTransactionsUnchanged": True, "publishedReports": reports[:2], "latestDraftVersion": 2}})
    return result


def validation(ctx):
    """Counts of violations; every query must return zero."""
    checks = [
        ("warehouse_ops.package_presence", "SELECT ABS(COUNT(*)-5) FROM mes_wm_package WHERE tenant_id=1 AND deleted=0 AND code LIKE 'TST-PKG-%'"),
        ("warehouse_ops.package_links", """SELECT COUNT(*) FROM mes_wm_package p LEFT JOIN mes_wm_package parent ON parent.id=p.parent_id AND parent.deleted=0 AND parent.tenant_id=p.tenant_id
            WHERE p.code LIKE 'TST-PKG-%' AND p.deleted=0 AND (p.parent_id=p.id OR (p.parent_id>0 AND parent.id IS NULL)
            OR p.gross_weight<p.net_weight OR p.length<=0 OR p.width<=0 OR p.height<=0
            OR (p.code='TST-PKG-DRAFT' AND p.status<>0) OR (p.code<>'TST-PKG-DRAFT' AND p.status<>4))"""),
        ("warehouse_ops.package_three_levels", """SELECT IF(COUNT(*)=2,0,1) FROM mes_wm_package i JOIN mes_wm_package m ON m.id=i.parent_id
            JOIN mes_wm_package o ON o.id=m.parent_id WHERE i.code IN('TST-PKG-INNER-A','TST-PKG-INNER-B')
            AND m.code='TST-PKG-MIDDLE' AND o.code='TST-PKG-OUTER' AND o.parent_id=0 AND i.deleted=0 AND m.deleted=0 AND o.deleted=0"""),
        ("warehouse_ops.package_stock_dimensions", """SELECT COUNT(*) FROM mes_wm_package_line l JOIN mes_wm_package p ON p.id=l.package_id
            LEFT JOIN mes_wm_material_stock s ON s.id=l.material_stock_id AND s.deleted=0 AND s.tenant_id=l.tenant_id
            LEFT JOIN mes_pro_work_order w ON w.id=l.work_order_id AND w.deleted=0 AND w.tenant_id=l.tenant_id
            WHERE p.code LIKE 'TST-PKG-%' AND l.deleted=0 AND (s.id IS NULL OR w.id IS NULL OR s.item_id<>l.item_id
            OR w.product_id<>l.item_id OR s.work_order_id<>l.work_order_id OR l.quantity<=0)"""),
        ("warehouse_ops.packed_quantity", """SELECT IF(COUNT(*)=2 AND SUM(l.quantity)=4,0,1) FROM mes_wm_package_line l JOIN mes_wm_package p ON p.id=l.package_id
            WHERE p.code IN('TST-PKG-INNER-A','TST-PKG-INNER-B') AND p.deleted=0 AND l.deleted=0"""),
        ("warehouse_ops.package_not_over_stock", """SELECT COUNT(*) FROM (SELECT l.material_stock_id,SUM(l.quantity) packed,MAX(s.quantity) available
            FROM mes_wm_package_line l JOIN mes_wm_package p ON p.id=l.package_id JOIN mes_wm_material_stock s ON s.id=l.material_stock_id
            WHERE p.code LIKE 'TST-PKG-%' AND p.deleted=0 AND l.deleted=0 GROUP BY l.material_stock_id HAVING packed>available) x"""),
        ("warehouse_ops.stocktaking_presence", "SELECT ABS(COUNT(*)-2) FROM mes_wm_stock_taking_task WHERE tenant_id=1 AND deleted=0 AND code IN('TST-COUNT-MATCH','TST-COUNT-VARIANCE')"),
        ("warehouse_ops.stocktaking_states", """SELECT COUNT(*) FROM mes_wm_stock_taking_task t LEFT JOIN mes_wm_stock_taking_plan p ON p.id=t.plan_id AND p.deleted=0 AND p.tenant_id=t.tenant_id
            WHERE t.code IN('TST-COUNT-MATCH','TST-COUNT-VARIANCE') AND t.deleted=0 AND (p.id IS NULL OR p.status<>0 OR t.frozen<>0
            OR (t.code='TST-COUNT-MATCH' AND t.status<>4) OR (t.code='TST-COUNT-VARIANCE' AND t.status<>2))"""),
        ("warehouse_ops.count_results_complete", """SELECT COUNT(*) FROM mes_wm_stock_taking_task_line l JOIN mes_wm_stock_taking_task t ON t.id=l.task_id
            WHERE t.code IN('TST-COUNT-MATCH','TST-COUNT-VARIANCE') AND l.deleted=0 AND NOT EXISTS(
            SELECT 1 FROM mes_wm_stock_taking_task_result r WHERE r.task_id=t.id AND r.line_id=l.id AND r.deleted=0 AND r.tenant_id=t.tenant_id)"""),
        ("warehouse_ops.count_result_consistency", """SELECT COUNT(*) FROM mes_wm_stock_taking_task_result r JOIN mes_wm_stock_taking_task t ON t.id=r.task_id
            LEFT JOIN mes_wm_stock_taking_task_line l ON l.id=r.line_id AND l.task_id=r.task_id AND l.tenant_id=r.tenant_id AND l.deleted=0
            WHERE t.code IN('TST-COUNT-MATCH','TST-COUNT-VARIANCE') AND r.deleted=0 AND (l.id IS NULL OR r.item_id<>l.item_id
            OR NOT(r.batch_id<=>l.batch_id) OR r.material_stock_id<>l.material_stock_id OR r.quantity<>l.quantity OR r.taking_quantity<>l.taking_quantity
            OR r.warehouse_id<>l.warehouse_id OR r.location_id<>l.location_id OR r.area_id<>l.area_id OR r.taking_quantity<0)"""),
        ("warehouse_ops.completed_count_no_difference", """SELECT COUNT(*) FROM mes_wm_stock_taking_task_line l JOIN mes_wm_stock_taking_task t ON t.id=l.task_id
            WHERE t.code='TST-COUNT-MATCH' AND l.deleted=0 AND (l.quantity<>l.taking_quantity OR l.status<>1)"""),
        ("warehouse_ops.active_count_one_gain", """SELECT IF(COUNT(*)=1 AND SUM(l.taking_quantity-l.quantity)=1,0,1)
            FROM mes_wm_stock_taking_task_line l JOIN mes_wm_stock_taking_task t ON t.id=l.task_id
            WHERE t.code='TST-COUNT-VARIANCE' AND l.deleted=0 AND l.status=2"""),
        ("warehouse_ops.report_published_version", """SELECT COUNT(*) FROM mes_report_definition WHERE id IN(980001,980002)
            AND (published_version<>1 OR published_version IS NULL OR (id=980001 AND latest_version<>2) OR (id=980002 AND latest_version<>1))"""),
    ]
    result = ctx.ids.get("warehouse_ops", {})
    if result.get("stockSnapshotBefore"):
        data = json.dumps(result["stockSnapshotBefore"], separators=(",", ":"))
        if "'" in data:
            raise ValueError("Unexpected quote in numeric inventory snapshot")
        checks.append(("warehouse_ops.inventory_snapshot_unchanged", f"""SELECT COUNT(*) FROM JSON_TABLE('{data}','$[*]'
            COLUMNS(id BIGINT PATH '$.id',q DECIMAL(24,6) PATH '$.quantity',frozen INT PATH '$.frozen',holdflag INT PATH '$.hold')) j
            LEFT JOIN mes_wm_material_stock s ON s.id=j.id AND s.tenant_id=1 AND s.deleted=0
            WHERE s.id IS NULL OR s.quantity<>j.q OR s.frozen<>j.frozen OR s.quality_hold<>j.holdflag"""))
        expected = int(result["transactionCountAfter"])
        checks.append(("warehouse_ops.no_inventory_posting", f"SELECT ABS(COUNT(*)-{expected}) FROM mes_wm_transaction WHERE tenant_id=1 AND deleted=0"))
    return checks
