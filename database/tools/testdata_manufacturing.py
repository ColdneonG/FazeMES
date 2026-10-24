"""Scenario fixtures executed through the public MES business APIs.

The caller owns database reset, master data and priced procurement. This module
never connects to MySQL and never inserts stock, consume or produce evidence.
Historical feedback dates are explicitly labelled as synthetic backfill; API
audit, stock movements and approvals keep their real execution timestamps.
"""
from __future__ import annotations

from collections import defaultdict
from datetime import timedelta
from decimal import Decimal
import time


SCENARIOS = [
    {"code": "MFG-H01..H12", "name": "近30天历史完工", "coverage": "12笔10至30件；双工序、采购成本、领料、耗料、产品入库、完工及实际成本结算"},
    {"code": "MFG-COMPLETE", "name": "50件完整闭环", "coverage": "30+20前序；20+30后序；一源分拆与多源合并；实际成本；SN；受控SOP"},
    {"code": "MFG-PROGRESS", "name": "30件在制", "coverage": "首序10件、末序5件，真实工单所属WIP及未消耗余料"},
    {"code": "MFG-APPROVING", "name": "12件报工待审批", "coverage": "已领料、SOP执行完成，报工提交后尚未扣料"},
    {"code": "MFG-REJECTED", "name": "8件报工驳回", "coverage": "报工提交并驳回为草稿；无虚构耗料或产出"},
    {"code": "MFG-SHORTAGE", "name": "12000件缺料", "coverage": "采购库存不足、齐套分析、物料采购跟催"},
    {"code": "MFG-CANCELED", "name": "20件工单取消", "coverage": "已确认、排产、预留，未执行前取消并释放预留"},
    {"code": "MFG-READY", "name": "40件齐套待投产", "coverage": "已确认及排产，齐套预留仍有效，SOP待执行"},
    {"code": "MFG-DRAFT", "name": "25件草稿", "coverage": "自动展开工单BOM；未确认、未排产"},
    {"code": "SOP-LIFECYCLE", "name": "SOP版本生命周期", "coverage": "已替代R1快照、现行R2、草稿R3、已提交、已驳回"},
    {"code": "SN-TRACE", "name": "单件SN实证追溯", "coverage": "3件双工序完成、1件首序完成、1件仅注册；精确关联实际耗料明细"},
    {"code": "BROKY-MULTILEVEL", "name": "Broky三层计划与缺料", "coverage": "100台父工单及100套机头/底座子工单；28种外购叶子BOM；冻结标准成本；缺料采购跟催；独立20台草稿"},
    {"code": "WORKFORCE", "name": "人员上下工及任务投料", "coverage": "真实操作员登录、两工位上下工和当前在线快照；实际领料单到任务的投料分配及数量余额；计划流转卡草稿、已确认和取消"},
]


def _rows(value):
    """Accept Api.page returning either the PageResult or its list."""
    return value.get("list", []) if isinstance(value, dict) else value


def _decimal(value):
    return Decimal(str(value or 0))


def _number(value):
    value = _decimal(value)
    return int(value) if value == value.to_integral_value() else float(value)


def _date(value):
    return value.replace(microsecond=0).isoformat()


