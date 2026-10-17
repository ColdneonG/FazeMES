#!/usr/bin/env python3
"""Real HTTP quality regression for the upgraded local MES API.

Run only after applying migrations and restarting the backend. Creates labelled,
run-specific fixtures and consumes four units of the sample raw material. Existing
orders, routes and templates are never edited. No SQL, service management or token
output. A run is intentionally not resumed; use a fresh --run-id after a failure.

python -X utf8 deploy/verify-mes-quality.py
python -X utf8 deploy/verify-mes-quality.py --run-id quality-20261017-a
"""

import argparse
from datetime import datetime, timedelta, timezone
from decimal import Decimal
import importlib.util
import json
from pathlib import Path
import re
import sys
import uuid

ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location("mes_flow", ROOT / "deploy/verify-mes-flow.py")
flow = importlib.util.module_from_spec(spec)
spec.loader.exec_module(flow)

FINISHED = 4
UNCHECK = 3
FEEDBACK_SOURCE = 304
SAMPLE_PRODUCT = 900103
SAMPLE_RAW = 900101
PHYSICAL = 900001
WIP = 900002
VENDOR = CLIENT = WORKSHOP = 900001


class QualityRun:
    def __init__(self, args, report):
        self.args, self.report = args, report
        self.api = flow.MesApi(args.base_url, args.tenant)
        self.now = datetime.now(timezone(timedelta(hours=8))).replace(tzinfo=None, microsecond=0)
        self.when = self.now.isoformat(timespec="seconds")
        self.ids = report["ids"]

    def code(self, label):
        return f"QVR-{label}-{self.args.run_id}"

    def check(self, name, condition, **evidence):
        flow.require(condition, name)
        self.report["checks"].append({"check": name, "passed": True, **evidence})

    def create(self, key, path, payload):
        value = self.api.post(path + "/create", payload)
        flow.require(isinstance(value, int) and value > 0, f"{key}: missing created ID")
        self.ids[key] = value
        return value

    def reject(self, name, method, path, payload, expected_code):
        try:
            self.api.call(method, path, payload)
        except RuntimeError as error:
            match = re.search(r"code=(\d+),", str(error))
            # A timeout, missing endpoint, permission error or server exception
            # must never be misreported as a passed business validation.
            flow.require(match and int(match.group(1)) == expected_code,
                         f"{name}: expected business code {expected_code}; got {error}")
            self.check(name, True, businessCode=expected_code)
            return
        raise RuntimeError(name + ": expected business rejection")

    def stock(self, item_id, warehouse, order_id=None):
        query = {"itemId": item_id, "warehouseId": warehouse}
        if order_id is not None:
            query["workOrderId"] = order_id
        return sum((flow.dec(row["quantity"]) for row in self.api.page("/mes/wm/material-stock", **query)), Decimal(0))

    def login(self):
        secrets = json.loads(self.args.secrets_file.read_text(encoding="utf-8-sig"))
        login = self.api.post("/system/auth/login", {
            "username": secrets["AdminUsername"], "password": secrets["AdminPassword"],
        })
        self.api.headers["Authorization"] = "Bearer " + login["accessToken"]
        self.user_id = login["userId"]
        # Do not retain login objects or credentials in the report.
        del secrets, login

    def setup(self):
        api = self.api
        flow.require(api.exact_code("/mes/md/item", self.code("PRODUCT")) is None,
                     "run-id already exists; use a new run-id rather than replaying writes")
        sample = api.get("/mes/md/item/get", id=SAMPLE_PRODUCT)
        flow.require(sample["itemOrProduct"] == "PRODUCT", "sample product fixture missing")
        flow.require(self.stock(SAMPLE_RAW, PHYSICAL) >= 4, "sample raw stock must be at least four units")
        self.raw_before = self.stock(SAMPLE_RAW, PHYSICAL)
        product = self.create("product", "/mes/md/item", {
            "code": self.code("PRODUCT"), "name": "质检回归独立产品 " + self.args.run_id,
            "unitMeasureId": sample["unitMeasureId"], "itemTypeId": sample["itemTypeId"],
            "specification": "HTTP质检回归专用", "safeStockFlag": False, "batchFlag": False,
            "highValue": False, "remark": self.code("RUN"),
        })
        self.create("productBom", "/mes/md/product-bom", {
            "itemId": product, "bomItemId": SAMPLE_RAW, "quantity": 1, "status": 0,
        })
        # Products intentionally start disabled until their BOM is configured.
        api.put("/mes/md/item/update-status", id=product, status=0)
        self.check("independent_product_enabled_after_bom", api.get("/mes/md/item/get", id=product)["status"] == 0)
        process = self.create("process", "/mes/pro/process", {
            "code": self.code("PROCESS"), "name": "质检回归工序 " + self.args.run_id, "status": 0,
        })
        station = self.create("workstation", "/mes/md-workstation", {
            "code": self.code("STATION"), "name": "质检回归工位 " + self.args.run_id,
            "workshopId": WORKSHOP, "processId": process, "status": 0,
        })
        route = self.create("route", "/mes/pro/route", {
            "code": self.code("ROUTE"), "name": "质检回归路线 " + self.args.run_id,
        })
        self.create("routeProcess", "/mes/pro/route-process", {
            "routeId": route, "processId": process, "sort": 1, "keyFlag": True,
            "checkFlag": True, "linkType": 0,
        })
        self.create("routeProduct", "/mes/pro/route-product", {"routeId": route, "itemId": product})
        self.create("routeBom", "/mes/pro/route-product-bom", {
            "routeId": route, "processId": process, "productId": product, "itemId": SAMPLE_RAW, "quantity": 1,
        })
        api.put("/mes/pro/route/update-status", id=route, status=0)
        order = self.create("workOrder", "/mes/pro/work-order", {
            "code": self.code("WO"), "name": "质检回归工单 " + self.args.run_id,
            "type": 1, "orderSourceType": 2, "productId": product, "quantity": 4,
            "clientId": CLIENT, "requestDate": (self.now + timedelta(days=7)).isoformat(),
        })
        api.put("/mes/pro/work-order/confirm", id=order)
        task = self.create("task", "/mes/pro/task", {
            "workOrderId": order, "workstationId": station, "routeId": route, "processId": process,
            "itemId": product, "quantity": 4, "startTime": self.when, "duration": 1,
            "endTime": (self.now + timedelta(hours=8)).isoformat(),
        })
        sop = self.create("sop", "/mes/md/product-sop", {
            "itemId": product, "processId": process, "sort": 1,
            "title": "质检回归工位SOP " + self.args.run_id,
            "description": "确认工位与产品，按检测项标准完成首检和末检；触屏验收使用的独立示例作业指导。",
            "url": "http://127.0.0.1:5173/placeholder-image.svg",
        })
        # A second SOP on this new product belongs to a different process. It
        # must not leak into the selected task's workstation view.
        self.create("otherProcessSop", "/mes/md/product-sop", {
            "itemId": product, "processId": 900001, "sort": 2,
            "title": "不应在当前工位出现的SOP " + self.args.run_id,
            "description": "工序隔离对照样例", "url": "http://127.0.0.1:5173/placeholder-image.svg",
        })
        context = api.get("/mes/pro/workbench/context", taskId=task)
        tasks = api.get("/mes/pro/workbench/tasks", workstationId=station)
        self.check("workstation_api_binds_task_order_product_process",
                   context["task"]["workOrderId"] == order and context["task"]["itemId"] == product
                   and context["task"]["processId"] == process and context["task"]["checkFlag"] is True
                   and [row["id"] for row in tasks] == [task])
        self.check("workstation_context_returns_only_current_product_process_sop",
                   [row["id"] for row in context["sops"]] == [sop]
                   and all(row["itemId"] == product and row["processId"] == process for row in context["sops"]),
                   sopId=sop, image="http://127.0.0.1:5173/placeholder-image.svg")
        for label, qc_type, phase in (("FIRST", 2, 2), ("LAST", 2, 6), ("IQC", 1, None), ("OQC", 3, None)):
            self.template(label, qc_type, phase, sample["unitMeasureId"])
        self.issue_raw()

    def template(self, label, qc_type, phase, unit_id):
        indicator = self.create("indicator" + label, "/mes/qc/indicator", {
            "code": self.code("I-" + label), "name": "回归检测项 " + label + " " + self.args.run_id,
            "type": 1, "resultType": 1, "tool": "示例量具",
        })
        template = self.create("template" + label, "/mes/qc/template", {
            "code": self.code("T-" + label), "name": "回归模板 " + label + " " + self.args.run_id,
            "types": [qc_type], "ipqcTypes": [] if phase is None else [phase], "status": 0,
        })
        self.create("templateItem" + label, "/mes/qc/template/item", {
            "templateId": template, "itemId": self.ids["product"], "quantityCheck": 1,
            "quantityUnqualified": 1, "criticalRate": 0, "majorRate": 100, "minorRate": 100,
        })
        self.create("templateIndicator" + label, "/mes/qc/template/indicator", {
            "templateId": template, "indicatorId": indicator, "checkMethod": "HTTP回归测量",
            "standardValue": 10, "unitMeasureId": unit_id, "thresholdMax": 11, "thresholdMin": 9,
        })

    def issue_raw(self):
        api, ids = self.api, self.ids
        issue = self.create("issue", "/mes/wm/product-issue", {
            "code": self.code("ISSUE"), "name": "质检回归独立领料", "workOrderId": ids["workOrder"],
            "workstationId": ids["workstation"], "requiredTime": self.when,
        })
        line = self.create("issueLine", "/mes/wm/product-issue-line", {
            "issueId": issue, "itemId": SAMPLE_RAW, "quantity": 4,
        })
        api.put("/mes/wm/product-issue/submit", id=issue)
        remaining = Decimal(4)
        for stock in api.page("/mes/wm/material-stock", itemId=SAMPLE_RAW, warehouseId=PHYSICAL):
            amount = min(remaining, max(Decimal(0), flow.dec(stock["quantity"])))
            if amount <= 0:
                continue
            api.post("/mes/wm/product-issue-detail/create", {
                "issueId": issue, "lineId": line, "itemId": SAMPLE_RAW, "materialStockId": stock["id"],
                "quantity": float(amount), "batchId": stock.get("batchId"), "batchCode": stock.get("batchCode"),
                "warehouseId": stock["warehouseId"], "locationId": stock["locationId"], "areaId": stock["areaId"],
            })
            remaining -= amount
            if not remaining:
                break
        flow.require(remaining == 0, "sample stock changed before issue; picking incomplete")
        api.put("/mes/wm/product-issue/stock", id=issue)
        api.put("/mes/wm/product-issue/finish", id=issue)
        self.check("four_raw_units_issued_to_only_this_order",
                   self.stock(SAMPLE_RAW, WIP, ids["workOrder"]) == 4
                   and self.stock(SAMPLE_RAW, PHYSICAL) == self.raw_before - 4)

    def record_results(self, stage, qc_id, label):
        api = self.api
        qc_type = {"iqc": 1, "ipqc": 2, "oqc": 3}[stage]
        shell = api.get("/mes/qc/indicator-result/get-detail", qcId=qc_id, qcType=qc_type)
        indicator = self.ids["indicator" + label]
        self.check(label + "_mobile_indicator_template_is_exact",
                   [row["indicatorId"] for row in shell["items"]] == [indicator])
        result = self.create("result" + label, "/mes/qc/indicator-result", {
            "code": self.code("SAMPLE-" + label), "qcId": qc_id, "qcType": qc_type,
            "items": [{"indicatorId": indicator, "value": "10.25", "remark": self.code("VALUE")}],
        })
        saved = api.get("/mes/qc/indicator-result/get-detail", id=result, qcId=qc_id, qcType=qc_type)
        self.check(label + "_measurement_persisted", len(saved["items"]) == 1
                   and saved["items"][0]["indicatorId"] == indicator
                   and saved["items"][0]["value"] == "10.25")
        return result

    def ipqc(self):
        api, ids = self.api, self.ids
        for index, (label, phase, good, bad) in enumerate((("FIRST", 2, 1, 1), ("LAST", 6, 2, 0)), 1):
            feedback = self.create("feedback" + label, "/mes/pro/feedback", {
                "code": self.code("FB-" + label), "type": 1, "workstationId": ids["workstation"],
                "routeId": ids["route"], "processId": ids["process"], "workOrderId": ids["workOrder"],
                "taskId": ids["task"], "itemId": ids["product"], "scheduledQuantity": 4,
                "feedbackQuantity": 2, "qualifiedQuantity": 0, "unqualifiedQuantity": 0,
                "uncheckQuantity": 2, "laborScrapQuantity": 0, "materialScrapQuantity": 0,
                "otherScrapQuantity": 0, "feedbackUserId": self.user_id, "approveUserId": self.user_id,
                "feedbackTime": self.when, "lotNumber": self.code(label),
            })
            api.put("/mes/pro/feedback/submit", id=feedback)
            approved = api.put("/mes/pro/feedback/approve", id=feedback)
            waiting = api.get("/mes/pro/feedback/get", id=feedback)
            self.check(label + "_approval_waits_for_inspection_without_early_output",
                       approved is False and waiting["status"] == UNCHECK
                       and self.stock(ids["product"], WIP, ids["workOrder"]) == (index - 1) * 2)
            filters = {"qcType": 2, "itemId": ids["product"], "workstationId": ids["workstation"],
                       "workOrderId": ids["workOrder"], "taskId": ids["task"]}
            pending = api.page("/mes/qc/pending-inspect", **filters)
            target = [row for row in pending if row["sourceDocType"] == FEEDBACK_SOURCE and row["sourceDocId"] == feedback]
            self.check(label + "_mobile_pending_filter_finds_its_own_feedback", len(target) == 1
                       and target[0]["itemId"] == ids["product"] and flow.dec(target[0]["quantity"]) == 2)
            wrong = api.page("/mes/qc/pending-inspect", **{**filters, "workOrderId": -1})
            self.check(label + "_mobile_pending_filter_excludes_other_order", not wrong)
            payload = {"code": self.code("IPQC-" + label), "name": "独立" + label + "检验 " + self.args.run_id,
                       "type": phase, "sourceDocType": FEEDBACK_SOURCE, "sourceDocId": feedback,
                       "sourceLineId": target[0]["sourceLineId"], "workOrderId": ids["workOrder"],
                       "taskId": ids["task"], "workstationId": ids["workstation"], "processId": ids["process"],
                       "itemId": ids["product"], "checkQuantity": 2, "qualifiedQuantity": good,
                       "unqualifiedQuantity": bad, "laborScrapQuantity": 0, "materialScrapQuantity": 0,
                       "otherScrapQuantity": bad, "checkResult": 1, "inspectorUserId": self.user_id, "inspectDate": self.when}
            inspection = self.create("ipqc" + label, "/mes/qc/ipqc", payload)
            loaded = api.get("/mes/qc/ipqc/get", id=inspection)
            self.check(label + "_matches_phase_specific_template", loaded["templateId"] == ids["template" + label]
                       and loaded["type"] == phase and loaded["itemId"] == ids["product"])
            filtered = api.page("/mes/qc/ipqc", workstationId=ids["workstation"], workOrderId=ids["workOrder"], taskId=ids["task"])
            self.check(label + "_mobile_inspection_query_keeps_context", inspection in [row["id"] for row in filtered]
                       and all(row["workOrderId"] == ids["workOrder"] and row["taskId"] == ids["task"] for row in filtered))
            self.reject(label + "_cannot_finish_without_measurement", "PUT", "/mes/qc/ipqc/finish?id=" + str(inspection), None, 1040606002)
            self.check(label + "_rejected_finish_keeps_source_pending",
                       api.get("/mes/qc/ipqc/get", id=inspection)["status"] == 0
                       and api.get("/mes/pro/feedback/get", id=feedback)["status"] == UNCHECK
                       and self.stock(ids["product"], WIP, ids["workOrder"]) == (index - 1) * 2)
            self.record_results("ipqc", inspection, label)
            if label == "FIRST":
                self.defect_snapshot(inspection)
            api.put("/mes/qc/ipqc/finish", id=inspection)
            done = api.get("/mes/qc/ipqc/get", id=inspection)
            completed = api.get("/mes/pro/feedback/get", id=feedback)
            task = api.get("/mes/pro/task/get", id=ids["task"])
            order = api.get("/mes/pro/work-order/get", id=ids["workOrder"])
            self.check(label + "_finish_writes_feedback_progress_and_order_wip",
                       done["status"] == FINISHED and completed["status"] == FINISHED
                       and flow.dec(completed["qualifiedQuantity"]) == good
                       and flow.dec(completed["unqualifiedQuantity"]) == bad
                       and flow.dec(completed["uncheckQuantity"]) == 0
                       and flow.dec(task["producedQuantity"]) == index * 2
                       and flow.dec(order["quantityProduced"]) == index * 2
                       and self.stock(ids["product"], WIP, ids["workOrder"]) == index * 2,
                       produced=flow.dec(order["quantityProduced"]), wip=flow.dec(index * 2))
            remaining = api.page("/mes/qc/pending-inspect", **filters)
            self.check(label + "_completed_source_removed_from_pending", not any(row["sourceDocId"] == feedback for row in remaining))
        self.check("first_and_last_use_distinct_templates", ids["templateFIRST"] != ids["templateLAST"])
        task = api.get("/mes/pro/task/get", id=ids["task"])
        self.check("classified_task_totals_match_two_inspections",
                   flow.dec(task["qualifyQuantity"]) == 3 and flow.dec(task["unqualifyQuantity"]) == 1
                   and self.stock(SAMPLE_RAW, WIP, ids["workOrder"]) == 0)

    def defect_snapshot(self, inspection):
        api = self.api
        original_name = "回归常见划痕 " + self.args.run_id
        defect_payload = {"code": self.code("DEFECT"), "name": original_name, "type": 1, "level": 3}
        defect = self.create("commonDefect", "/mes/qc/defect", defect_payload)
        lines = api.page("/mes/qc/ipqc/line", ipqcId=inspection)
        flow.require(len(lines) == 1, "FIRST template must create exactly one inspection line")
        record = self.create("defectRecord", "/mes/qc/defect-record", {
            "qcType": 2, "qcId": inspection, "lineId": lines[0]["id"], "defectId": defect,
            "name": "客户端字段应被服务端快照替换", "level": 1, "quantity": 1,
        })
        saved = api.get("/mes/qc/defect-record/get", id=record)
        self.check("common_defect_selection_copies_server_snapshot",
                   saved["defectId"] == defect and saved["name"] == original_name and saved["level"] == 3)
        api.call("PUT", "/mes/qc/defect/update", {**defect_payload, "id": defect, "name": original_name + "已改名", "level": 2})
        reread = api.get("/mes/qc/defect-record/get", id=record)
        inspection_row = api.get("/mes/qc/ipqc/get", id=inspection)
        self.check("defect_history_survives_master_change", reread["name"] == original_name and reread["level"] == 3
                   and inspection_row["minorQuantity"] == 1 and inspection_row["majorQuantity"] == 0)

    def qc_references(self):
        api, ids = self.api, self.ids
        wrong_vendor = self.create("otherVendor", "/mes/md-vendor", {
            "code": self.code("VENDOR"), "name": "参考约束供应商 " + self.args.run_id, "status": 0,
        })
        wrong_client = self.create("otherClient", "/mes/md-client", {
            "code": self.code("CLIENT"), "name": "参考约束客户 " + self.args.run_id, "type": 1, "status": 0,
        })
        for stage, label, qc_type, party, wrong_party in (("iqc", "IQC", 1, VENDOR, wrong_vendor), ("oqc", "OQC", 3, CLIENT, wrong_client)):
            path = "/mes/qc/" + stage
            base = {"code": self.code(label), "name": "独立参考" + label + " " + self.args.run_id,
                    "itemId": ids["product"], "qualifiedQuantity": 1, "unqualifiedQuantity": 0,
                    "inspectorUserId": self.user_id, "inspectDate": self.when, "checkResult": 1}
            if stage == "iqc":
                base.update(vendorId=party, receivedQuantity=1, receiveDate=self.when)
                doc_path, reference, party_field = "/mes/wm/item-receipt", "iqcId", "vendorId"
                header = {"code": self.code("RCPT"), "name": "独立IQC参考入库", "vendorId": party, "receiptDate": self.when}
                status_error, party_error = 1040705008, 1040705007
            else:
                base.update(clientId=party, outQuantity=1, checkQuantity=1, outDate=self.when)
                doc_path, reference, party_field = "/mes/wm/product-sales", "oqcId", "clientId"
                header = {"code": self.code("SALES"), "name": "独立OQC参考出库", "clientId": party, "salesDate": self.when}
                status_error, party_error = 1040714035, 1040714034
            qc_id = self.create(stage, path, base)
            loaded = api.get(path + "/get", id=qc_id)
            self.check(label + "_matches_own_template", loaded["templateId"] == ids["template" + label])
            self.check(label + "_manual_inspection_has_no_source_links",
                       loaded.get("sourceDocType") in (None, 0)
                       and loaded.get("sourceDocId") is None and loaded.get("sourceLineId") is None,
                       sourceDocType=loaded.get("sourceDocType"))
            self.reject(label + "_draft_reference_rejected", "POST", doc_path + "/create", {**header, reference: qc_id}, status_error)
            self.check(label + "_draft_rejection_did_not_create_document", api.exact_code(doc_path, header["code"]) is None)
            options = api.get(doc_path + "/qc-options", **{party_field: party})
            self.check(label + "_picker_excludes_draft", qc_id not in [row["id"] for row in options])
            self.record_results(stage, qc_id, label)
            api.put(path + "/finish", id=qc_id)
            self.check(label + "_inspection_really_finished", api.get(path + "/get", id=qc_id)["status"] == FINISHED)
            self.reject(label + "_different_party_reference_rejected", "POST", doc_path + "/create",
                        {**header, party_field: wrong_party, reference: qc_id}, party_error)
            good = self.create("header" + label, doc_path, {**header, reference: qc_id})
            stored = api.get(doc_path + "/get", id=good)
            self.check(label + "_completed_same_party_reference_persists", stored[reference] == qc_id
                       and stored[party_field] == party and stored["status"] == 0)
            included = api.get(doc_path + "/qc-options", **{party_field: party})
            excluded = api.get(doc_path + "/qc-options", **{party_field: wrong_party})
            self.check(label + "_picker_filters_completed_same_party", qc_id in [row["id"] for row in included]
                       and qc_id not in [row["id"] for row in excluded])
            api.call("PUT", doc_path + "/update", {**header, "id": good, reference: None})
            self.check(label + "_optional_reference_can_be_cleared", api.get(doc_path + "/get", id=good).get(reference) is None)
            api.call("PUT", doc_path + "/update", {**header, "id": good, reference: qc_id})
            if stage == "oqc":
                line = self.create("mandatoryOqcLine", "/mes/wm/product-sales-line", {
                    "salesId": good, "itemId": ids["product"], "quantity": 1, "oqcCheckFlag": True,
                })
                api.put(doc_path + "/submit", id=good)
                sales_pending = api.page("/mes/qc/pending-inspect", qcType=3,
                                         sourceDocCode=header["code"], itemId=ids["product"])
                matching_pending = [row for row in sales_pending if row["sourceDocType"] == 118
                                    and row["sourceDocId"] == good and row["sourceLineId"] == line]
                self.check("header_oqc_does_not_exempt_line_inspection",
                           api.get(doc_path + "/get", id=good)["status"] == 1
                           and len(matching_pending) == 1
                           and flow.dec(matching_pending[0]["quantity"]) == 1
                           and matching_pending[0]["clientId"] == party,
                           salesId=good, lineId=line, pendingQuantity=1)

    def run(self):
        self.login()
        self.setup()
        self.ipqc()
        self.qc_references()
        # Keep this dedicated station/order available for the later real H5
        # walkthrough. The order can be closed manually after that acceptance.
        self.check("own_station_kept_available_for_touchscreen_acceptance",
                   self.api.get("/mes/pro/work-order/get", id=self.ids["workOrder"])["status"] == 1
                   and [row["id"] for row in self.api.get("/mes/pro/workbench/tasks", workstationId=self.ids["workstation"])] == [self.ids["task"]])


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base-url", default="http://127.0.0.1:48080/admin-api")
    parser.add_argument("--tenant", type=int, default=1)
    parser.add_argument("--run-id", default=datetime.now().strftime("%Y%m%d%H%M%S") + "-" + uuid.uuid4().hex[:6])
    parser.add_argument("--secrets-file", type=Path, default=ROOT / ".runtime/secrets.local.json")
    parser.add_argument("--report", type=Path, default=ROOT / ".runtime/mes-quality-acceptance.json")
    args = parser.parse_args()
    flow.require(re.fullmatch(r"[A-Za-z0-9-]{1,30}", args.run_id), "run-id must be 1-30 letters, digits or hyphens")
    report = {"runId": args.run_id, "checks": [], "ids": {}, "result": "running", "evidenceBoundary": [
        "Authenticated admin HTTP calls to the same endpoints used by H5; no browser/touchscreen or operator-role permission assertion.",
        "Run-specific product, route, process, station, order, indicators, templates and QC documents; BOM uses sample raw item 900101.",
        "Consumes four sample raw units and leaves four classified units in this order's WIP. No existing production orders or templates are edited.",
        "Own confirmed order/task and product/process SOP remain available for the subsequent touchscreen UI walkthrough; the dedicated order is not automatically closed.",
        "IPQC verifies real feedback, task/order and WIP writeback; IQC/OQC reference tests use manual source-free inspections, not complete purchase/sales inventory flows.",
        "RQC, attachment uploads, threshold auto-decision and full notice-level IQC writeback are outside this script.",
    ]}
    run = QualityRun(args, report)
    try:
        run.run()
        report["result"] = "passed"
    except Exception as error:
        report["result"] = "failed"
        report["error"] = str(error)
    finally:
        report["httpCalls"] = run.api.calls
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2, default=str) + "\n", encoding="utf-8")
    print(json.dumps({"runId": args.run_id, "result": report["result"], "passedChecks": len(report["checks"]),
                      "httpCalls": report["httpCalls"], "report": str(args.report)}, ensure_ascii=False))
    return 0 if report["result"] == "passed" else 1


if __name__ == "__main__":
    sys.exit(main())
