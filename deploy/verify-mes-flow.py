#!/usr/bin/env python3
"""Exercise the local MES business flow through real authenticated HTTP APIs.

Requires database/init/mes_seed.sql and a running backend. No direct SQL access.
Each default invocation creates a unique run; --run-id resumes that run without
repeating an approved material issue or production feedback. Output is JSON and
never includes authentication tokens. Set YUDAO_LOCAL_ADMIN_PASSWORD to use a
password other than the local demo default.

Example: python -X utf8 deploy/verify-mes-flow.py --quantity 50
"""

import argparse
from datetime import datetime, timedelta, timezone
from decimal import Decimal
import json
import os
from pathlib import Path
import re
import sys
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen


class MesApi:
    def __init__(self, base, tenant):
        self.base = base.rstrip("/")
        self.headers = {"Content-Type": "application/json", "tenant-id": str(tenant)}
        self.calls = 0

    def call(self, method, path, payload=None, **params):
        url = self.base + path + ("?" + urlencode(params) if params else "")
        data = None if payload is None else json.dumps(payload).encode("utf-8")
        try:
            with urlopen(Request(url, data=data, headers=self.headers, method=method), timeout=60) as response:
                result = json.load(response)
        except HTTPError as error:
            raise RuntimeError(f"{method} {path}: HTTP {error.code}: {error.read().decode('utf-8')[:1000]}") from error
        except URLError as error:
            raise RuntimeError(f"{method} {path}: {error.reason}") from error
        self.calls += 1
        if result.get("code") != 0:
            raise RuntimeError(f"{method} {path}: code={result.get('code')}, {result.get('msg')}")
        return result.get("data")

    def get(self, path, **params):
        return self.call("GET", path, **params)

    def post(self, path, payload):
        return self.call("POST", path, payload)

    def put(self, path, **params):
        return self.call("PUT", path, **params)

    def page(self, path, **params):
        result = self.get(path + "/page", pageNo=1, pageSize=100, **params)
        require(result["total"] <= 100, f"Unexpectedly many rows returned from {path}")
        return result["list"]

    def exact_code(self, path, code):
        matches = [row for row in self.page(path, code=code) if row["code"] == code]
        require(len(matches) <= 1, f"Duplicate document code {code}")
        return matches[0] if matches else None


def dec(value):
    return Decimal(str(value or 0))


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def stock_snapshot(api):
    totals = {}
    for warehouse, name in ((900001, "physical"), (900002, "wip")):
        rows = api.page("/mes/wm/material-stock", warehouseId=warehouse)
        for item, label in ((900101, "blank"), (900102, "bolt"), (900103, "product")):
            totals[name + "_" + label] = sum((dec(row["quantity"]) for row in rows if row["itemId"] == item), Decimal(0))
    return totals


def verify_delta(before, after, expected, label, report):
    actual = {key: after[key] - before[key] for key in before}
    wanted = {key: dec(expected.get(key)) for key in before}
    require(actual == wanted, f"{label} stock delta mismatch: actual={actual}, expected={wanted}")
    report["checks"].append({"check": label, "passed": True, "stockDelta": actual})


def ensure_task(api, order_id, process, quantity, when):
    rows = api.page("/mes/pro/task", workOrderId=order_id, processId=process)
    require(len(rows) <= 1, f"More than one task for work order {order_id}, process {process}")
    if rows:
        require(dec(rows[0]["quantity"]) == quantity, "Existing task quantity does not match --quantity")
        return rows[0]["id"]
    return api.post("/mes/pro/task/create", {
        "workOrderId": order_id, "workstationId": process, "routeId": 900001,
        "processId": process, "itemId": 900103, "quantity": quantity,
        "startTime": when.isoformat(timespec="seconds"), "duration": 1,
        "endTime": (when + timedelta(days=1)).isoformat(timespec="seconds"),
        "colorCode": "#2563eb", "remark": "Local HTTP acceptance flow",
    })