def seed_api(api, ctx):
    """Create a coherent manufacturing portfolio, raising on every failed call.

    Required baseline IDs are overridable via ctx.ids. The result includes all
    evidence IDs and warehouse product stocks for shipping/return scenarios.
    """
    ids = ctx.ids
    now = ctx.now.replace(microsecond=0)
    raw = ids.get("raw", 900101)
    bolt = ids.get("bolt", 900102)
    product = ids.get("product", 900103)
    client = ids.get("client", 900001)
    warehouse = ids.get("warehouse", 900001)
    wip = ids.get("wip", 900002)
    route = ids.get("route", 900001)
    process1 = ids.get("process1", 900001)
    process2 = ids.get("process2", 900002)
    station1 = ids.get("station1", 900001)
    station2 = ids.get("station2", 900002)
    # Approval must be performed by the user explicitly named on the report.
    approver = ids.get("admin", 1)
    operator = ids.get("operator", 980003)
    result = {"orders": {}, "tasks": {}, "feedback": {}, "issues": {},
              "receipts": {}, "costs": {}, "sn": {}, "sop": {},
              "historical": [], "scenarios": SCENARIOS}
    ids["manufacturing"] = result

    def put_body(path, payload):
        return api.call("PUT", path, payload)

    def stocks(item, *, order=None, physical=True):
        query = {"itemId": item, "warehouseId": warehouse if physical else wip,
                 "frozen": "false"}
        if order is not None:
            query["workOrderId"] = order
        rows = _rows(api.page("/mes/wm/material-stock", **query))
        # Explicit client-side identity checks protect against future query changes.
        rows = [row for row in rows if row["itemId"] == item
                and row["warehouseId"] == query["warehouseId"]
                and _decimal(row.get("quantity")) > 0
                and not row.get("frozen") and not row.get("qualityHold")
                and (order is None or row.get("workOrderId") == order)]
        return sorted(rows, key=lambda row: (row.get("receiptTime") or "", row["id"]))

    # Keep root-provided profiles; fill only missing baseline profiles.
    for item, profile in [
        (raw, {"purchasePrice": "28.500000", "laborCost": "0", "overheadCost": "0"}),
        (bolt, {"purchasePrice": "0.650000", "laborCost": "0", "overheadCost": "0"}),
        (product, {"salePrice": "68.000000", "laborCost": "9.000000", "overheadCost": "3.500000"}),
    ]:
        if not api.get("/mes/cost/profile", itemId=item):
            put_body("/mes/cost/profile", {"itemId": item, **profile,
                     "remark": "TST 虚构人民币未税价格；制造测试基准"})

    def order(key, quantity, *, date=None, confirm=True, remark=""):
        date = date or now
        value = api.post("/mes/pro/work-order/create", {
            "code": "TST-" + key, "name": "测试·" + key,
            "type": 1, "orderSourceType": 2, "orderSourceCode": "TST-PLAN-" + key,
            "productId": product, "quantity": quantity,
            "clientId": (986001 + (int(key[-2:])-1)%8) if key.startswith('MFG-H') else client,
            "batchCode": "TST-LOT-" + key, "requestDate": _date(date + timedelta(days=3)),
            "parentId": 0, "remark": "合成测试数据；" + remark})
        result["orders"][key] = value
        if confirm:
            api.put("/mes/pro/work-order/confirm", id=value)
        return value

    def tasks(key, order_id, quantity, *, date=None, execute_sop=True):
        start = date.replace(hour=8, minute=0, second=0) if date is not None else now - timedelta(hours=16)
        made = []
        for index, (process, station) in enumerate([(process1, station1), (process2, station2)]):
            task_id = api.post("/mes/pro/task/create", {
                "workOrderId": order_id, "workstationId": station, "routeId": route,
                "processId": process, "itemId": product, "quantity": quantity,
                "startTime": _date(start + timedelta(hours=index * 8)), "duration": 1,
                "colorCode": "#409EFF" if index == 0 else "#67C23A",
                "remark": "TST 合成排产；" + key})
            made.append(task_id)
            if execute_sop:
                complete_sop(task_id)
        result["tasks"][key] = made
        return made

    def complete_sop(task_id):
        package = api.get("/mes/sop/task", taskId=task_id)
        for step in package.get("steps", []):
            values = {}
            for parameter in step.get("parameters", []):
                low = _decimal(parameter.get("min"))
                high = _decimal(parameter.get("max")) if parameter.get("max") is not None else low
                values[parameter["key"]] = _number((low + high) / 2)
            evidence = {"taskId": task_id, "stepId": step["id"], "parameters": values, "photos": []}
            api.post("/mes/sop/task/view", evidence)
            # MySQL DATETIME(0) can round a just-saved viewedAt into the next
            # second; even a zero-second rule must not see a future timestamp.
            time.sleep(1.05)
            api.post("/mes/sop/task/confirm", evidence)

    def issue(key, order_id, quantity):
        issue_id = api.post("/mes/wm/product-issue/create", {
            "code": "TST-ISS-" + key, "name": "测试·生产领料·" + key,
            "workOrderId": order_id, "workstationId": station1,
            "requiredTime": _date(now), "remark": "合成测试；按实际采购库存批次拣料"})
        picks = []
        for item, qty in [(raw, _decimal(quantity)), (bolt, _decimal(quantity) * 4)]:
            line = api.post("/mes/wm/product-issue-line/create", {
                "issueId": issue_id, "itemId": item, "quantity": _number(qty),
                "remark": "TST 配方用料，不借用其他工单线边库存"})
            remaining = qty
            for stock in stocks(item):
                if remaining <= 0:
                    break
                take = min(remaining, _decimal(stock["quantity"]))
                picks.append({
                    "lineId": line, "issueId": issue_id, "itemId": item,
                    "materialStockId": stock["id"], "quantity": _number(take),
                    "batchId": stock.get("batchId"), "batchCode": stock.get("batchCode"),
                    "warehouseId": stock["warehouseId"], "locationId": stock["locationId"],
                    "areaId": stock["areaId"], "remark": "TST FIFO 拣货实证"})
                remaining -= take
            if remaining:
                raise AssertionError(f"Insufficient priced physical stock for {key}, item {item}: missing {remaining}")
        # Lines belong to the draft; pick details belong to the submitted issue.
        api.put("/mes/wm/product-issue/submit", id=issue_id)
        for pick in picks:
            api.post("/mes/wm/product-issue-detail/create", pick)
        for action in ("stock", "finish"):
            api.put("/mes/wm/product-issue/" + action, id=issue_id)
        result["issues"][key] = issue_id
        return issue_id

    def feedback(key, order_id, task_id, process, station, quantity, *, sources=None,
                 date=None, submit=True, approve=True):
        report = api.post("/mes/pro/feedback/create", {
            "code": "TST-FB-" + key, "type": 2, "workstationId": station,
            "routeId": route, "processId": process, "workOrderId": order_id,
            "taskId": task_id, "itemId": product, "feedbackQuantity": quantity,
            "qualifiedQuantity": quantity, "unqualifiedQuantity": 0, "uncheckQuantity": 0,
            "laborScrapQuantity": 0, "materialScrapQuantity": 0, "otherScrapQuantity": 0,
            "feedbackUserId": operator, "approveUserId": approver,
            "feedbackTime": _date(date or ctx.at("feedback:" + key)), "lotNumber": "TST-" + key,
            "sources": [{"sourceFeedbackId": source, "quantity": qty}
                        for source, qty in (sources or [])],
            "remark": "TST 合成报工；前序来源明确分配；" + ("历史日期回填，审计及库存时间保留真实执行时间" if date else "当前生产演练")})
        result["feedback"][key] = report
        if submit:
            api.put("/mes/pro/feedback/submit", id=report)
        if submit and approve:
            api.put("/mes/pro/feedback/approve", id=report)
            saved = api.get("/mes/pro/feedback/get", id=report)
            assert saved["status"] == 4, (key, saved)
            assert saved.get("flowComplete") is True, (key, "incomplete process genealogy", saved)
        return report

    def receive(key, order_id):
        owned = stocks(product, order=order_id, physical=False)
        if not owned:
            raise AssertionError(f"No produced WIP to receive for {key}")
        receipt = api.post("/mes/wm/product-receipt/create", {
            "code": "TST-PR-" + key, "name": "测试·合格产品入库·" + key,
            "workOrderId": order_id, "receiptDate": _date(now),
            "remark": "TST 由已完成报工的工单所属产品库存入库"})
        for stock in owned:
            qty = _number(stock["quantity"])
            line = api.post("/mes/wm/product-receipt-line/create", {
                "receiptId": receipt, "itemId": product, "materialStockId": stock["id"],
                "quantity": qty, "batchId": stock.get("batchId"),
                "batchCode": stock.get("batchCode"), "remark": "TST 本工单实物库存"})
            api.post("/mes/wm/product-receipt-detail/create", {
                "lineId": line, "receiptId": receipt, "itemId": product, "quantity": qty,
                "batchId": stock.get("batchId"), "warehouseId": warehouse,
                "locationId": ids.get("location", 900001), "areaId": ids.get("area", 900001),
                "remark": "TST 合格产品正常仓上架"})
        for action in ("submit", "stock", "finish"):
            api.put("/mes/wm/product-receipt/" + action, id=receipt)
        result["receipts"][key] = receipt
        return receipt

    def effort(key, order_id, quantity, *, with_void=False):
        for index, process in enumerate([process1, process2]):
            api.post("/mes/cost/workorder/effort", {
                "workorderId": order_id, "processId": process,
                "hours": str((_decimal(quantity) * (Decimal("0.05") if index == 0 else Decimal("0.075"))).quantize(Decimal("0.0001"))),
                "hourlyRate": "60.000000", "overheadAmount": str(_decimal(quantity) * (1 if index == 0 else 2)),
                "remark": "TST 实际工时及制造费用；" + key,
                "requestId": f"TST-EFF-{key}-{index + 1}"})
        if with_void:
            api.post("/mes/cost/workorder/effort", {
                "workorderId": order_id, "processId": process1, "hours": "0.5000",
                "hourlyRate": "60.000000", "overheadAmount": "0.000000",
                "remark": "TST 演示误登记，须留痕撤销", "requestId": f"TST-EFF-{key}-VOID"})
            costs = api.get("/mes/cost/workorder", workorderId=order_id)
            wrong = [row for row in costs["efforts"] if row["requestId"] == f"TST-EFF-{key}-VOID"]
            assert len(wrong) == 1, wrong
            api.post("/mes/cost/workorder/effort/void", {
                "id": wrong[0]["id"], "reason": "TST 虚构误登记演练：重复的准备工时，不计入实际成本"})

    def finish(key, order_id, quantity, *, with_void=False):
        receive(key, order_id)
        effort(key, order_id, quantity, with_void=with_void)
        api.put("/mes/pro/work-order/finish", id=order_id)
        api.call("POST", "/mes/cost/workorder/settle", workorderId=order_id)
        costs = api.get("/mes/cost/workorder", workorderId=order_id)
        assert costs["status"] == "SETTLED" and costs.get("actualUnitCost") is not None, (key, costs)
        assert not costs.get("issues"), (key, costs)
        result["costs"][key] = costs

    # Date-backed reporting trends without inventing historical audit timestamps.
    historical_quantities = [10, 12, 15, 18, 20, 24, 16, 28, 22, 30, 25, 14]
    for number, quantity in enumerate(historical_quantities, 1):
        key = f"MFG-H{number:02d}"
        day = (now - timedelta(days=30 - number * 2)).replace(hour=8, minute=0, second=0)
        work = order(key, quantity, date=day, remark="历史合成演练；业务日期回填，真实审计时间不伪造")
        task1, task2 = tasks(key, work, quantity, date=day)
        issue(key, work, quantity)
        first = feedback(key + "-01", work, task1, process1, station1, quantity,
                         date=day + timedelta(hours=6))
        feedback(key + "-02", work, task2, process2, station2, quantity,
                 sources=[(first, quantity)], date=day + timedelta(hours=14))
        finish(key, work, quantity)
        result["historical"].append({"key": key, "workOrderId": work, "quantity": quantity,
                                     "feedbackDate": day.date().isoformat()})

    # Controlled operating instructions are published before subsequent task binding.
    def sop_version(document, version, title, steps, state="PUBLISHED"):
        version_id = api.post("/mes/sop/version/save", {
            "documentId": document, "versionNo": version, "title": title,
            "changeNote": "TST 合成验证；作业内容、参数上下限及操作记录可核对", "steps": steps,
            "attachments": [], "modelUrl": ""})
        actions = {"DRAFT": [], "SUBMITTED": ["submit"],
                   "REJECTED": ["submit", "reject"],
                   "PUBLISHED": ["submit", "approve", "publish"]}[state]
        for action in actions:
            api.post("/mes/sop/version/" + action, {
                "id": version_id, "comment": "TST 验证完成" if action != "reject" else "TST 试审驳回：作业说明需补充测量工具精度要求"})
        return version_id

    sop_documents = []
    for process, suffix, title, parameter in [
        (process1, "CUT", "下料作业", {"key": "length", "label": "下料长度", "required": True, "min": 49.95, "max": 50.05, "unit": "mm"}),
        (process2, "ASM", "装配作业", {"key": "torque", "label": "紧固扭矩", "required": True, "min": 11.4, "max": 12.6, "unit": "N·m"}),
    ]:
        document = api.post("/mes/sop/document/create", {
            "code": "TST-SOP-" + suffix, "name": "测试·传动组件" + title,
            "itemId": product, "processId": process})
        steps = [
            {"id": "PRECHECK", "title": "开工前确认", "instruction": "核对工单、物料批次、工装定位和设备可用性；确认图纸版本及防护措施。",
             "required": True, "minViewSeconds": 0, "photoRequired": False, "attachments": [], "parameters": []},
            {"id": "MEASURE", "title": "关键参数记录", "instruction": "按本工序标准完成作业，使用校验有效的量具测量并记录关键参数。",
             "required": True, "minViewSeconds": 0, "photoRequired": False, "attachments": [], "parameters": [parameter]},
            {"id": "CHECK", "title": "自检与交接", "instruction": "确认外观、数量和流转标签；不合格品隔离并按流程提交质量异常。",
             "required": True, "minViewSeconds": 0, "photoRequired": False, "attachments": [], "parameters": []},
        ]
        version1 = sop_version(document, "R1", "测试·" + title + "·R1", steps)
        sop_documents.append((document, title, steps))
        result["sop"][suffix] = {"documentId": document, "R1": version1}

    key = "MFG-COMPLETE"
    work = order(key, 50, remark="50件完整闭环；前序30+20分批，后序20+30分拆合并来源；成本和SN实证")
    task1, task2 = tasks(key, work, 50)
    api.call("POST", "/mes/pro/kitting/reserve", workOrderId=work)
    issue(key, work, 50)
    first30 = feedback(key + "-CUT30", work, task1, process1, station1, 30)
    first20 = feedback(key + "-CUT20", work, task1, process1, station1, 20)
    final20 = feedback(key + "-ASM20", work, task2, process2, station2, 20, sources=[(first30, 20)])
    final30 = feedback(key + "-ASM30", work, task2, process2, station2, 30,
                       sources=[(first30, 10), (first20, 20)])

    def sn_materials(report):
        options = api.get("/mes/pro/sn-trace/consume-options", feedbackId=report)
        groups = defaultdict(list)
        for option in options:
            groups[option["consumeLineId"]].append(option)
        selected = []
        for line, options_for_line in groups.items():
            remaining = _decimal(options_for_line[0]["unitQuantity"])
            for option in options_for_line:
                if remaining <= 0:
                    break
                take = min(remaining, _decimal(option["availableQuantity"]))
                if take > 0:
                    selected.append({"consumeDetailId": option["consumeDetailId"], "quantity": _number(take)})
                    remaining -= take
            assert remaining == 0, ("Missing single-unit material allocation", report, line, remaining)
        return selected

    for number in range(1, 6):
        trace = api.post("/mes/pro/sn-trace/register", {
            "workOrderId": work, "snCode": f"TST-SN-COMPLETE-{number:04d}"})
        result["sn"][f"COMPLETE-{number:04d}"] = trace
        if number <= 4:
            api.post("/mes/pro/sn-trace/station", {"requestKey": f"TST-SN-{number}-CUT",
                "traceId": trace, "feedbackId": first30, "materials": sn_materials(first30)})
        if number <= 3:
            api.post("/mes/pro/sn-trace/station", {"requestKey": f"TST-SN-{number}-ASM",
                "traceId": trace, "feedbackId": final20, "materials": sn_materials(final20)})
    finish(key, work, 50, with_void=True)
    ids["finished_order"] = work
    ids["finished_feedback"] = final30

    key = "MFG-PROGRESS"
    work = order(key, 30, remark="在制状态：首序已完成10件，装配已完成5件；余料及5件产出保留在本工单线边")
    task1, task2 = tasks(key, work, 30)
    issue(key, work, 30)
    first = feedback(key + "-CUT10", work, task1, process1, station1, 10)
    feedback(key + "-ASM05", work, task2, process2, station2, 5, sources=[(first, 5)])
    effort(key, work, 5)

    key = "MFG-APPROVING"
    work = order(key, 12, remark="首序12件已提交，等待指定审批人审批；实际耗料尚未发生")
    task1, task2 = tasks(key, work, 12)
    issue(key, work, 12)
    feedback(key + "-CUT12", work, task1, process1, station1, 12, approve=False)

    key = "MFG-REJECTED"
    work = order(key, 8, remark="报工驳回案例：回到草稿待补充；没有已完成耗料或库存产出")
    task1, task2 = tasks(key, work, 8)
    rejected = feedback(key + "-CUT08", work, task1, process1, station1, 8, approve=False)
    api.put("/mes/pro/feedback/reject", id=rejected)

    key = "MFG-CANCELED"
    work = order(key, 20, remark="计划取消演练：排产和物料预留完成后，在领料和生产前取消")
    tasks(key, work, 20, execute_sop=False)
    api.call("POST", "/mes/pro/kitting/reserve", workOrderId=work)
    api.put("/mes/pro/work-order/cancel", id=work)

    # Publish R2 only after old tasks have bound R1, preserving version snapshots.
    for (document, title, steps), suffix in zip(sop_documents, ["CUT", "ASM"]):
        result["sop"][suffix]["R2"] = sop_version(document, "R2", "测试·" + title + "·R2", steps)
        result["sop"][suffix]["R3-DRAFT"] = sop_version(document, "R3-DRAFT", "测试·" + title + "·待完善", steps, "DRAFT")
        result["sop"][suffix]["R3-SUBMITTED"] = sop_version(document, "R3-REVIEW", "测试·" + title + "·审核中", steps, "SUBMITTED")
        result["sop"][suffix]["R0-REJECTED"] = sop_version(document, "R0-REJECT", "测试·" + title + "·试审驳回", steps, "REJECTED")

    key = "MFG-READY"
    work = order(key, 40, remark="齐套已预留、已排产，现行R2 SOP待阅读确认，尚未领料")
    tasks(key, work, 40, date=now + timedelta(days=1), execute_sop=False)
    api.call("POST", "/mes/pro/kitting/reserve", workOrderId=work)

    key = "MFG-SHORTAGE"
    work = order(key, 12000, remark="计划需求大于可用库存；只作缺料分析及采购跟催，不抢占其他工单库存")
    shortage = api.get("/mes/pro/kitting/analyze", workOrderId=work)
    assert shortage.get("shortageItemCount", 0) > 0, shortage
    result["shortage"] = shortage
    for item, status, delay in [(raw, "ORDERED", 5), (bolt, "OPEN", 7)]:
        put_body("/mes/pro/kitting/followup", {"workOrderId": work, "itemId": item,
                 "trackingStatus": status, "expectedDate": (now + timedelta(days=delay)).date().isoformat(),
                 "remark": "TST 合成缺料跟催；供应商交期及采购进度待业务确认"})

    order("MFG-DRAFT", 25, confirm=False, remark="尚未评审确认的生产计划；自动生成配方BOM供修改和核对")
    result["productStocks"] = stocks(product)
    ids["product_stocks"] = result["productStocks"]
    ids["manufacturing_orders"] = result["orders"]
    ids["manufacturing_tasks"] = result["tasks"]
    ids["manufacturing_feedback"] = result["feedback"]
    return result


