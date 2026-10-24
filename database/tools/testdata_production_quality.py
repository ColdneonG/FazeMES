"""Quality-gated production fixtures created exclusively through public APIs.

Execute before warehouse snapshots/counts: this module consumes 21 priced raw
pieces, produces 15 inspected pieces and receives 13 qualified pieces. Six
reported pieces remain pending inspection; two rejected pieces remain owned WIP.
"""
from __future__ import annotations

from datetime import timedelta
from decimal import Decimal


SCENARIOS = [
    {"code": "PQC-PENDING6", "name": "关键工序6件待检", "coverage": "真实领料、报工提交审批、耗料已执行；报工UNCHECK；未完成检验前无产品库存或工单产出"},
    {"code": "PQC-FAIL10", "name": "10件检出2件不良", "coverage": "10件逐件尺寸记录，8合格2不合格；实际IPQC回写、产出拆行、缺陷快照；8件合格入库，2件不良留线边待处置，工单开放且成本待核定"},
    {"code": "PQC-PASS5", "name": "5件检验放行闭环", "coverage": "5件逐件尺寸合格；实际IPQC放行、产品入库、工单完工及实际成本结算"},
]


def _rows(value):
    return value.get("list", []) if isinstance(value, dict) else value


def _n(value):
    amount = Decimal(str(value or 0))
    return int(amount) if amount == amount.to_integral_value() else float(amount)


def _stamp(value):
    return value.replace(microsecond=0).isoformat()