def ensure_issue(api, order_id, code, quantity, when, report):
    path = "/mes/wm/product-issue"
    issue = api.exact_code(path, code)
    if issue is None:
        issue_id = api.post(path + "/create", {
            "code": code, "name": "本地验收生产领料", "workOrderId": order_id,
            "workstationId": 900001, "requiredTime": when.isoformat(timespec="seconds"),
            "remark": "Local HTTP acceptance flow",
        })
        issue = api.get(path + "/get", id=issue_id)
    issue_id = issue["id"]
    require(issue["workOrderId"] == order_id, "Issue belongs to a different work order")
    require(issue["status"] in (0, 2, 3, 4), "Issue is in an unsupported state")
    items = ((900101, quantity), (900102, quantity * 4))
    if issue["status"] == 0:
        lines = api.get(path + "-line/list-by-issue", issueId=issue_id)
        for item_id, amount in items:
            existing = [line for line in lines if line["itemId"] == item_id]
            require(len(existing) <= 1, "Duplicate issue item")
            if not existing:
                api.post(path + "-line/create", {"issueId": issue_id, "itemId": item_id, "quantity": amount})
            else:
                require(dec(existing[0]["quantity"]) == amount, "Existing issue line quantity mismatch")
        api.put(path + "/submit", id=issue_id)
        issue["status"] = 2
    if issue["status"] == 2:
        lines = api.get(path + "-line/list-by-issue", issueId=issue_id)
        for line in lines:
            details = api.get(path + "-detail/list-by-line", lineId=line["id"])
            remaining = dec(line["quantity"]) - sum((dec(row["quantity"]) for row in details), Decimal(0))
            require(remaining >= 0, "Issue details exceed line quantity")
            if remaining == 0:
                continue
            stocks = api.page("/mes/wm/material-stock", warehouseId=900001, itemId=line["itemId"])
            for stock in stocks:
                available = max(Decimal(0), dec(stock["quantity"]))
                amount = min(remaining, available)
                if amount <= 0:
                    continue
                api.post(path + "-detail/create", {
                    "lineId": line["id"], "issueId": issue_id, "itemId": line["itemId"],
                    "materialStockId": stock["id"], "quantity": float(amount),
                    "batchId": stock.get("batchId"), "batchCode": stock.get("batchCode"),
                    "warehouseId": stock["warehouseId"], "locationId": stock["locationId"], "areaId": stock["areaId"],
                })
                remaining -= amount
                if remaining == 0:
                    break
            require(remaining == 0, f"Insufficient physical stock for item {line['itemId']}")
        require(api.get(path + "/check-quantity", id=issue_id) is True, "Issue picking quantities do not balance")
        api.put(path + "/stock", id=issue_id)
        issue["status"] = 3
    if issue["status"] == 3:
        before = stock_snapshot(api)
        api.put(path + "/finish", id=issue_id)
        after = stock_snapshot(api)
        verify_delta(before, after, {"physical_blank": -quantity, "physical_bolt": -quantity * 4,
                                     "wip_blank": quantity, "wip_bolt": quantity * 4}, "material_issue", report)
    require(api.get(path + "/get", id=issue_id)["status"] == 4, "Material issue did not finish")
    return issue_id