def validation(ctx):
    """Read-only relational and scenario checks; every SQL returns violations."""
    checks = [
        ("manufacturing:closed_portfolio", """SELECT ABS(COUNT(*)-13)
            FROM mes_pro_work_order WHERE deleted=0 AND status=2
            AND (code LIKE 'TST-MFG-H%' OR code='TST-MFG-COMPLETE')"""),
        ("manufacturing:completed_quantity", """SELECT ABS(COALESCE(SUM(quantity_produced),0)-284)
            FROM mes_pro_work_order WHERE deleted=0 AND status=2
            AND (code LIKE 'TST-MFG-H%' OR code='TST-MFG-COMPLETE')"""),
        ("manufacturing:closed_cost_settlement", """SELECT COUNT(*) FROM mes_pro_work_order w
            LEFT JOIN mes_cost_workorder c ON c.workorder_id=w.id AND c.tenant_id=w.tenant_id AND c.deleted=0
            WHERE w.deleted=0 AND w.status=2 AND w.code LIKE 'TST-MFG-%'
            AND (c.id IS NULL OR c.status<>'SETTLED' OR c.actual_unit_cost IS NULL
                 OR c.produced_quantity<>w.quantity_produced)"""),
        ("manufacturing:closed_wip_empty", """SELECT COUNT(*) FROM mes_wm_material_stock s
            JOIN mes_pro_work_order w ON w.id=s.work_order_id AND w.tenant_id=s.tenant_id
            WHERE s.deleted=0 AND s.quantity>0 AND w.deleted=0 AND w.status IN(2,3)
            AND w.code LIKE 'TST-MFG-%'"""),
        ("manufacturing:order_scenario_states", """SELECT COUNT(*) FROM mes_pro_work_order
            WHERE deleted=0 AND ((code='TST-MFG-CANCELED' AND status<>3)
            OR (code='TST-MFG-DRAFT' AND status<>0)
            OR (code IN('TST-MFG-PROGRESS','TST-MFG-APPROVING','TST-MFG-REJECTED',
                'TST-MFG-READY','TST-MFG-SHORTAGE') AND status<>1)
            OR (code='TST-MFG-PROGRESS' AND quantity_produced<>5))"""),
        ("manufacturing:pending_and_rejected", """SELECT COUNT(*) FROM mes_pro_feedback
            WHERE deleted=0 AND ((code='TST-FB-MFG-APPROVING-CUT12' AND status<>2)
            OR (code='TST-FB-MFG-REJECTED-CUT08' AND status<>0))"""),
        ("manufacturing:complete_flow_flags", """SELECT COUNT(*) FROM mes_pro_feedback
            WHERE deleted=0 AND code LIKE 'TST-FB-MFG-%' AND status=4
            AND (flow_required<>1 OR flow_complete<>1)"""),
        ("manufacturing:posted_consumption_evidence", """SELECT COUNT(*) FROM mes_pro_feedback f
            LEFT JOIN mes_wm_item_consume c ON c.feedback_id=f.id AND c.tenant_id=f.tenant_id
                AND c.deleted=0 AND c.status=4
            WHERE f.deleted=0 AND f.status=4 AND f.code LIKE 'TST-FB-MFG-%'
            AND c.id IS NULL"""),
        ("manufacturing:nonposted_no_consumption", """SELECT COUNT(*) FROM mes_pro_feedback f
            JOIN mes_wm_item_consume c ON c.feedback_id=f.id AND c.tenant_id=f.tenant_id AND c.deleted=0
            WHERE f.deleted=0 AND f.status IN(0,2) AND f.code LIKE 'TST-FB-MFG-%'"""),
        ("manufacturing:source_identity", """SELECT COUNT(*) FROM mes_pro_feedback_source s
            JOIN mes_pro_feedback f ON f.id=s.feedback_id AND f.tenant_id=s.tenant_id AND f.deleted=0
            LEFT JOIN mes_pro_feedback p ON p.id=s.source_feedback_id AND p.tenant_id=s.tenant_id AND p.deleted=0
            WHERE s.deleted=0 AND f.code LIKE 'TST-FB-MFG-%' AND
            (p.id IS NULL OR p.status<>4 OR p.work_order_id<>f.work_order_id OR p.item_id<>f.item_id
             OR p.route_id<>f.route_id OR p.process_id=f.process_id
             OR (f.status IN(3,4) AND s.frozen<>1))"""),
        ("manufacturing:source_adjacent_operation", """SELECT COUNT(*) FROM mes_pro_feedback_source s
            JOIN mes_pro_feedback f ON f.id=s.feedback_id AND f.tenant_id=s.tenant_id AND f.deleted=0
            JOIN mes_pro_feedback p ON p.id=s.source_feedback_id AND p.tenant_id=s.tenant_id AND p.deleted=0
            JOIN mes_pro_route_process rp ON rp.route_id=f.route_id AND rp.process_id=f.process_id
                AND rp.tenant_id=f.tenant_id AND rp.deleted=0
            WHERE s.deleted=0 AND f.code LIKE 'TST-FB-MFG-%' AND p.process_id<>(
                SELECT prior.process_id FROM mes_pro_route_process prior
                WHERE prior.tenant_id=rp.tenant_id AND prior.route_id=rp.route_id AND prior.deleted=0
                    AND prior.sort<rp.sort ORDER BY prior.sort DESC,prior.id DESC LIMIT 1)"""),
        ("manufacturing:downstream_source_quantity", """SELECT COUNT(*) FROM mes_pro_feedback f
            JOIN mes_pro_route_process rp ON rp.route_id=f.route_id AND rp.process_id=f.process_id
                AND rp.tenant_id=f.tenant_id AND rp.deleted=0
            WHERE f.deleted=0 AND f.status=4 AND f.code LIKE 'TST-FB-MFG-%'
            AND EXISTS(SELECT 1 FROM mes_pro_route_process p WHERE p.route_id=rp.route_id
                AND p.tenant_id=rp.tenant_id AND p.deleted=0 AND p.sort<rp.sort)
            AND f.feedback_quantity<>(SELECT COALESCE(SUM(s.quantity),0) FROM mes_pro_feedback_source s
                WHERE s.feedback_id=f.id AND s.tenant_id=f.tenant_id AND s.deleted=0 AND s.frozen=1)"""),
        ("manufacturing:split_merge_sources", """SELECT ABS((SELECT COUNT(*) FROM mes_pro_feedback_source s
            JOIN mes_pro_feedback f ON f.id=s.feedback_id AND f.tenant_id=s.tenant_id
            WHERE s.deleted=0 AND s.frozen=1 AND f.deleted=0 AND f.code='TST-FB-MFG-COMPLETE-ASM20')-1)
            +ABS((SELECT COUNT(*) FROM mes_pro_feedback_source s JOIN mes_pro_feedback f
                ON f.id=s.feedback_id AND f.tenant_id=s.tenant_id
                WHERE s.deleted=0 AND s.frozen=1 AND f.deleted=0 AND f.code='TST-FB-MFG-COMPLETE-ASM30')-2)"""),
        ("manufacturing:source_ancestor_closure", """SELECT COUNT(*) FROM mes_pro_feedback_source s
            JOIN mes_pro_feedback f ON f.id=s.feedback_id AND f.tenant_id=s.tenant_id AND f.deleted=0
            LEFT JOIN mes_pro_feedback_ancestor a ON a.feedback_id=s.feedback_id
                AND a.ancestor_feedback_id=s.source_feedback_id AND a.tenant_id=s.tenant_id AND a.deleted=0
            WHERE s.deleted=0 AND s.frozen=1 AND f.code LIKE 'TST-FB-MFG-%' AND a.id IS NULL"""),
        ("manufacturing:consume_detail_balances", """SELECT COUNT(*) FROM mes_wm_item_consume_line l
            JOIN mes_wm_item_consume c ON c.id=l.consume_id AND c.tenant_id=l.tenant_id AND c.deleted=0
            JOIN mes_pro_feedback f ON f.id=c.feedback_id AND f.tenant_id=c.tenant_id AND f.deleted=0
            WHERE l.deleted=0 AND c.status=4 AND f.code LIKE 'TST-FB-MFG-%' AND l.quantity<>(
                SELECT COALESCE(SUM(d.quantity),0) FROM mes_wm_item_consume_detail d
                WHERE d.line_id=l.id AND d.consume_id=c.id AND d.tenant_id=l.tenant_id AND d.deleted=0)"""),
        ("manufacturing:consume_stock_owner", """SELECT COUNT(*) FROM mes_wm_item_consume_detail d
            JOIN mes_wm_item_consume c ON c.id=d.consume_id AND c.tenant_id=d.tenant_id AND c.deleted=0
            JOIN mes_pro_feedback f ON f.id=c.feedback_id AND f.tenant_id=c.tenant_id AND f.deleted=0
            LEFT JOIN mes_wm_material_stock st ON st.id=d.material_stock_id AND st.tenant_id=d.tenant_id AND st.deleted=0
            WHERE d.deleted=0 AND f.code LIKE 'TST-FB-MFG-%' AND (st.id IS NULL
                OR st.work_order_id<>c.work_order_id OR st.item_id<>d.item_id OR NOT(st.batch_id<=>d.batch_id)
                OR st.warehouse_id<>d.warehouse_id OR st.location_id<>d.location_id OR st.area_id<>d.area_id)"""),
        ("manufacturing:sn_status_portfolio", """SELECT ABS(SUM(status='COMPLETED')-3)
            +ABS(SUM(status='REGISTERED')-2) FROM mes_pro_sn_trace
            WHERE deleted=0 AND sn_code LIKE 'TST-SN-COMPLETE-%'"""),
        ("manufacturing:sn_material_overallocation", """SELECT COUNT(*) FROM (
            SELECT m.consume_detail_id,SUM(m.quantity) allocated,MAX(d.quantity) actual
            FROM mes_pro_sn_material m JOIN mes_wm_item_consume_detail d
                ON d.id=m.consume_detail_id AND d.tenant_id=m.tenant_id AND d.deleted=0
            WHERE m.deleted=0 GROUP BY m.tenant_id,m.consume_detail_id HAVING allocated>actual) violations"""),
        ("manufacturing:sop_version_states", """SELECT ABS(COUNT(*)-10)
            +ABS(SUM(v.status='SUPERSEDED')-2)+ABS(SUM(v.status='PUBLISHED')-2)
            +ABS(SUM(v.status='DRAFT')-2)+ABS(SUM(v.status='SUBMITTED')-2)
            +ABS(SUM(v.status='REJECTED')-2) FROM mes_sop_version v
            JOIN mes_sop_document d ON d.id=v.document_id AND d.tenant_id=v.tenant_id AND d.deleted=0
            WHERE v.deleted=0 AND d.code IN('TST-SOP-CUT','TST-SOP-ASM')"""),
        ("manufacturing:sop_snapshot_versions", """SELECT COUNT(*) FROM mes_sop_task_snapshot s
            JOIN mes_pro_task t ON t.id=s.task_id AND t.tenant_id=s.tenant_id AND t.deleted=0
            JOIN mes_pro_work_order w ON w.id=t.work_order_id AND w.tenant_id=t.tenant_id AND w.deleted=0
            WHERE s.deleted=0 AND w.code LIKE 'TST-MFG-%' AND
                ((w.code='TST-MFG-READY' AND s.version_no<>'R2')
                 OR (w.code<>'TST-MFG-READY' AND s.version_no<>'R1'))"""),
        ("manufacturing:sop_confirmed_before_posted_feedback", """SELECT COUNT(*) FROM mes_pro_feedback f
            JOIN mes_sop_task_snapshot s ON s.task_id=f.task_id AND s.tenant_id=f.tenant_id AND s.deleted=0
            WHERE f.deleted=0 AND f.status IN(2,3,4) AND f.code LIKE 'TST-FB-MFG-%' AND
                (SELECT COUNT(*) FROM mes_sop_step_record r WHERE r.snapshot_id=s.id
                    AND r.tenant_id=s.tenant_id AND r.deleted=0 AND r.confirmed_at IS NOT NULL)<>3"""),
    ]
    if ctx.ids.get("broky_portfolio"):
        checks.extend(_broky_validation())
    if ctx.ids.get("workforce_portfolio"):
        checks.extend(_workforce_validation())
    return checks


