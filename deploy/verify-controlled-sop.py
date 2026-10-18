#!/usr/bin/env python3
"""Real HTTP acceptance for controlled SOP; creates isolated, run-labelled fixtures.

Requires the local MES seed plus the controlled SOP migration and rebuilt backend.
Credentials come from .runtime/secrets.local.json and are never printed.
Each invocation uses a fresh run id. Repeating a successful --run-id verifies its
retained immutable snapshot and disabled-version gates without repeating writes.
Example: python -X utf8 deploy/verify-controlled-sop.py --run-id sop-20261018
"""
import argparse
import base64
from datetime import datetime, timedelta
import json
from pathlib import Path
import re
import sys
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[1]


def require(value, message):
    if not value:
        raise RuntimeError(message)


class Api:
    def __init__(self, base, tenant):
        self.base = base.rstrip("/")
        self.headers = {"Content-Type": "application/json", "tenant-id": str(tenant)}
        self.calls = 0

    def call(self, method, path, body=None, expected=0, **params):
        url = self.base + path + ("?" + urlencode(params) if params else "")
        data = None if body is None else json.dumps(body).encode("utf-8")
        self.calls += 1
        try:
            with urlopen(Request(url, data=data, headers=self.headers, method=method), timeout=45) as response:
                result = json.load(response)
        except HTTPError as error:
            raise RuntimeError(f"{method} {path}: HTTP {error.code}") from None
        except URLError:
            raise RuntimeError(f"{method} {path}: backend unavailable") from None
        require(result.get("code") == expected, f"{method} {path}: expected code {expected}, got {result.get('code')}")
        return result.get("data")

    def get(self, path, **params):
        return self.call("GET", path, **params)

    def post(self, path, body, expected=0):
        return self.call("POST", path, body, expected=expected)

    def put(self, path, expected=0, **params):
        return self.call("PUT", path, expected=expected, **params)

    def upload_photo(self):
        boundary = "----SopAcceptanceImageBoundary"
        png = base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+j9ZkAAAAASUVORK5CYII=")
        body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"sop-acceptance.png\"\r\nContent-Type: image/png\r\n\r\n".encode()
                + png + f"\r\n--{boundary}--\r\n".encode())
        headers = {**self.headers, "Content-Type": "multipart/form-data; boundary=" + boundary}
        self.calls += 1
        try:
            with urlopen(Request(self.base + "/infra/file/upload", data=body, headers=headers, method="POST"), timeout=45) as response:
                result = json.load(response)
        except (HTTPError, URLError):
            raise RuntimeError("Acceptance photo upload failed") from None
        require(result.get("code") == 0 and isinstance(result.get("data"), str), "Photo upload must return a file URL")
        return result["data"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", default="http://127.0.0.1:48080/admin-api")
    parser.add_argument("--tenant", type=int, default=1)
    parser.add_argument("--run-id", default="sop-" + datetime.now().strftime("%Y%m%d-%H%M%S"))
    parser.add_argument("--template-item", type=int, default=900103)
    parser.add_argument("--template-workstation", type=int, default=900001)
    parser.add_argument("--bom-item", type=int, default=900101)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    require(re.fullmatch(r"[A-Za-z0-9_-]{1,35}", args.run_id), "run-id must contain 1–35 safe characters")
    output = args.output or ROOT / ".runtime" / ("controlled-sop-" + args.run_id + ".json")
    previous = json.loads(output.read_text(encoding="utf-8")) if output.exists() else None
    require(previous is None or previous.get("result") == "passed", "Partial run retained: use a fresh run-id")
    secrets = json.loads((ROOT / ".runtime/secrets.local.json").read_text(encoding="utf-8-sig"))
    api = Api(args.base, args.tenant)
    login = api.post("/system/auth/login", {"username": secrets["AdminUsername"], "password": secrets["AdminPassword"]})
    api.headers["Authorization"] = "Bearer " + login["accessToken"]
    if previous:
        ids = previous["ids"]
        api.call("GET", "/mes/sop/task", expected=1047110004, taskId=ids["taskA"])
        versions = api.get("/mes/sop/version/list", documentId=ids["document"])
        require(all(row["status"] == "DISABLED" for row in versions), "Retained versions must remain disabled")
        print(json.dumps({"result": "passed", "repeatReadOnly": True, "runId": args.run_id, "calls": api.calls}))
        return
    report = {"runId": args.run_id, "result": "running", "ids": {}, "checks": [], "retainedFixtures": True}

    def save():
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

    def check(label, condition=True):
        require(condition, label)
        report["checks"].append({"check": label, "passed": True})
        save()

    try:
        now = datetime.now().replace(microsecond=0)
        prefix = "SOP-" + args.run_id
        template = api.get("/mes/md/item/get", id=args.template_item)
        item = api.post("/mes/md/item/create", {"code": prefix + "-ITEM", "name": prefix + "验收产品",
                "unitMeasureId": template["unitMeasureId"], "itemTypeId": template["itemTypeId"], "batchFlag": False,
                "safeStockFlag": False, "highValue": False, "remark": "Controlled SOP isolated acceptance fixture"})
        api.post("/mes/md/product-bom/create", {"itemId": item, "bomItemId": args.bom_item, "quantity": 1, "status": 0})
        api.put("/mes/md/item/update-status", id=item, status=0)
        process = api.post("/mes/pro/process/create", {"code": prefix + "-PROC", "name": prefix + "验收工序", "status": 0})
        route = api.post("/mes/pro/route/create", {"code": prefix + "-ROUTE", "name": prefix + "验收路线"})
        api.post("/mes/pro/route-process/create", {"routeId": route, "processId": process, "sort": 1,
                "keyFlag": True, "checkFlag": False, "linkType": 0, "prepareTime": 0, "waitTime": 0})
        api.post("/mes/pro/route-product/create", {"routeId": route, "itemId": item, "quantity": 1, "productionTime": 1, "timeUnitType": "MINUTE"})
        api.post("/mes/pro/route-product-bom/create", {"routeId": route, "processId": process, "productId": item, "itemId": args.bom_item, "quantity": 1})
        api.put("/mes/pro/route/update-status", id=route, status=0)
        workstation_template = api.get("/mes/md-workstation/get", id=args.template_workstation)
        workstation = api.post("/mes/md-workstation/create", {"code": prefix + "-ST", "name": prefix + "验收工位",
                "workshopId": workstation_template["workshopId"], "processId": process, "status": 0,
                **{key: workstation_template.get(key) for key in ("warehouseId", "locationId", "areaId")}})
        order = api.post("/mes/pro/work-order/create", {"code": prefix + "-WO", "name": prefix + "验收工单",
                "type": 1, "orderSourceType": 2, "productId": item, "quantity": 4,
                "requestDate": (now + timedelta(days=7)).isoformat(timespec="seconds")})
        api.put("/mes/pro/work-order/confirm", id=order)
        task_body = {"workOrderId": order, "workstationId": workstation, "routeId": route, "processId": process,
                "itemId": item, "quantity": 1, "startTime": now.isoformat(), "duration": 1,
                "endTime": (now + timedelta(hours=8)).isoformat(), "remark": "Controlled SOP acceptance"}
        task_a = api.post("/mes/pro/task/create", task_body)
        report["ids"].update({"item": item, "process": process, "route": route, "workstation": workstation, "order": order, "taskA": task_a})
        check("legacy_task_without_document_is_compatible", api.get("/mes/sop/task", taskId=task_a)["required"] is False)
        doc = api.post("/mes/sop/document/create", {"code": prefix + "-DOC", "name": prefix + "受控SOP", "itemId": item, "processId": process})
        report["ids"]["document"] = doc
        api.call("GET", "/mes/sop/task", expected=1047110003, taskId=task_a)
        api.post("/mes/pro/task/create", task_body, expected=1047110003)
        check("controlled_document_without_release_blocks_read_and_dispatch")
        version_body = {"documentId": doc, "versionNo": "A", "title": "验收规程A", "modelUrl": "", "attachments": [], "steps": [
            {"title": "扭矩确认", "instruction": "按要求拧紧并上传现场照片", "required": True, "minViewSeconds": 2,
             "photoRequired": True, "attachments": [], "parameters": [{"key": "torque", "label": "扭矩", "required": True, "min": 5, "max": 10, "unit": "Nm"}]},
            {"title": "可选外观检查", "instruction": "检查外观", "required": False, "minViewSeconds": 0, "photoRequired": False, "attachments": [], "parameters": []}]}
        version_a = api.post("/mes/sop/version/save", version_body)
        report["ids"]["versionA"] = version_a
        api.post("/mes/sop/version/submit", {"id": version_a})
        api.post("/mes/sop/version/save", {**version_body, "id": version_a, "title": "非法修改"}, expected=1047110002)
        api.post("/mes/sop/version/approve", {"id": version_a})
        api.post("/mes/sop/version/publish", {"id": version_a})
        pkg = api.get("/mes/sop/task", taskId=task_a)
        check("task_pins_published_version", pkg["versionId"] == version_a and pkg["steps"][0]["title"] == "扭矩确认")
        step = pkg["steps"][0]["id"]
        feedback = api.post("/mes/pro/feedback/create", {"code": prefix + "-FB", "type": 1, "workstationId": workstation,
                "routeId": route, "processId": process, "workOrderId": order, "taskId": task_a, "itemId": item,
                "scheduledQuantity": 1, "feedbackQuantity": 1, "qualifiedQuantity": 1, "unqualifiedQuantity": 0,
                "uncheckQuantity": 0, "laborScrapQuantity": 0, "materialScrapQuantity": 0, "otherScrapQuantity": 0,
                "feedbackUserId": login["userId"], "approveUserId": login["userId"], "feedbackTime": now.isoformat()})
        report["ids"]["feedback"] = feedback
        api.put("/mes/pro/feedback/submit", id=feedback, expected=1047110005)
        body = {"taskId": task_a, "stepId": step, "parameters": {"torque": 7}, "photos": [], "operatorId": 999999}
        api.post("/mes/sop/task/confirm", body, expected=1047110006)
        api.post("/mes/sop/task/view", body)
        api.post("/mes/sop/task/view", body)
        api.post("/mes/sop/task/confirm", body, expected=1047110006)
        time.sleep(2.2)
        api.post("/mes/sop/task/confirm", body, expected=1047110001)
        photo = api.upload_photo()
        api.post("/mes/sop/task/confirm", {**body, "photos": [photo], "parameters": {"torque": 11}}, expected=1047110001)
        api.post("/mes/sop/task/confirm", {**body, "photos": [photo]})
        api.post("/mes/sop/task/confirm", {**body, "parameters": {"torque": 999}})
        records = api.get("/mes/sop/task", taskId=task_a)["records"]
        check("server_timing_parameters_photos_and_idempotent_identity", len(records) == 1 and records[0]["confirmedBy"] == login["userId"]
              and json.loads(records[0]["parametersJson"])["torque"] == 7)
        api.put("/mes/pro/feedback/submit", id=feedback)
        check("real_feedback_submit_accepts_completed_mandatory_steps")
        version_b = api.post("/mes/sop/version/save", {**version_body, "versionNo": "B", "title": "验收规程B"})
        report["ids"]["versionB"] = version_b
        for action in ("submit", "approve", "publish"):
            api.post("/mes/sop/version/" + action, {"id": version_b})
        check("new_release_preserves_task_snapshot", api.get("/mes/sop/task", taskId=task_a)["versionId"] == version_a)
        task_b = api.post("/mes/pro/task/create", task_body)
        report["ids"]["taskB"] = task_b
        check("new_task_uses_new_release", api.get("/mes/sop/task", taskId=task_b)["versionId"] == version_b)
        api.post("/mes/sop/version/disable", {"id": version_a, "comment": "验收停用已替代版本"})
        api.call("GET", "/mes/sop/task", expected=1047110004, taskId=task_a)
        api.put("/mes/pro/feedback/approve", id=feedback, expected=1047110004)
        api.post("/mes/sop/version/disable", {"id": version_b, "comment": "验收结束，停用专用规程"})
        api.post("/mes/pro/task/create", task_body, expected=1047110003)
        check("disabled_versions_block_execution_dispatch_and_feedback_approval")
        report["result"] = "passed"
        report["calls"] = api.calls
        report["note"] = "Isolated fixture masters/tasks, disabled versions and one submitted feedback retained. No inventory posting or feedback approval performed."
        save()
        print(json.dumps({"result": "passed", "runId": args.run_id, "checks": len(report["checks"]), "report": str(output)}, ensure_ascii=False))
    except Exception as error:
        report["result"] = "failed"
        report["error"] = str(error)
        save()
        raise


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(json.dumps({"result": "failed", "error": str(error)}, ensure_ascii=False), file=sys.stderr)
        sys.exit(1)