def ensure_feedback(api, order_id, task_id, process, code, quantity, user_id, when, report):
    path = "/mes/pro/feedback"
    feedback = api.exact_code(path, code)
    if feedback is None:
        feedback_id = api.post(path + "/create", {
            "code": code, "type": 1, "workstationId": process, "routeId": 900001,
            "processId": process, "workOrderId": order_id, "taskId": task_id, "itemId": 900103,
            "scheduledQuantity": quantity, "feedbackQuantity": quantity, "qualifiedQuantity": quantity,
            "unqualifiedQuantity": 0, "uncheckQuantity": 0, "laborScrapQuantity": 0,
            "materialScrapQuantity": 0, "otherScrapQuantity": 0, "feedbackUserId": user_id,
            "approveUserId": user_id, "feedbackTime": when.isoformat(timespec="seconds"),
            "lotNumber": code, "remark": "Local HTTP acceptance flow",
        })
        feedback = api.get(path + "/get", id=feedback_id)
    feedback_id = feedback["id"]
    require(feedback["workOrderId"] == order_id and feedback["taskId"] == task_id, "Feedback relation mismatch")
    require(dec(feedback["feedbackQuantity"]) == quantity, "Existing feedback quantity mismatch")
    require(feedback["status"] in (0, 2, 4), "Feedback is in an unsupported state")
    if feedback["status"] == 0:
        api.put(path + "/submit", id=feedback_id)
        feedback["status"] = 2
    if feedback["status"] == 2:
        before = stock_snapshot(api)
        require(api.put(path + "/approve", id=feedback_id) is True, "Feedback unexpectedly requires quality inspection")
        after = stock_snapshot(api)
        delta = {"wip_blank": -quantity} if process == 900001 else {"wip_bolt": -quantity * 4, "wip_product": quantity}
        verify_delta(before, after, delta, "cutting_feedback" if process == 900001 else "assembly_feedback", report)
    require(api.get(path + "/get", id=feedback_id)["status"] == 4, "Feedback did not finish")
    return feedback_id