def seed_broky_api(api, ctx):
    """Enable reviewed teaching configuration and create an unexecuted BOM tree.

    This deliberately posts no inventory, feedback, inspection result or actual
    cost. Child orders reserve parent demand through the normal parentId gate.
    """
    from generate_broky_product import BOMS, FG, HEAD, STAND, RAW, build_rows

    now = ctx.now.replace(microsecond=0)
    configured = defaultdict(list)
    for table, values, identity in build_rows():
        configured[table].append(values)
    result = {"orders": {}, "configuration": {"items": [], "routes": [], "templates": [], "sopVersions": []},
              "planCosts": {}, "shortages": {}, "boms": {}, "leafMaterialCount": len(RAW)}
    ctx.ids["broky_portfolio"] = result
    comment = "TST 教学配置核验：本层BOM、工序分料及标准预算一致。仅发布测试作业配置，未声明实物生产、检验或安全认证。"

    # Follow the existing verified publication sequence: SOPs, quality templates,
    # items and then routes. Existing identities must match before enabling.
    documents = {row["id"]: row for row in api.get("/mes/sop/document/list", keyword="Broky")}
    for expected in configured["mes_sop_version"]:
        document = documents.get(expected["document_id"])
        assert document and document["code"].startswith("BROKY-SOP-"), expected
        versions = api.get("/mes/sop/version/list", documentId=expected["document_id"])
        version = next((row for row in versions if row["id"] == expected["id"]), None)
        assert version and version["versionNo"] == expected["version_no"], expected
        # Validate immutable teaching instructions against their maintained source.
        import json
        assert json.loads(version["stepsJson"]) == json.loads(expected["steps_json"]), version["id"]
        state = version["status"]
        assert state in {"DRAFT", "SUBMITTED", "APPROVED", "PUBLISHED"}, version
        for before, action, after in [("DRAFT", "submit", "SUBMITTED"),
                                      ("SUBMITTED", "approve", "APPROVED"),
                                      ("APPROVED", "publish", "PUBLISHED")]:
            if state == before:
                api.call("POST", "/mes/sop/version/" + action, {"id": version["id"], "comment": comment})
                state = after
        result["configuration"]["sopVersions"].append(version["id"])

    for expected in configured["mes_qc_template"]:
        template = api.get("/mes/qc/template/get", id=expected["id"])
        assert template and template["code"] == expected["code"], expected
        if template["status"] != 0:
            api.call("PUT", "/mes/qc/template/update", {
                **{key: template.get(key) for key in ("id", "code", "name", "types", "ipqcTypes", "remark")},
                "status": 0})
        result["configuration"]["templates"].append(template["id"])

    for expected in configured["mes_md_item"]:
        item = api.get("/mes/md/item/get", id=expected["id"])
        assert item and item["code"] == expected["code"], expected
        if item["status"] != 0:
            api.call("PUT", "/mes/md/item/update-status", id=item["id"], status=0)
        result["configuration"]["items"].append(item["id"])
    for expected in configured["mes_pro_route"]:
        route = api.get("/mes/pro/route/get", id=expected["id"])
        assert route and route["code"] == expected["code"], expected
        if route["status"] != 0:
            api.call("PUT", "/mes/pro/route/update-status", id=route["id"], status=0)
        result["configuration"]["routes"].append(route["id"])

    def create(key, item, quantity, parent=0, confirmed=True, days=21):
        work = api.call("POST", "/mes/pro/work-order/create", {
            "code": "TST-BROKY-ORDER-" + key, "name": "测试·Broky多层计划·" + key,
            "type": 1, "orderSourceType": 2, "orderSourceCode": "TST-BROKY-MPS-A" if key != "DRAFT" else "TST-BROKY-MPS-DRAFT",
            "productId": item, "quantity": quantity, "clientId": 972001,
            "batchCode": "TST-BROKY-LOT-" + key, "requestDate": _date(now + timedelta(days=days)),
            "parentId": parent, "remark": "TST 三层计划测试；仅确认计划、自动生成本层BOM及冻结标准成本。外购原料未采购，未领料、未排产、未生产。"})
        result["orders"][key] = work
        if confirmed:
            api.call("PUT", "/mes/pro/work-order/confirm", id=work)
            costs = api.get("/mes/cost/workorder", workorderId=work)
            assert costs.get("planSnapshot", {}).get("status") == "COMPLETE", costs
            assert _decimal(costs.get("planQuantity")) == quantity, costs
            assert costs.get("actualUnitCost") is None, costs
            result["planCosts"][key] = {
                "planQuantity": costs["planQuantity"], "planMaterialCost": costs.get("planMaterialCost"),
                "planLaborCost": costs.get("planLaborCost"), "planOverheadCost": costs.get("planOverheadCost"),
                "planTotalCost": costs.get("planTotalCost"), "status": costs["status"]}
            shortage = api.get("/mes/pro/kitting/analyze", workOrderId=work)
            assert shortage["shortageItemCount"] == len(BOMS[item]) and not shortage["ready"], shortage
            result["shortages"][key] = shortage
        # Public order-BOM API is a snapshot of only this level, preserving the
        # manufacturing boundary rather than duplicating nested purchased leaves.
        bom = api.get("/mes/pro/work-order-bom/list-by-work-order-id", workOrderId=work)
        expected = {child: _decimal(amount) * quantity for child, amount in BOMS[item]}
        assert {row["itemId"]: _decimal(row["quantity"]) for row in bom} == expected, (key, bom)
        result["boms"][key] = bom
        return work

    parent = create("A", FG, 100)
    head = create("A-HEAD", HEAD, 100, parent=parent, days=14)
    create("A-STAND", STAND, 100, parent=parent, days=14)
    create("DRAFT", FG, 20, confirmed=False, days=28)
    api.call("PUT", "/mes/pro/kitting/followup", {"workOrderId": head, "itemId": RAW[16],
        "trackingStatus": "ORDERED", "expectedDate": (now + timedelta(days=7)).date().isoformat(),
        "remark": "TST Broky电机采购模块100套已列入采购跟催；未实际到货或入库，须经真实来料检验放行。"})
    result["shortages"]["A-HEAD"] = api.get("/mes/pro/kitting/analyze", workOrderId=head)
    baseline = api.get("/mes/pro/work-order/get", id=972901)
    assert baseline["code"] == "BROKY-WO-FG-100" and baseline["status"] == 0, baseline
    result["baselineTeachingDraft"] = baseline["id"]
    for key, expected_cost in [("A", "17200"), ("A-HEAD", "8550"), ("A-STAND", "3150")]:
        assert _decimal(result["planCosts"][key]["planTotalCost"]) == _decimal(expected_cost), (key, result["planCosts"][key])
    return result


