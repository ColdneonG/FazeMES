#!/usr/bin/env python3
"""Real HTTP reservation race, protected stock issue and release regression.

Creates an isolated material/product with ten units through normal receipt APIs.
No SQL or existing order changes. Run after migration and backend restart.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta
from decimal import Decimal
import importlib.util
import json
from pathlib import Path
import re
import uuid

ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location("flow", ROOT / "deploy/verify-mes-flow.py")
flow = importlib.util.module_from_spec(spec)
spec.loader.exec_module(flow)

def run(args, report):
    api = flow.MesApi(args.base_url, 1)
    secrets = json.loads((ROOT / ".runtime/secrets.local.json").read_text(encoding="utf-8-sig"))
    login = api.post("/system/auth/login", {"username": secrets["AdminUsername"], "password": secrets["AdminPassword"]})
    api.headers["Authorization"] = "Bearer " + login["accessToken"]
    del secrets, login
    now = datetime.now().replace(microsecond=0)
    when = now.isoformat()
    ids = report["ids"]
    def code(label): return "KIT-" + label + "-" + args.run_id
    def check(label, condition, **evidence):
        flow.require(condition, label)
        report["checks"].append({"check": label, "passed": True, **evidence})
    def create(label, path, data):
        ids[label] = api.post(path + "/create", data)
        return ids[label]
    def reject(label, call, expected):
        try: call()
        except RuntimeError as error:
            match = re.search(r"code=(\d+),", str(error))
            flow.require(match and int(match[1]) == expected, str(error))
            check(label, True, businessCode=expected)
            return
        raise RuntimeError(label + ": expected business rejection")
    raw_model = api.get("/mes/md/item/get", id=900101)
    product_model = api.get("/mes/md/item/get", id=900103)
    def item(label, model):
        return create(label, "/mes/md/item", {"code": code(label), "name": "齐套回归" + label + args.run_id,
            "unitMeasureId": model["unitMeasureId"], "itemTypeId": model["itemTypeId"],
            "safeStockFlag": False, "batchFlag": False, "highValue": False})
    raw = item("RAW", raw_model)
    api.put("/mes/md/item/update-status", id=raw, status=0)
    product = item("PRODUCT", product_model)
    create("bom", "/mes/md/product-bom", {"itemId": product, "bomItemId": raw, "quantity": 1, "status": 0})
    api.put("/mes/md/item/update-status", id=product, status=0)
    receipt = create("receipt", "/mes/wm/misc-receipt", {"code": code("RECEIPT"), "name": "齐套隔离库存", "type": 1, "receiptDate": when})
    create("receiptLine", "/mes/wm/misc-receipt-line", {"receiptId": receipt, "itemId": raw, "quantity": 10,
        "warehouseId": 900001, "locationId": 900001, "areaId": 900001})
    api.put("/mes/wm/misc-receipt/submit", id=receipt)
    api.put("/mes/wm/misc-receipt/finish", id=receipt)
    def stocks(warehouse=900001, order=None):
        params = {"itemId": raw, "warehouseId": warehouse}
        if order: params["workOrderId"] = order
        return api.page("/mes/wm/material-stock", **params)
    def quantity(warehouse=900001, order=None): return sum((flow.dec(s["quantity"]) for s in stocks(warehouse, order)), Decimal(0))
    for label in ("A", "B"):
        order = create("order" + label, "/mes/pro/work-order", {"code": code(label), "name": "齐套并发工单" + label,
            "type": 1, "orderSourceType": 2, "productId": product, "quantity": 10,
            "requestDate": (now + timedelta(days=7)).isoformat()})
        api.put("/mes/pro/work-order/confirm", id=order)
    orders = [ids["orderA"], ids["orderB"]]
    def reserve(order): return api.call("POST", "/mes/pro/kitting/reserve", workOrderId=order)
    def analyze(order): return api.get("/mes/pro/kitting/analyze", workOrderId=order)
    def ledger(order): return api.get("/mes/pro/kitting/reservations", workOrderId=order)
    def allocated(order): return sum((flow.dec(r["quantity"]) for r in ledger(order)), Decimal(0))
    with ThreadPoolExecutor(max_workers=2) as pool: list(pool.map(reserve, orders))
    amounts = [allocated(order) for order in orders]
    check("concurrent_orders_never_overbook", sorted(amounts) == [0, 10], allocations=amounts)
    winner = orders[amounts.index(10)]
    loser = next(order for order in orders if order != winner)
    ids.update(winner=winner, loser=loser)
    check("reservation_does_not_change_physical_stock", quantity() == 10)
    reserve(winner)
    check("repeat_reserve_is_idempotent", allocated(winner) == 10)
    check("other_order_shortage_is_real", analyze(loser)["materials"][0]["shortageQuantity"] == 10)
    api.call("PUT", "/mes/pro/kitting/followup", {"workOrderId": loser, "itemId": raw,
        "trackingStatus": "ARRIVED", "expectedDate": now.date().isoformat(), "remark": "跟进状态不等于库存入账"})
    check("arrival_followup_does_not_fabricate_stock", not analyze(loser)["ready"] and allocated(loser) == 0)
    bom = api.get("/mes/pro/work-order-bom/list-by-work-order-id", workOrderId=winner)
    if bom:
        reject("confirmed_order_bom_is_frozen", lambda: api.call("PUT", "/mes/pro/work-order-bom/update",
            {"id": bom[0]["id"], "workOrderId": winner, "itemId": raw, "quantity": 1}), 1047100007)
    source = stocks()[0]
    misc = create("miscIssue", "/mes/wm/misc-issue", {"code": code("MISC"), "name": "验证预占不能绕过", "type": 1, "issueDate": when})
    create("miscLine", "/mes/wm/misc-issue-line", {"issueId": misc, "itemId": raw, "materialStockId": source["id"],
        "quantity": 1, "warehouseId": source["warehouseId"], "locationId": source["locationId"], "areaId": source["areaId"]})
    api.put("/mes/wm/misc-issue/submit", id=misc)
    reject("misc_outbound_cannot_take_reserved_stock", lambda: api.put("/mes/wm/misc-issue/finish", id=misc), 1047100003)
    check("rejected_outbound_rolls_back_stock_and_reservation", quantity() == 10 and allocated(winner) == 10)
    api.call("POST", "/mes/pro/kitting/release", workOrderId=winner)
    api.call("POST", "/mes/pro/kitting/release", workOrderId=winner)
    check("release_is_idempotent", allocated(winner) == 0 and quantity() == 10)
    reserve(loser)
    check("released_stock_can_be_reserved_by_other_order", allocated(loser) == 10)
    api.put("/mes/pro/work-order/cancel", id=winner)
    check("cancel_never_releases_other_order", allocated(loser) == 10)
    issue = create("productionIssue", "/mes/wm/product-issue", {"code": code("ISSUE"), "name": "预占实际领料", "workOrderId": loser, "requiredTime": when})
    line = create("productionLine", "/mes/wm/product-issue-line", {"issueId": issue, "itemId": raw, "quantity": 4})
    api.put("/mes/wm/product-issue/submit", id=issue)
    create("productionDetail", "/mes/wm/product-issue-detail", {"issueId": issue, "lineId": line, "itemId": raw,
        "materialStockId": source["id"], "quantity": 4, "warehouseId": source["warehouseId"],
        "locationId": source["locationId"], "areaId": source["areaId"]})
    api.put("/mes/wm/product-issue/stock", id=issue)
    api.put("/mes/wm/product-issue/finish", id=issue)
    row = ledger(loser)[0]
    check("own_issue_consumes_reservation_once", flow.dec(row["quantity"]) == 6 and flow.dec(row["consumedQuantity"]) == 4)
    check("physical_and_wip_balances_remain_conserved", quantity() == 6 and quantity(900002, loser) == 4)
    check("net_issued_reduces_remaining_requirement", flow.dec(analyze(loser)["materials"][0]["outstandingQuantity"]) == 6)
    api.put("/mes/pro/work-order/cancel", id=loser)
    check("cancel_releases_only_remaining_allocation", allocated(loser) == 0 and quantity() == 6)
    events = api.get("/mes/pro/kitting/history", workOrderId=loser)
    check("ledger_history_contains_reserve_issue_release", {r["action"] for r in events} >= {"RESERVE", "ISSUE", "RELEASE"})
    report["apiCalls"] = api.calls

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:48080/admin-api")
    parser.add_argument("--run-id", default=datetime.now().strftime("%m%d%H%M") + "-" + uuid.uuid4().hex[:4])
    args = parser.parse_args()
    report = {"runId": args.run_id, "ids": {}, "checks": [], "status": "running"}
    try:
        run(args, report)
        report["status"] = "passed"
    except Exception as error:
        report.update(status="failed", error=str(error))
        raise
    finally:
        target = ROOT / ".runtime" / ("kitting-acceptance-" + args.run_id + ".json")
        target.write_text(json.dumps(report, ensure_ascii=False, indent=2, default=str), encoding="utf-8")
        print(json.dumps({"status": report["status"], "checks": len(report["checks"]), "report": str(target)}, ensure_ascii=False))