def run(args, report):
    api = MesApi(args.base_url, args.tenant)
    login = api.post("/system/auth/login", {"username": args.username, "password": args.password})
    api.headers["Authorization"] = "Bearer " + login["accessToken"]
    user_id = login["userId"]
    when = datetime.now(timezone(timedelta(hours=8))).replace(tzinfo=None, microsecond=0)
    quantity = args.quantity
    order_path = "/mes/pro/work-order"
    order_code = "VERIFY-WO-" + args.run_id
    order = api.exact_code(order_path, order_code)
    fresh = order is None
    if fresh:
        order_id = api.post(order_path + "/create", {
            "code": order_code, "name": "本地MES完整流程验收", "type": 1, "orderSourceType": 2,
            "productId": 900103, "quantity": quantity, "quantityProduced": 0,
            "quantityChanged": 0, "quantityScheduled": 0, "clientId": 900001,
            "requestDate": (when + timedelta(days=7)).isoformat(timespec="seconds"),
            "remark": "HTTP acceptance run " + args.run_id,
        })
        order = api.get(order_path + "/get", id=order_id)
    order_id = order["id"]
    report["workOrderId"] = order_id
    report["workOrderCode"] = order_code
    require(dec(order["quantity"]) == quantity, "Existing work order quantity does not match --quantity")
    require(order["status"] in (0, 1, 2), "Work order is in an unsupported state")
    boms = api.get("/mes/pro/work-order-bom/list-by-work-order-id", workOrderId=order_id)
    actual_bom = {row["itemId"]: dec(row["quantity"]) for row in boms}
    require(actual_bom == {900101: dec(quantity), 900102: dec(quantity * 4)}, f"Unexpected generated work order BOM: {actual_bom}")
    report["checks"].append({"check": "generated_work_order_bom", "passed": True, "quantities": actual_bom})
    if order["status"] == 0:
        api.put(order_path + "/confirm", id=order_id)
    task_ids = [ensure_task(api, order_id, process, quantity, when + timedelta(days=index))
                for index, process in enumerate((900001, 900002))]
    report["taskIds"] = task_ids
    scheduled = api.get(order_path + "/get", id=order_id)
    require(dec(scheduled["quantityScheduled"]) == quantity,
            "Work order scheduled quantity must count the two process groups exactly once")
    report["checks"].append({"check": "work_order_scheduled_quantity", "passed": True,
                              "quantityScheduled": scheduled["quantityScheduled"]})
    report["issueId"] = ensure_issue(api, order_id, "VERIFY-IS-" + args.run_id, quantity, when, report)
    report["feedbackIds"] = [ensure_feedback(api, order_id, task_id, process,
        "VERIFY-FB" + str(index + 1) + "-" + args.run_id, quantity, user_id, when, report)
        for index, (task_id, process) in enumerate(zip(task_ids, (900001, 900002)))]
    order = api.get(order_path + "/get", id=order_id)
    require(dec(order["quantityProduced"]) == quantity, "Work order produced quantity was not updated exactly once")
    cutting = api.get("/mes/pro/task/get", id=task_ids[0])
    require(dec(cutting["producedQuantity"]) == quantity, "Non-key cutting task progress was not updated")
    require(dec(cutting["qualifyQuantity"]) == quantity and dec(cutting["unqualifyQuantity"]) == 0,
            "Cutting qualified/unqualified quantities mismatch")
    assembly = api.get("/mes/pro/task/get", id=task_ids[1])
    require(dec(assembly["producedQuantity"]) == quantity, "Assembly produced quantity mismatch")
    require(dec(assembly["qualifyQuantity"]) == quantity and dec(assembly["unqualifyQuantity"]) == 0,
            "Assembly qualified/unqualified quantities mismatch")
    report["checks"].append({"check": "production_progress", "passed": True,
                              "workOrderProduced": order["quantityProduced"], "cuttingProduced": cutting["producedQuantity"],
                              "assemblyProduced": assembly["producedQuantity"]})
    gantt = api.get("/mes/pro/task/gantt-list", pageNo=1, pageSize=100, code=order_code)
    require(bool(gantt), "Work order was not returned in the Gantt schedule")
    require(all(dec(row.get("progress")) == 1 for row in gantt), "Gantt progress must be 100% for all three rows")
    report["checks"].append({"check": "gantt_schedule", "passed": True, "rows": len(gantt)})
    if order["status"] == 1:
        api.put(order_path + "/finish", id=order_id)
    order = api.get(order_path + "/get", id=order_id)
    require(order["status"] == 2, "Work order did not finish")
    for task_id in task_ids:
        require(api.get("/mes/pro/task/get", id=task_id)["status"] == 4, "Work order completion did not finish its tasks")
    report["checks"].append({"check": "work_order_and_tasks_finished", "passed": True})
    report["stockAfter"] = stock_snapshot(api)
    report["freshRun"] = fresh
    report["httpCalls"] = api.calls
    report["result"] = "passed"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:48080/admin-api")
    parser.add_argument("--username", default="admin")
    parser.add_argument("--password", default=os.environ.get("YUDAO_LOCAL_ADMIN_PASSWORD", "MesLocal@2026"))
    parser.add_argument("--tenant", type=int, default=1)
    parser.add_argument("--quantity", type=int, default=50)
    parser.add_argument("--run-id", default=datetime.now(timezone(timedelta(hours=8))).strftime("%Y%m%d%H%M%S") + str(time.time_ns() % 1000000).zfill(6))
    parser.add_argument("--output", default=str(Path(__file__).resolve().parent.parent / ".runtime" / "mes-acceptance.json"))
    args = parser.parse_args()
    require(args.quantity > 0, "--quantity must be positive")
    require(re.fullmatch(r"[A-Za-z0-9_-]{1,40}", args.run_id), "--run-id must contain 1-40 letters, digits, underscores, or hyphens")
    report = {"runId": args.run_id, "quantity": args.quantity, "checks": []}
    try:
        run(args, report)
    except Exception as error:
        report["result"] = "failed"
        report["error"] = str(error)
    result = json.dumps(report, ensure_ascii=False, indent=2, default=str)
    Path(args.output).write_text(result + "\n", encoding="utf-8")
    print(result)
    return 0 if report["result"] == "passed" else 1


if __name__ == "__main__":
    sys.exit(main())