def _broky_validation():
    return [
        ("broky:order_portfolio", """SELECT ABS(COUNT(*)-4)+ABS(SUM(status=1)-3)+ABS(SUM(status=0)-1)
            FROM mes_pro_work_order WHERE deleted=0 AND code LIKE 'TST-BROKY-ORDER-%'"""),
        ("broky:teaching_baseline_untouched", """SELECT COUNT(*) FROM mes_pro_work_order
            WHERE id=972901 AND (code<>'BROKY-WO-FG-100' OR status<>0 OR quantity<>100 OR quantity_produced<>0)"""),
        ("broky:child_demand", """SELECT COUNT(*) FROM mes_pro_work_order c
            LEFT JOIN mes_pro_work_order p ON p.id=c.parent_id AND p.tenant_id=c.tenant_id AND p.deleted=0
            LEFT JOIN mes_pro_work_order_bom b ON b.work_order_id=p.id AND b.item_id=c.product_id
                AND b.tenant_id=c.tenant_id AND b.deleted=0
            WHERE c.deleted=0 AND c.code IN('TST-BROKY-ORDER-A-HEAD','TST-BROKY-ORDER-A-STAND')
                AND (p.id IS NULL OR p.code<>'TST-BROKY-ORDER-A' OR p.status<>1 OR b.id IS NULL OR c.quantity<>b.quantity)"""),
        ("broky:local_bom_snapshot", """SELECT COUNT(*) FROM mes_pro_work_order w
            JOIN mes_md_product_bom b ON b.item_id=w.product_id AND b.tenant_id=w.tenant_id AND b.deleted=0 AND b.status=0
            LEFT JOIN mes_pro_work_order_bom s ON s.work_order_id=w.id AND s.item_id=b.bom_item_id
                AND s.tenant_id=w.tenant_id AND s.deleted=0
            WHERE w.deleted=0 AND w.code LIKE 'TST-BROKY-ORDER-%'
                AND (s.id IS NULL OR s.quantity<>b.quantity*w.quantity)"""),
        ("broky:confirmed_cost_plans", """SELECT COUNT(*) FROM mes_pro_work_order w
            LEFT JOIN mes_cost_workorder c ON c.workorder_id=w.id AND c.tenant_id=w.tenant_id AND c.deleted=0
            WHERE w.deleted=0 AND w.status=1 AND w.code LIKE 'TST-BROKY-ORDER-%'
                AND (c.id IS NULL OR c.plan_quantity<>100 OR JSON_VALID(c.plan_snapshot)<>1
                    OR c.status='SETTLED' OR c.actual_unit_cost IS NOT NULL)"""),
        ("broky:no_production_evidence", """SELECT
            (SELECT COUNT(*) FROM mes_pro_feedback f JOIN mes_pro_work_order w ON w.id=f.work_order_id
                AND w.tenant_id=f.tenant_id WHERE f.deleted=0 AND w.code LIKE 'TST-BROKY-ORDER-%')
            +(SELECT COUNT(*) FROM mes_pro_task t JOIN mes_pro_work_order w ON w.id=t.work_order_id
                AND w.tenant_id=t.tenant_id WHERE t.deleted=0 AND w.code LIKE 'TST-BROKY-ORDER-%')
            +(SELECT COUNT(*) FROM mes_wm_material_stock s JOIN mes_pro_work_order w ON w.id=s.work_order_id
                AND w.tenant_id=s.tenant_id WHERE s.deleted=0 AND w.code LIKE 'TST-BROKY-ORDER-%')"""),
        ("broky:released_sops", """SELECT ABS(COUNT(*)-11) FROM mes_sop_version v JOIN mes_sop_document d
            ON d.id=v.document_id AND d.tenant_id=v.tenant_id AND d.deleted=0
            WHERE v.deleted=0 AND d.code LIKE 'BROKY-SOP-%' AND v.status='PUBLISHED'"""),
        ("broky:enabled_configuration", """SELECT
            (SELECT COUNT(*) FROM mes_md_item WHERE deleted=0 AND code LIKE 'BROKY-%' AND status<>0)
            +(SELECT COUNT(*) FROM mes_pro_route WHERE deleted=0 AND code LIKE 'BROKY-%' AND status<>0)
            +(SELECT COUNT(*) FROM mes_qc_template WHERE deleted=0 AND code LIKE 'BROKY-QT-%' AND status<>0)"""),
        ("broky:motor_followup", """SELECT ABS(COUNT(*)-1) FROM mes_pro_shortage_followup f
            JOIN mes_pro_work_order w ON w.id=f.work_order_id AND w.tenant_id=f.tenant_id AND w.deleted=0
            WHERE f.deleted=0 AND w.code='TST-BROKY-ORDER-A-HEAD' AND f.item_id=972216
                AND f.tracking_status='ORDERED' AND f.expected_date IS NOT NULL"""),
    ]


