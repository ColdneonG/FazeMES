#!/usr/bin/env python3
"""Real HTTP regression: two orders, shortage rollback, returns and finished receipts.

Uses the existing local sample fixture. Creates clearly labelled acceptance
documents; never resets stock or prints credentials. Run against the upgraded API.
"""
import argparse
from datetime import datetime, timedelta, timezone
from decimal import Decimal
import importlib.util
import json
from pathlib import Path
import re
from threading import Barrier
import uuid
from concurrent.futures import ThreadPoolExecutor

ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location("mes_flow", ROOT / "deploy/verify-mes-flow.py")
flow = importlib.util.module_from_spec(spec)
spec.loader.exec_module(flow)


def run(report, run_id):
    secrets = json.loads((ROOT / ".runtime/secrets.local.json").read_text(encoding="utf-8-sig"))
    api = flow.MesApi("http://127.0.0.1:48080/admin-api", 1)
    login = api.post("/system/auth/login", {"username": secrets["AdminUsername"], "password": secrets["AdminPassword"]})
    api.headers["Authorization"] = "Bearer " + login["accessToken"]
    when = datetime.now(timezone(timedelta(hours=8))).replace(tzinfo=None, microsecond=0)
    q = 10

    def check(name, condition, **evidence):
        flow.require(condition, name)
        report["checks"].append({"check": name, "passed": True, **evidence})

    def business_code(error):
        match = re.search(r"code=(\d+),", str(error))
        return int(match.group(1)) if match else None

    def reject(name, action, expected_code):
        try:
            action()
        except RuntimeError as error:
            # Reject business validation only; connectivity/server errors are not a pass.
            flow.require(business_code(error) == expected_code,
                         f"{name}: expected business code {expected_code}; got {error}")
            report["checks"].append({"check": name, "passed": True, "businessCode": expected_code})
            return
        raise RuntimeError(name + ": expected business rejection")

    def wip(order, item):
        rows = api.page("/mes/wm/material-stock", workOrderId=order, itemId=item, warehouseId=900002)
        flow.require(all(row["workOrderId"] == order and row["itemId"] == item
                         and row["warehouseId"] == 900002 for row in rows),
                     "WIP query returned stock outside the requested order/item/warehouse")
        return rows

    def balance(order, item):
        return sum((flow.dec(row["quantity"]) for row in wip(order, item)), Decimal(0))

    def create_order(label):
        order = api.post("/mes/pro/work-order/create", {
            "code": f"ISO-WO-{label}-{run_id}", "name": "工单库存隔离验收" + label,
            "type": 1, "orderSourceType": 2, "productId": 900103, "quantity": q,
            "clientId": 900001, "requestDate": (when + timedelta(days=7)).isoformat(),
            "remark": "Acceptance " + run_id,
        })
        api.put("/mes/pro/work-order/confirm", id=order)
        tasks = [flow.ensure_task(api, order, process, q, when + timedelta(days=i))
                 for i, process in enumerate((900001, 900002))]
        return order, tasks

    def return_material(order, item, amount, label):
        other = order_b if order == order_a else order_a
        other_before = balance(other, item)
        own_before = balance(order, item)
        inventory_before = flow.stock_snapshot(api)
        source = next(row for row in wip(order, item) if flow.dec(row["quantity"]) >= amount)
        receipt = api.post("/mes/wm/return-issue/create", {
            "code": f"ISO-RT-{label}-{run_id}", "name": "工单隔离退料验收", "workOrderId": order,
            "workstationId": 900001, "type": 1, "returnDate": when.isoformat(),
        })
        line = api.post("/mes/wm/return-issue-line/create", {
            "issueId": receipt, "itemId": item, "materialStockId": source["id"],
            "quantity": amount, "batchId": source.get("batchId"), "batchCode": source.get("batchCode"),
            "rqcCheckFlag": False,
        })
        api.put("/mes/wm/return-issue/submit", id=receipt)
        check(label + "_return_submitted_for_putaway", api.get("/mes/wm/return-issue/get", id=receipt)["status"] == 2)
        api.post("/mes/wm/return-issue-detail/create", {
            "issueId": receipt, "lineId": line, "itemId": item, "materialStockId": source["id"],
            "quantity": amount, "batchId": source.get("batchId"), "batchCode": source.get("batchCode"),
            "warehouseId": 900001, "locationId": 900001, "areaId": 900001,
        })
        api.put("/mes/wm/return-issue/stock", id=receipt)
        check(label + "_return_ready_for_execution", api.get("/mes/wm/return-issue/get", id=receipt)["status"] == 3)
        flow.verify_delta(inventory_before, flow.stock_snapshot(api), {}, label + "_return_does_not_move_stock_before_finish", report)
        api.put("/mes/wm/return-issue/finish", id=receipt)
        item_label = {900101: "blank", 900102: "bolt"}[item]
        flow.verify_delta(inventory_before, flow.stock_snapshot(api),
                          {"physical_" + item_label: amount, "wip_" + item_label: -amount},
                          label + "_return_exact_inventory_delta", report)
        check(label + "_return_finishes_only_its_own_order",
              api.get("/mes/wm/return-issue/get", id=receipt)["status"] == 4
              and balance(order, item) == own_before - amount and balance(other, item) == other_before)
        report.setdefault("returnIds", []).append(receipt)
        return receipt

    def receive_product(order, label):
        inventory_before = flow.stock_snapshot(api)
        source = next(row for row in wip(order, 900103) if flow.dec(row["quantity"]) >= q)
        receipt = api.post("/mes/wm/product-receipt/create", {
            "code": f"ISO-RC-{label}-{run_id}", "name": "工单隔离成品入库验收",
            "workOrderId": order, "receiptDate": when.isoformat(),
        })
        line_data = {"receiptId": receipt, "itemId": 900103, "materialStockId": source["id"],
                     "quantity": q, "batchId": source.get("batchId"), "batchCode": source.get("batchCode")}
        other = order_b if order == order_a else order_a
        other_before = balance(other, 900103)
        other_rows = [row for row in wip(other, 900103) if flow.dec(row["quantity"]) >= q]
        flow.require(label != "A" or other_rows, "A receipt requires positive other-order output for its rejection control")
        if other_rows:
            wrong = other_rows[0]
            reject("product_receipt_rejects_other_work_order", lambda: api.post(
                "/mes/wm/product-receipt-line/create", {**line_data, "materialStockId": wrong["id"],
                  "batchId": wrong.get("batchId"), "batchCode": wrong.get("batchCode")}), 1040703014)
        line = api.post("/mes/wm/product-receipt-line/create", line_data)
        api.put("/mes/wm/product-receipt/submit", id=receipt)
        check(label + "_receipt_submitted_for_putaway", api.get("/mes/wm/product-receipt/get", id=receipt)["status"] == 2)
        api.post("/mes/wm/product-receipt-detail/create", {
            "receiptId": receipt, "lineId": line, "itemId": 900103, "quantity": q,
            "warehouseId": 900001, "locationId": 900001, "areaId": 900001,
        })
        api.put("/mes/wm/product-receipt/stock", id=receipt)
        check(label + "_receipt_ready_for_execution", api.get("/mes/wm/product-receipt/get", id=receipt)["status"] == 3)
        flow.verify_delta(inventory_before, flow.stock_snapshot(api), {}, label + "_receipt_does_not_move_stock_before_finish", report)
        if label == "A":
            barrier = Barrier(2)

            def simultaneous_finish():
                barrier.wait(timeout=15)
                result = api.put("/mes/wm/product-receipt/finish", id=receipt)
                flow.require(result is True, "finish returned a successful envelope without a true result")
                return True

            with ThreadPoolExecutor(max_workers=2) as pool:
                attempts = [pool.submit(simultaneous_finish) for _ in range(2)]
                results = []
                for attempt in attempts:
                    try:
                        results.append({"succeeded": attempt.result() is True})
                    except RuntimeError as error:
                        flow.require(business_code(error) == 1040712005,
                                     f"Concurrent finish expected completed-status rejection; got {error}")
                        results.append({"succeeded": False, "businessCode": 1040712005})
            check("concurrent_finish_has_one_success_and_one_completed_status_rejection",
                  sum(result["succeeded"] for result in results) == 1, attempts=results)
        else:
            api.put("/mes/wm/product-receipt/finish", id=receipt)
        flow.verify_delta(inventory_before, flow.stock_snapshot(api),
                          {"physical_product": q, "wip_product": -q},
                          label + "_receipt_executes_inventory_exactly_once", report)
        check(label + "_receipt_finishes_only_its_own_order",
              api.get("/mes/wm/product-receipt/get", id=receipt)["status"] == 4
              and balance(order, 900103) == 0 and balance(other, 900103) == other_before)
        report.setdefault("receiptIds", []).append(receipt)
        return receipt

    before = flow.stock_snapshot(api)
    flow.require(before["physical_blank"] >= q * 2 + 2 and before["physical_bolt"] >= (q * 2 + 2) * 4,
                 "Insufficient physical fixture stock; need 22 blank units and 88 bolts before creating orders")
    flow.require(all(api.exact_code("/mes/pro/work-order", f"ISO-WO-{label}-{run_id}") is None for label in ("A", "B")),
                 "run-id already exists; this regression is not resumable, use a fresh run-id")
    order_a, tasks_a = create_order("A")
    order_b, tasks_b = create_order("B")
    report["workOrderIds"] = [order_a, order_b]
    flow.ensure_issue(api, order_a, "ISO-IS-A-" + run_id, q, when, report)
    check("issue_stock_belongs_only_to_order_a", balance(order_a, 900101) == q and balance(order_b, 900101) == 0)
    wrong_return = api.post("/mes/wm/return-issue/create", {
        "code": "ISO-WRONG-RETURN-" + run_id, "name": "跨工单退料拒绝验收", "workOrderId": order_b,
        "workstationId": 900001, "type": 1, "returnDate": when.isoformat(),
    })
    a_stock = wip(order_a, 900101)[0]
    reject("return_rejects_other_order_stock", lambda: api.post("/mes/wm/return-issue-line/create", {
        "issueId": wrong_return, "itemId": 900101, "materialStockId": a_stock["id"], "quantity": 1,
        "batchId": a_stock.get("batchId"), "batchCode": a_stock.get("batchCode"), "rqcCheckFlag": False,
    }), 1040703014)
    api.put("/mes/wm/return-issue/cancel", id=wrong_return)
    a_before = balance(order_a, 900101)
    shortage_before = flow.stock_snapshot(api)
    reject("order_b_cannot_consume_order_a_stock", lambda: flow.ensure_feedback(
        api, order_b, tasks_b[0], 900001, "ISO-FB-B1-" + run_id, q, login["userId"], when, report), 1040703001)
    check("shortage_keeps_other_order_stock_unchanged", balance(order_a, 900101) == a_before)
    flow.verify_delta(shortage_before, flow.stock_snapshot(api), {}, "shortage_rolls_back_all_inventory", report)
    rejected_feedback = api.exact_code("/mes/pro/feedback", "ISO-FB-B1-" + run_id)
    check("shortage_keeps_feedback_pending_and_progress_zero", rejected_feedback is not None
          and rejected_feedback["status"] == 2
          and flow.dec(api.get("/mes/pro/task/get", id=tasks_b[0])["producedQuantity"]) == 0
          and flow.dec(api.get("/mes/pro/work-order/get", id=order_b)["quantityProduced"]) == 0)
    return_material(order_a, 900101, 2, "A-RAW")
    check("return_decrements_only_own_wip", balance(order_a, 900101) == q - 2 and balance(order_b, 900101) == 0)
    flow.ensure_issue(api, order_a, "ISO-IS-EXTRA-" + run_id, 2, when, report)
    flow.ensure_issue(api, order_b, "ISO-IS-B-" + run_id, q, when, report)
    a_before = balance(order_a, 900101)
    flow.ensure_feedback(api, order_b, tasks_b[0], 900001, "ISO-FB-B1-" + run_id, q, login["userId"], when, report)
    check("approved_b_does_not_consume_a", balance(order_a, 900101) == a_before and balance(order_b, 900101) == 0)
    for label, order, tasks in (("A", order_a, tasks_a), ("B", order_b, tasks_b)):
        if label == "A":
            flow.ensure_feedback(api, order, tasks[0], 900001, f"ISO-FB-{label}1-{run_id}", q, login["userId"], when, report)
        other = order_b if order == order_a else order_a
        own_bolts_before = balance(order, 900102)
        other_bolts_before = balance(other, 900102)
        other_products_before = balance(other, 900103)
        flow.ensure_feedback(api, order, tasks[1], 900002, f"ISO-FB-{label}2-{run_id}", q, login["userId"], when, report)
        check(label + "_assembly_consumes_and_produces_only_its_own_order",
              balance(order, 900102) == own_bolts_before - q * 4
              and balance(order, 900103) == q
              and balance(other, 900102) == other_bolts_before
              and balance(other, 900103) == other_products_before)
    check("outputs_are_order_scoped", balance(order_a, 900103) == q and balance(order_b, 900103) == q)
    return_material(order_a, 900102, 8, "A-BOLT")
    receive_product(order_a, "A")
    check("receipt_a_preserves_b_output", balance(order_a, 900103) == 0 and balance(order_b, 900103) == q)
    receive_product(order_b, "B")
    for order in (order_a, order_b):
        api.put("/mes/pro/work-order/finish", id=order)
        check("work_order_" + str(order) + "_really_finished", api.get("/mes/pro/work-order/get", id=order)["status"] == 2)
    after = flow.stock_snapshot(api)
    flow.verify_delta(before, after, {"physical_blank": -q * 2, "physical_bolt": -q * 8,
                                    "physical_product": q * 2}, "full_two_order_material_balance", report)
    report["httpCalls"] = api.calls


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--run-id", default=datetime.now(timezone(timedelta(hours=8))).strftime("%Y%m%d%H%M%S") + uuid.uuid4().hex[:4])
    parser.add_argument("--output", default=str(ROOT / ".runtime/mes-isolation-acceptance.json"))
    args = parser.parse_args()
    flow.require(re.fullmatch(r"[A-Za-z0-9-]{1,30}", args.run_id), "run-id must be 1-30 letters, digits or hyphens")
    report = {"runId": args.run_id, "checks": [], "passed": False, "evidenceBoundary": [
        "Creates two new orders against the existing local sample route; consumes 20 blanks and 80 bolts net and adds 20 physical finished products.",
        "The extra issue of 2 blanks also issues 8 bolts; the earlier 2-blank return and later 8-bolt return balance that extra issue.",
        "Two receipt-finish HTTP requests are released from a client barrier together; database wait timing is not independently observed.",
        "HTTP admin regression only; no direct SQL, stock reset, browser or low-permission operator-role claim.",
    ]}
    try:
        run(report, args.run_id)
        report["passed"] = True
    except Exception as error:
        report["error"] = str(error)
    result = json.dumps(report, ensure_ascii=False, indent=2, default=str)
    Path(args.output).parent.mkdir(parents=True, exist_ok=True)
    Path(args.output).write_text(result + "\n", encoding="utf-8")
    print(result)
    raise SystemExit(0 if report["passed"] else 1)