def seed_api(api, ctx):
    now = ctx.now.replace(microsecond=0)
    raw, physical, wip = ctx.ids.get("raw", 900101), ctx.ids.get("warehouse", 900001), ctx.ids.get("wip", 900002)
    sample = api.get("/mes/md/item/get", id=ctx.ids.get("product", 900103))
    result = {"masters": {}, "orders": {}, "tasks": {}, "feedback": {}, "ipqc": {},
              "readings": {}, "produceLines": {}, "receipts": {}, "costs": {}, "checks": [],
              "qualityBatchPolicy": "当前生产接口在产出行记录质量分类，同工单同批次库存可共池；合格入库额度由已完成PASS产出事务核验，不良余量保留本工单线边待处置。"}
    ctx.ids["production_quality"] = result

    def create(key, path, body):
        value = api.post(path + "/create", body)
        assert isinstance(value, int) and value > 0, (key, value)
        result["masters"][key] = value
        return value

    unit = create("measurementUnit", "/mes/md/unit-measure", {
        "code": "TST-PQC-MM", "name": "毫米", "primaryFlag": True, "primaryId": 0,
        "changeRate": 1, "status": 0, "remark": "TST 质量演练尺寸单位mm；与库存计件单位分开"})
    product = create("product", "/mes/md/item", {
        "code": "TST-PQC-PRODUCT", "name": "测试·检验门控精密衬套",
        "specification": "教学图样：检验直径10.00±0.10mm；每件消耗1件试制毛坯",
        "unitMeasureId": sample["unitMeasureId"], "itemTypeId": sample["itemTypeId"],
        "safeStockFlag": False, "batchFlag": True, "highValue": False,
        "remark": "TST 合成制造与质检场景；合格及不良数量由实际IPQC服务回写"})
    flags = {name + "Flag": False for name in (
        "produceDate", "expireDate", "receiptDate", "vendor", "client", "salesOrderCode", "purchaseOrderCode",
        "workOrder", "task", "workstation", "tool", "mold", "lotNumber", "qualityStatus")}
    api.post("/mes/md/item-batch-config/save", {"itemId": product, **flags, "workOrderFlag": True, "produceDateFlag": True})
    create("productBom", "/mes/md/product-bom", {"itemId": product, "bomItemId": raw,
        "quantity": 1, "status": 0, "remark": "TST 每件衬套使用1件已定价毛坯"})
    api.call("PUT", "/mes/cost/profile", {"itemId": product, "salePrice": "40.000000",
        "laborCost": "6.000000", "overheadCost": "2.000000", "remark": "TST 质量演练标准预算，人民币未税；实际成本另按采购耗料与工时核算"})
    process = create("process", "/mes/pro/process", {"code": "TST-PQC-PROCESS", "name": "测试·精加工与尺寸全检",
        "attention": "按10.00±0.10mm教学标准逐件检测；未经检验不得转入成品仓，不良品保留待处置。", "status": 0})
    station = create("station", "/mes/md-workstation", {"code": "TST-PQC-STATION", "name": "测试·质量门控工位",
        "workshopId": 900001, "processId": process, "status": 0, "remark": "TST 独立试制与全检工位"})
    route = create("route", "/mes/pro/route", {"code": "TST-PQC-ROUTE", "name": "测试·检验放行路线",
        "description": "单一关键工序；报工审批后必须IPQC，完成检验后按合格与不合格拆分产出。"})
    create("routeProcess", "/mes/pro/route-process", {"routeId": route, "processId": process, "sort": 1,
        "keyFlag": True, "checkFlag": True, "linkType": 0, "prepareTime": 0, "waitTime": 0, "colorCode": "#E6A23C"})
    create("routeProduct", "/mes/pro/route-product", {"routeId": route, "itemId": product,
        "quantity": 1, "productionTime": 6, "timeUnitType": "MINUTE"})
    create("routeBom", "/mes/pro/route-product-bom", {"routeId": route, "processId": process,
        "productId": product, "itemId": raw, "quantity": 1})
    indicator = create("indicator", "/mes/qc/indicator", {"code": "TST-PQC-DIAMETER", "name": "测试·衬套检验直径",
        "type": 1, "resultType": 1, "tool": "教学数显卡尺", "remark": "TST 以mm记录；允许9.90至10.10，超出范围为尺寸不合格"})
    template = create("template", "/mes/qc/template", {"code": "TST-PQC-TEMPLATE", "name": "测试·衬套成品尺寸全检",
        "types": [2], "ipqcTypes": [5], "status": 0, "remark": "TST 逐件检验，质量结论与实际数量分别记录；不允许不合格件作为合格放行"})
    create("templateItem", "/mes/qc/template/item", {"templateId": template, "itemId": product,
        "quantityCheck": 1, "quantityUnqualified": 0, "criticalRate": 0, "majorRate": 0, "minorRate": 0})
    create("templateIndicator", "/mes/qc/template/indicator", {"templateId": template, "indicatorId": indicator,
        "checkMethod": "逐件测量直径并按样本序号记录；教学图样10.00±0.10mm",
        "standardValue": "10.00", "unitMeasureId": unit, "thresholdMin": "9.90", "thresholdMax": "10.10"})
    defect = create("defect", "/mes/qc/defect", {"code": "TST-PQC-DIMENSION-FAIL", "name": "测试·检验直径超差",
        "type": 1, "level": 2, "remark": "TST 数值超出9.90至10.10mm，作为严重缺陷记录；一件只登记一次主缺陷"})
    api.put("/mes/md/item/update-status", id=product, status=0)
    api.put("/mes/pro/route/update-status", id=route, status=0)

    def stock_rows(item, warehouse, order=None):
        query = {"itemId": item, "warehouseId": warehouse}
        if order is not None:
            query["workOrderId"] = order
        return [row for row in _rows(api.page("/mes/wm/material-stock", **query))
            if row["itemId"] == item and row["warehouseId"] == warehouse
            and (order is None or row.get("workOrderId") == order)
            and Decimal(str(row["quantity"])) > 0]

    def make_order(key, quantity):
        order = api.post("/mes/pro/work-order/create", {"code": "TST-PQC-" + key,
            "name": "测试·质量门控·" + key, "type": 1, "orderSourceType": 2,
            "orderSourceCode": "TST-PQC-PLAN-" + key, "productId": product, "quantity": quantity,
            "clientId": ctx.ids.get("client", 900001), "batchCode": "TST-PQC-LOT-" + key,
            "requestDate": _stamp(now + timedelta(days=7)), "parentId": 0,
            "remark": "TST 合成质量门控生产；待检和不良数量不得伪装已放行产品"})
        result["orders"][key] = order
        api.put("/mes/pro/work-order/confirm", id=order)
        task = api.post("/mes/pro/task/create", {"workOrderId": order, "workstationId": station,
            "routeId": route, "processId": process, "itemId": product, "quantity": quantity,
            "startTime": _stamp(now - timedelta(hours=8)), "duration": 1, "colorCode": "#E6A23C"})
        result["tasks"][key] = task
        issue = api.post("/mes/wm/product-issue/create", {"code": "TST-PQC-ISS-" + key,
            "name": "测试·质量门控领料·" + key, "workOrderId": order,
            "workstationId": station, "requiredTime": _stamp(ctx.at("pqc:issue:" + key))})
        line = api.post("/mes/wm/product-issue-line/create", {"issueId": issue, "itemId": raw, "quantity": quantity})
        api.put("/mes/wm/product-issue/submit", id=issue)
        remaining = Decimal(quantity)
        candidates = sorted(stock_rows(raw, physical), key=lambda row: (row.get("receiptTime") or "", row["id"]))
        for stock in candidates:
            if stock.get("frozen") or stock.get("qualityHold"):
                continue
            take = min(remaining, Decimal(str(stock["quantity"])))
            if take <= 0:
                continue
            api.post("/mes/wm/product-issue-detail/create", {"issueId": issue, "lineId": line,
                "itemId": raw, "materialStockId": stock["id"], "quantity": _n(take),
                "batchId": stock.get("batchId"), "batchCode": stock.get("batchCode"),
                "warehouseId": stock["warehouseId"], "locationId": stock["locationId"], "areaId": stock["areaId"]})
            remaining -= take
            if remaining == 0:
                break
        assert remaining == 0, (key, "Insufficient usable priced raw stock", remaining)
        for action in ("stock", "finish"):
            api.put("/mes/wm/product-issue/" + action, id=issue)
        report = api.post("/mes/pro/feedback/create", {"code": "TST-PQC-FB-" + key, "type": 2,
            "workstationId": station, "routeId": route, "processId": process, "workOrderId": order,
            "taskId": task, "itemId": product, "feedbackQuantity": quantity, "qualifiedQuantity": 0,
            "unqualifiedQuantity": 0, "uncheckQuantity": quantity, "laborScrapQuantity": 0,
            "materialScrapQuantity": 0, "otherScrapQuantity": 0, "feedbackUserId": 980003,
            "approveUserId": ctx.ids.get("admin", 1), "feedbackTime": _stamp(ctx.at("pqc:feedback:" + key)),
            "lotNumber": "TST-PQC-" + key, "sources": [], "remark": "TST 关键质检工序，审批后全量待检，实际分类由IPQC完成后回写"})
        result["feedback"][key] = report
        api.put("/mes/pro/feedback/submit", id=report)
        approved = api.put("/mes/pro/feedback/approve", id=report)
        waiting = api.get("/mes/pro/feedback/get", id=report)
        assert approved is False and waiting["status"] == 3, waiting
        task_state = api.get("/mes/pro/task/get", id=task)
        order_state = api.get("/mes/pro/work-order/get", id=order)
        assert Decimal(str(task_state.get("producedQuantity") or 0)) == 0, task_state
        assert Decimal(str(order_state.get("quantityProduced") or 0)) == 0, order_state
        assert not stock_rows(product, wip, order), (key, "Pending inspection created premature product stock")
        pending = _rows(api.page("/mes/qc/pending-inspect", qcType=2, itemId=product,
            workstationId=station, workOrderId=order, taskId=task))
        source = [row for row in pending if row["sourceDocType"] == 304 and row["sourceDocId"] == report]
        assert len(source) == 1 and Decimal(str(source[0]["quantity"])) == quantity, (key, pending)
        return order, task, report, source[0]["sourceLineId"]

    def receive(key, order, quantity):
        stocks = stock_rows(product, wip, order)
        assert sum(Decimal(str(row["quantity"])) for row in stocks) >= quantity, (key, stocks)
        receipt = api.post("/mes/wm/product-receipt/create", {"code": "TST-PQC-PR-" + key,
            "name": "测试·IPQC已放行产品入库·" + key, "workOrderId": order,
            "receiptDate": _stamp(ctx.at("pqc:receipt:" + key)),
            "remark": "TST 只接收本工单IPQC已确认合格数量；不良件留在线边待质量处置"})
        remaining = Decimal(quantity)
        for stock in stocks:
            take = min(remaining, Decimal(str(stock["quantity"])))
            line = api.post("/mes/wm/product-receipt-line/create", {"receiptId": receipt,
                "itemId": product, "materialStockId": stock["id"], "quantity": _n(take),
                "batchId": stock.get("batchId"), "batchCode": stock.get("batchCode")})
            api.post("/mes/wm/product-receipt-detail/create", {"receiptId": receipt, "lineId": line,
                "itemId": product, "quantity": _n(take), "batchId": stock.get("batchId"),
                "warehouseId": physical, "locationId": ctx.ids.get("location", 900001), "areaId": ctx.ids.get("area", 900001)})
            remaining -= take
            if remaining == 0:
                break
        for action in ("submit", "stock", "finish"):
            api.put("/mes/wm/product-receipt/" + action, id=receipt)
        result["receipts"][key] = receipt

    make_order("PENDING6", 6)
    # Pending case deliberately has no fabricated inspection or provisional pass
    # classification: it is visible in the real pending-inspection queue.
    for key, quantity, good, bad, values in [
        ("FAIL10", 10, 8, 2, ["9.98", "10.02", "10.04", "9.99", "10.01", "10.00", "10.03", "10.05", "10.40", "9.60"]),
        ("PASS5", 5, 5, 0, ["9.98", "9.99", "10.00", "10.01", "10.02"]),
    ]:
        order, task, report, source_line = make_order(key, quantity)
        inspected_at = _stamp(ctx.at("pqc:inspect:" + key))
        inspection = api.post("/mes/qc/ipqc/create", {"code": "TST-PQC-IPQC-" + key,
            "name": "测试·逐件尺寸检验·" + key, "type": 5, "sourceDocType": 304,
            "sourceDocId": report, "sourceLineId": source_line, "workOrderId": order,
            "taskId": task, "workstationId": station, "processId": process, "itemId": product,
            "checkQuantity": quantity, "qualifiedQuantity": good, "unqualifiedQuantity": bad,
            "laborScrapQuantity": bad, "materialScrapQuantity": 0, "otherScrapQuantity": 0,
            "checkResult": 2 if bad else 1, "inspectDate": inspected_at, "inspectorUserId": 980005,
            "remark": "TST 合成逐件测量；" + ("样本09为10.40mm、10为9.60mm超差；其余8件合格。不良件待正式处置，不直接结案。" if bad else "5件均在9.90至10.10mm范围内，按实际服务放行。")})
        result["ipqc"][key] = inspection
        result["readings"][key] = []
        for index, value in enumerate(values, 1):
            reading = api.post("/mes/qc/indicator-result/create", {"code": f"TST-PQC-READ-{key}-{index:02d}",
                "qcType": 2, "qcId": inspection, "items": [{"indicatorId": indicator, "value": value,
                    "remark": f"TST 第{index:02d}件；mm；" + ("超差" if Decimal(value) < Decimal("9.90") or Decimal(value) > Decimal("10.10") else "范围内")}],
                "remark": "TST 每件独立样本，检验数量与测量记录件数一致"})
            result["readings"][key].append(reading)
        if bad:
            lines = _rows(api.page("/mes/qc/ipqc/line", ipqcId=inspection))
            assert len(lines) == 1, lines
            result["defectRecord"] = api.post("/mes/qc/defect-record/create", {"qcType": 2, "qcId": inspection,
                "lineId": lines[0]["id"], "defectId": defect, "name": "测试·检验直径超差", "level": 2,
                "quantity": 2, "remark": "TST 样本09、10各一件，未重复累计缺陷件数"})
        api.put("/mes/qc/ipqc/finish", id=inspection)
        saved = api.get("/mes/pro/feedback/get", id=report)
        assert saved["status"] == 4 and Decimal(str(saved["qualifiedQuantity"])) == good, saved
        assert Decimal(str(saved["unqualifiedQuantity"])) == bad and Decimal(str(saved["uncheckQuantity"])) == 0, saved
        output_lines = _rows(api.page("/mes/wm/product-produce-line", feedbackId=report))
        by_quality = {}
        for line in output_lines:
            by_quality[line["qualityStatus"]] = by_quality.get(line["qualityStatus"], Decimal(0)) + Decimal(str(line["quantity"]))
        assert by_quality == ({1: Decimal(good), 2: Decimal(bad)} if bad else {1: Decimal(good)}), (key, output_lines)
        result["produceLines"][key] = output_lines
        receive(key, order, good)
        if bad:
            leftover = stock_rows(product, wip, order)
            assert sum(Decimal(str(row["quantity"])) for row in leftover) == bad, leftover
            assert api.get("/mes/pro/work-order/get", id=order)["status"] == 1
            result["costs"][key] = api.get("/mes/cost/workorder", workorderId=order)
        else:
            api.post("/mes/cost/workorder/effort", {"workorderId": order, "processId": process,
                "hours": "0.5000", "hourlyRate": "60.000000", "overheadAmount": "10.000000",
                "requestId": "TST-PQC-EFFORT-PASS5", "remark": "TST 5件实际工时0.5小时及制造费用10元；人民币未税"})
            api.put("/mes/pro/work-order/finish", id=order)
            api.call("POST", "/mes/cost/workorder/settle", workorderId=order)
            result["costs"][key] = api.get("/mes/cost/workorder", workorderId=order)
            assert result["costs"][key]["status"] == "SETTLED" and not result["costs"][key].get("issues"), result["costs"][key]
    result["checks"] = ["approval_creates_consumption_but_no_uninspected_stock", "actual_ipqc_source_304_claim",
        "fifteen_piece_measurements", "two_defective_pieces_have_defect_evidence", "qualified_receipt_excludes_bad_units",
        "failed_batch_open_with_two_bad_wip_units", "allpass_quality_production_cost_closed"]
    return result