def seed_workforce_api(api, ctx):
    """Record real operator clock events and link tasks to actual issue lines."""
    result = {"operators": {}, "taskIssues": [], "cards": {}}
    ctx.ids["workforce_portfolio"] = result
    original_auth = api.headers.get("Authorization")
    try:
        for username, actions in [
            ("operator01", [("clock-in", 900001), ("clock-out", None), ("clock-in", 900002), ("clock-out", None)]),
            ("operator02", [("clock-in", 900002)]),
        ]:
            logged_in = api.call("POST", "/system/auth/login", {"username": username, "password": "Test@2026!"})
            api.headers["Authorization"] = "Bearer " + logged_in["accessToken"]
            logs = []
            for action, station in actions:
                log = api.call("PUT", "/mes/pro/workrecord/" + action,
                               **({"workstationId": station} if station is not None else {}))
                logs.append(log)
                if action == "clock-in" and len(actions) > 1:
                    time.sleep(1.05)
            snapshot = api.get("/mes/pro/workrecord/get-my")
            assert snapshot and snapshot["type"] == (2 if username == "operator01" else 1), snapshot
            result["operators"][username] = {"userId": snapshot["userId"],
                "clockInTime": snapshot.get("clockInTime"), "clockOutTime": snapshot.get("clockOutTime"),
                "status": snapshot["type"], "workstationId": snapshot["workstationId"], "logIds": logs}
            api.call("POST", "/system/auth/logout")
    finally:
        if original_auth is None:
            api.headers.pop("Authorization", None)
        else:
            api.headers["Authorization"] = original_auth

    # These assignment records annotate actual issued quantities. They do not
    # create inventory or imply an additional material issue/consumption.
    manufacturing = ctx.ids["manufacturing"]
    for key in ("MFG-COMPLETE", "MFG-PROGRESS", "MFG-APPROVING"):
        order_id = manufacturing["orders"][key]
        issue_id = manufacturing["issues"][key]
        issue = api.get("/mes/wm/product-issue/get", id=issue_id)
        lines = api.get("/mes/wm/product-issue-line/list-by-issue", issueId=issue_id)
        for line in lines:
            # Inventory acceptance cases return part of this order's bolts.
            # This legacy assignment model has no returnedQuantity field; omit
            # that line instead of misclassifying returned stock as consumed.
            if key == "MFG-PROGRESS" and line["itemId"] == ctx.ids.get("bolt", 900102):
                continue
            item = api.get("/mes/md/item/get", id=line["itemId"])
            own_stocks = api.page("/mes/wm/material-stock", workOrderId=order_id,
                                 warehouseId=ctx.ids.get("wip", 900002), itemId=line["itemId"])
            available = sum((_decimal(stock["quantity"]) for stock in _rows(own_stocks)
                if stock.get("workOrderId") == order_id and stock["itemId"] == line["itemId"]), Decimal(0))
            issued = _decimal(line["quantity"])
            assert 0 <= available <= issued, (key, line, available)
            task_index = 0 if line["itemId"] == ctx.ids.get("raw", 900101) else 1
            task = manufacturing["tasks"][key][task_index]
            task_issue = api.call("POST", "/mes/pro/task-issue/create", {
                "taskId": task, "workOrderId": order_id, "workstationId": 900001 + task_index,
                "sourceDocType": "MATERIAL_ISSUE", "sourceDocId": issue_id,
                "sourceLineId": line["id"], "sourceDocCode": issue["code"],
                "itemId": line["itemId"], "unitMeasureId": item["unitMeasureId"],
                "issuedQuantity": _number(issued), "availableQuantity": _number(available),
                "usedQuantity": _number(issued - available),
                "remark": "TST 任务投料分配；源自实际已执行领料单行，当前余额按本工单线边库存核对；不重复过账。"})
            result["taskIssues"].append(task_issue)

    # Legacy cards carry an unexecuted plan; actual source/consume evidence stays
    # in feedback flow and inventory. No fabricated completion is written.
    for key, order_key, quantity, target in [
        ("DRAFT", "MFG-READY", 20, "DRAFT"),
        ("CONFIRMED", "MFG-READY", 20, "CONFIRMED"),
        ("CANCELED", "MFG-REJECTED", 5, "CANCELED"),
    ]:
        order = api.get("/mes/pro/work-order/get", id=manufacturing["orders"][order_key])
        card = api.call("POST", "/mes/pro/card/create", {"code": "TST-CARD-WF-" + key,
            "workOrderId": order["id"], "itemId": order["productId"], "batchCode": order["batchCode"],
            "transferedQuantity": quantity,
            "remark": "TST 计划流转卡；只登记计划路径，尚未记录实物投入、产出或检验，不代替真实报工来源证据。"})
        for sort, process in enumerate([900001, 900002], 1):
            api.call("POST", "/mes/pro/card-process/create", {"cardId": card, "sort": sort,
                "processId": process, "workstationId": process, "inputQuantity": 0,
                "outputQuantity": 0, "unqualifiedQuantity": 0,
                "remark": "TST 计划工序，尚未执行；时间、操作人和检验单待实际发生后录入。"})
        if target == "CONFIRMED":
            api.call("PUT", "/mes/pro/card/submit", id=card)
        elif target == "CANCELED":
            api.call("PUT", "/mes/pro/card/cancel", id=card)
        result["cards"][key] = {"id": card, "status": target, "quantity": quantity}
    return result


def _workforce_validation():
    return [
        ("workforce:current_snapshot", """SELECT ABS(COUNT(*)-2)+ABS(SUM(type=1)-1)+ABS(SUM(type=2)-1)
            FROM mes_pro_work_record WHERE deleted=0 AND user_id IN(980003,980004)"""),
        ("workforce:clock_event_count", """SELECT ABS(COUNT(*)-5)+ABS(SUM(type=1)-3)+ABS(SUM(type=2)-2)
            FROM mes_pro_work_record_log WHERE deleted=0 AND user_id IN(980003,980004)"""),
        ("workforce:last_event_matches_snapshot", """SELECT COUNT(*) FROM mes_pro_work_record r
            JOIN mes_pro_work_record_log e ON e.id=(SELECT MAX(l.id) FROM mes_pro_work_record_log l
                WHERE l.user_id=r.user_id AND l.tenant_id=r.tenant_id AND l.deleted=0)
            WHERE r.deleted=0 AND r.user_id IN(980003,980004)
                AND (r.type<>e.type OR r.workstation_id<>e.workstation_id)"""),
        ("workforce:task_issue_balances", """SELECT COUNT(*) FROM mes_pro_task_issue i
            LEFT JOIN mes_pro_task t ON t.id=i.task_id AND t.tenant_id=i.tenant_id AND t.deleted=0
            LEFT JOIN mes_wm_product_issue_line l ON l.id=i.source_line_id AND l.tenant_id=i.tenant_id AND l.deleted=0
            WHERE i.deleted=0 AND i.remark LIKE 'TST 任务投料分配%' AND (t.id IS NULL OR l.id IS NULL
                OR i.work_order_id<>t.work_order_id OR i.workstation_id<>t.workstation_id OR i.item_id<>l.item_id
                OR i.source_doc_id<>l.issue_id OR i.issued_quantity<>l.quantity
                OR i.issued_quantity<>i.available_quantity+i.used_quantity
                OR i.available_quantity<0 OR i.used_quantity<0)"""),
        ("workforce:legacy_card_states", """SELECT ABS(COUNT(*)-3)+ABS(SUM(status=0)-1)
            +ABS(SUM(status=1)-1)+ABS(SUM(status=3)-1)
            FROM mes_pro_card WHERE deleted=0 AND code LIKE 'TST-CARD-WF-%'"""),
        ("workforce:planned_card_no_execution", """SELECT COUNT(*) FROM mes_pro_card_process p
            JOIN mes_pro_card c ON c.id=p.card_id AND c.tenant_id=p.tenant_id AND c.deleted=0
            WHERE p.deleted=0 AND c.code LIKE 'TST-CARD-WF-%' AND
                (p.input_time IS NOT NULL OR p.output_time IS NOT NULL OR p.input_quantity<>0
                OR p.output_quantity<>0 OR p.unqualified_quantity<>0 OR p.ipqc_id IS NOT NULL)"""),
    ]