def validation(ctx):
    if not ctx.ids.get("production_quality"):
        return []
    return [
        ("production_quality:order_states", """SELECT ABS(COUNT(*)-3)+ABS(SUM(status=1)-2)+ABS(SUM(status=2)-1)
            FROM mes_pro_work_order WHERE deleted=0 AND code IN('TST-PQC-PENDING6','TST-PQC-FAIL10','TST-PQC-PASS5')"""),
        ("production_quality:pending_classification", """SELECT COUNT(*) FROM mes_pro_feedback
            WHERE deleted=0 AND code='TST-PQC-FB-PENDING6' AND
            (status<>3 OR feedback_quantity<>6 OR uncheck_quantity<>6 OR qualified_quantity<>0 OR unqualified_quantity<>0)"""),
        ("production_quality:no_early_stock", """SELECT COUNT(*) FROM mes_wm_material_stock s JOIN mes_pro_work_order w
            ON w.id=s.work_order_id AND w.tenant_id=s.tenant_id WHERE s.deleted=0 AND s.item_id=w.product_id
            AND w.code='TST-PQC-PENDING6' AND s.quantity<>0"""),
        ("production_quality:failed_feedback_split", """SELECT COUNT(*) FROM mes_pro_feedback
            WHERE deleted=0 AND code='TST-PQC-FB-FAIL10' AND
                (status<>4 OR feedback_quantity<>10 OR qualified_quantity<>8 OR unqualified_quantity<>2 OR uncheck_quantity<>0)"""),
        ("production_quality:actual_good_bad_output_lines", """SELECT
            ABS(COALESCE(SUM(CASE WHEN l.quality_status=1 THEN l.quantity ELSE 0 END),0)-8)
            +ABS(COALESCE(SUM(CASE WHEN l.quality_status=2 THEN l.quantity ELSE 0 END),0)-2)
            FROM mes_wm_product_produce_line l JOIN mes_pro_feedback f ON f.id=l.feedback_id
                AND f.tenant_id=l.tenant_id AND f.deleted=0
            WHERE l.deleted=0 AND f.code='TST-PQC-FB-FAIL10'"""),
        ("production_quality:ipqc_statuses", """SELECT ABS(COUNT(*)-2)+ABS(SUM(status=4)-2)
            +ABS(SUM(check_result=1)-1)+ABS(SUM(check_result=2)-1)
            FROM mes_qc_ipqc WHERE deleted=0 AND code LIKE 'TST-PQC-IPQC-%'"""),
        ("production_quality:ipqc_source_evidence", """SELECT COUNT(*) FROM mes_qc_ipqc q
            LEFT JOIN mes_pro_feedback f ON f.id=q.source_doc_id AND f.tenant_id=q.tenant_id AND f.deleted=0
            LEFT JOIN mes_wm_product_produce p ON p.feedback_id=f.id AND p.tenant_id=f.tenant_id AND p.deleted=0
            LEFT JOIN mes_wm_product_produce_line l ON l.id=q.source_line_id AND l.produce_id=p.id
                AND l.tenant_id=q.tenant_id AND l.deleted=0
            WHERE q.deleted=0 AND q.code LIKE 'TST-PQC-IPQC-%' AND
                (q.source_doc_type<>304 OR f.id IS NULL OR l.id IS NULL OR f.work_order_id<>q.work_order_id OR f.item_id<>q.item_id)"""),
        ("production_quality:all_piece_measurements", """SELECT ABS(COUNT(*)-15) FROM mes_qc_indicator_result
            WHERE deleted=0 AND code LIKE 'TST-PQC-READ-%'"""),
        ("production_quality:actual_consumption", """SELECT ABS(COALESCE(SUM(l.quantity),0)-21)
            FROM mes_wm_item_consume_line l JOIN mes_wm_item_consume c ON c.id=l.consume_id
                AND c.tenant_id=l.tenant_id AND c.deleted=0 AND c.status=4
            JOIN mes_pro_work_order w ON w.id=c.work_order_id AND w.tenant_id=c.tenant_id
            WHERE l.deleted=0 AND w.code IN('TST-PQC-PENDING6','TST-PQC-FAIL10','TST-PQC-PASS5')"""),
        ("production_quality:recorded_defects", """SELECT ABS(COALESCE(SUM(d.quantity),0)-2)
            FROM mes_qc_defect_record d JOIN mes_qc_ipqc q ON q.id=d.qc_id AND q.tenant_id=d.tenant_id AND q.deleted=0
            WHERE d.deleted=0 AND d.qc_type=2 AND q.code='TST-PQC-IPQC-FAIL10'"""),
        ("production_quality:failed_batch_retains_bad_wip", """SELECT ABS(COALESCE(SUM(s.quantity),0)-2)
            FROM mes_wm_material_stock s JOIN mes_pro_work_order w ON w.id=s.work_order_id AND w.tenant_id=s.tenant_id
            WHERE s.deleted=0 AND s.item_id=w.product_id AND w.code='TST-PQC-FAIL10'"""),
        ("production_quality:bad_output_requires_disposition", """SELECT COUNT(*) FROM mes_pro_work_order w
            LEFT JOIN mes_cost_workorder c ON c.workorder_id=w.id AND c.tenant_id=w.tenant_id AND c.deleted=0
            WHERE w.deleted=0 AND w.code='TST-PQC-FAIL10' AND (w.status<>1 OR c.status='SETTLED')"""),
        ("production_quality:allpass_settled", """SELECT COUNT(*) FROM mes_pro_work_order w
            LEFT JOIN mes_cost_workorder c ON c.workorder_id=w.id AND c.tenant_id=w.tenant_id AND c.deleted=0
            WHERE w.deleted=0 AND w.code='TST-PQC-PASS5' AND
                (w.status<>2 OR w.quantity_produced<>5 OR c.id IS NULL OR c.status<>'SETTLED' OR c.actual_unit_cost IS NULL)"""),
        ("production_quality:receipt_quantities", """SELECT ABS(COALESCE(SUM(l.quantity),0)-13)
            FROM mes_wm_product_receipt_line l JOIN mes_wm_product_receipt r ON r.id=l.receipt_id
                AND r.tenant_id=l.tenant_id AND r.deleted=0 AND r.status=4
            WHERE l.deleted=0 AND r.code LIKE 'TST-PQC-PR-%'"""),
    ]
