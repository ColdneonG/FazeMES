"""Deterministic warehouse, commercial, and quality acceptance-test fixtures.

All business documents inserted here are unposted.  Actual stock and its ledger
are produced by the orchestration script through the application's APIs.
``seed_stock_cases`` runs after that API phase and only selects real stock.
"""
from __future__ import annotations

from datetime import timedelta
from decimal import Decimal


SCENARIOS = [
    "采购：多供应商、双物料、整数/小数/大批量到货，草稿、待检、免检待入库。",
    "采购入库：独立采购/关联到货、待上架、部分上架、分库位全量上架、待执行、已取消。",
    "销售：企业/个人客户，免检/需OQC、多行、分批交付、未来交期、逾期、长中文备注。",
    "外协：已确认代工计划下的发料草稿、免检与待IQC收货。",
    "其他收发：库存调整、样品、报废申请，草稿和待执行；尚未形成库存。",
    "仓储：分仓分区分位、禁混物料/禁混批次、停用库位、冻结空库位。",
    "质量：三物料唯一匹配模板，尺寸/外观/功能/文本，抽样上下限及严重度缺陷库。",
    "IQC：到货与外协有效来源、待录入草稿；API完成合格/不合格来料检验。",
    "IPQC：真实已确认工单及工作站，首检、巡检、自检、末检、成品检验。",
    "OQC：手工样本与真实销售待检行；来源物料、客户、批次、数量一致。",
    "RQC：手工样本与真实WIP退料待检行，合格数/不合格数守恒，来源认领一致。",
    "退货：供应商退货草稿与待拣货；销售退货保留真实出库来源并由业务API预留额度。",
    "调拨：真实实体库存的内部/外部调拨草稿、物流与收货确认参数。",
    "盘点：静态明盘/盲盘、动态时间窗、按仓库/物料/质量筛选、启用/停用方案。",
    "所有单据具有同租户主从关系、唯一编码、正数量；完成库存不由SQL伪造。",
]

OPERATIONS_SCENARIOS = [
    "仓库实证：100件带价采购、20件跨仓分库位调拨、5件原批次退供应商。",
    "外协实证：独立代工工单，5件垫片发出及5件去毛刺成品验收入库，原料与成品独立编码。",
    "无批次辅料实证：20件无偿样品入库、4件样品测试消耗；保留缺实际成本状态。",
    "生产退料实证：在制工单螺栓2件余料合格回仓、2件不良RQC后隔离；继续生产需补料。",
]

BASE_ITEMS = (900101, 900102, 900103)
QC_BASE = 986000
POST_BASE = 988000
REMARK = "全合成测试数据，无真实业务及个人联系方式；未执行库存过账。"


def _add(ctx, table, number, **values):
    return ctx.add(table, id=QC_BASE + number, **values)


def _post(ctx, table, number, **values):
    return ctx.add(table, id=POST_BASE + number, **values)


def _qc_lines(ctx, kind, qc_id, template_index, post=False):
    add = _post if post else _add
    for offset, indicator in enumerate(_indicators(template_index), 1):
        iid, tool, method, target, minimum, maximum, unit = indicator
        number = (qc_id - (POST_BASE if post else QC_BASE)) * 10 + offset
        add(ctx, f"mes_qc_{kind}_line", number, **{
            f"{kind}_id": qc_id, "indicator_id": iid,
            "tool": tool, "check_method": method, "standard_value": target,
            "min_threshold": minimum, "max_threshold": maximum,
            "unit_measure_id": unit, "critical_quantity": 0,
            "major_quantity": 0, "minor_quantity": 0,
            "remark": "模板受控快照，等待检验员确认。",
        })


def _indicators(item_index):
    # Dimension and torque readings have their own units, never the item count unit.
    dimensions = [
        (986001, "数显卡尺（0.01 mm）", "每批随机抽样，测量两端及中部", 50, Decimal("49.95"), Decimal("50.05"), 986101),
        (986002, "螺纹通止规", "通规通过、止规不通过", 1, 1, 1, 900001),
        (986003, "数字扭矩扳手", "按工艺顺序紧固后复核", 12, Decimal("11.4"), Decimal("12.6"), 986102),
    ]
    return [dimensions[item_index],
            (986004, "标准光源箱", "目视：无裂纹、毛刺、锈蚀，标识完整", 1, 1, 1, 900001),
            (986005, "检验工位", "记录材质批次及包装完整性", 0, 0, 0, None)]


def seed(ctx):
    """Insert rich, legal, unposted commercial/warehouse/QC fixtures."""
    now = ctx.now
    for number, code, name in [(101, "TEST-MM", "毫米"), (102, "TEST-NM", "牛米"), (103, "TEST-KG", "千克")]:
        _add(ctx, "mes_md_unit_measure", number, code=code, name=name,
             primary_flag=True, primary_id=0, change_rate=1, status=0,
             remark="测试检验单位，与物料计件单位独立。")

    vendors = ["澄川精密材料（测试）有限公司", "星屿紧固件（测试）有限公司",
               "云杉表面处理（测试）有限公司", "衡岳机加（测试）有限公司",
               "远汀包装（测试）有限公司", "清禾工装（测试）有限公司",
               "松原测量（测试）有限公司", "锦陌综合供应（测试）有限公司",
               "停用供应商（测试）有限公司", "历史供应商（测试）有限公司"]
    clients = ["栖川智能装备（测试）有限公司", "青屿自动化（测试）有限公司",
               "云麓工业（测试）有限公司", "澄光传动（测试）有限公司",
               "禾川机器人（测试）有限公司", "北湾机电（测试）有限公司",
               "测试个人客户甲", "测试个人客户乙", "停用客户（测试）有限公司", "历史客户（测试）有限公司"]
    for i, (vendor, client) in enumerate(zip(vendors, clients), 1):
        common = dict(address=f"虚构市测试工业园合成路{i}号（测试地址）",
                      description="合成验收测试主体；名称及地址仅用于测试，不对应真实组织。",
                      status=0 if i <= 8 else 1, remark=REMARK)
        _add(ctx, "mes_md_vendor", i, code=f"TST-VEN-{i:03d}", name=vendor,
             nickname=vendor.split("（")[0], level="ABC"[(i - 1) % 3],
             score=[98, 95, 92, 88, 85, 80, 76, 70, 60, 50][i - 1], **common)
        _add(ctx, "mes_md_client", i, code=f"TST-CLI-{i:03d}", name=client,
             nickname=client.split("（")[0], type=2 if i in (7, 8) else 1, **common)
    ctx.ids["inventory_vendors"] = list(range(986001, 986009))
    ctx.ids["inventory_clients"] = list(range(986001, 986009))

    for w, title in enumerate(["原料周转仓", "成品交付仓", "售后待处理仓"], 1):
        wid = _add(ctx, "mes_wm_warehouse", w, code=f"TST-WH-{w:02d}", name=title,
                   address=f"虚构测试园区{w}号仓", area=600 + w * 100,
                   charge_user_id=980002, frozen=False, remark=REMARK)
        for zone in range(1, 3):
            lid = _add(ctx, "mes_wm_warehouse_location", w * 10 + zone,
                       code=f"TST-WH-{w:02d}-L{zone}", name=f"{title}{zone}区",
                       warehouse_id=wid, area=300, frozen=False, remark=REMARK)
            for slot in range(1, 5):
                _add(ctx, "mes_wm_warehouse_area", w * 100 + zone * 10 + slot,
                     code=f"TST-WH-{w:02d}-L{zone}-A{slot:02d}",
                     name=f"{title}{zone}区{slot}号位", location_id=lid,
                     area=12, max_load=500, position_x=slot, position_y=zone,
                     position_z=1, status=1 if w == 3 and zone == 2 and slot == 4 else 0,
                     frozen=w == 3 and zone == 2 and slot == 3,
                     allow_item_mixing=slot != 1, allow_batch_mixing=slot != 2,
                     remark="空库位；单物料/单批次/冻结/停用配置覆盖。")
    ctx.ids["inventory_destination"] = {"warehouse_id": 986001, "location_id": 986011, "area_id": 986113}

    indicators = [
        (1, "外径", 1, "数显卡尺（0.01 mm）", 1),
        (2, "螺纹通止", 1, "螺纹通止规", 2),
        (3, "装配扭矩", 3, "数字扭矩扳手", 1),
        (4, "外观完整性", 2, "标准光源箱", 2),
        (5, "批次与包装说明", 4, "检验工位", 3),
    ]
    for i, name, itype, tool, result_type in indicators:
        _add(ctx, "mes_qc_indicator", i, code=f"TST-QI-{i:03d}", name=name,
             type=itype, tool=tool, result_type=result_type, remark="通用受控检验指标。")
    for i, (name, level) in enumerate([
        ("结构裂纹", 1), ("错料混料", 1), ("螺纹滑牙", 2), ("关键尺寸超差", 2),
        ("扭矩不足", 2), ("表面轻微划伤", 3), ("包装标签不清", 3), ("表面油污", 3)], 1):
        _add(ctx, "mes_qc_defect", i, code=f"TST-DEF-{i:03d}", name=name,
             type=1, level=level, remark="测试缺陷字典：同一实物不可重复计入不良总数。")
    for item_index, item_id in enumerate(BASE_ITEMS):
        tid = _add(ctx, "mes_qc_template", item_index + 1,
                   code=f"TST-QT-{item_id}", name=["毛坯综合检验规范", "螺栓综合检验规范", "组件综合检验规范"][item_index],
                   types="1,2,3,4", ipqc_types="1,2,3,4,5,6", status=0,
                   remark="每物料每检验类型唯一启用模板；支持首巡自末检。")
        _add(ctx, "mes_qc_template_item", item_index + 1, template_id=tid, item_id=item_id,
             quantity_check=5, quantity_unqualified=0, critical_rate=0,
             major_rate=Decimal("1.0"), minor_rate=Decimal("2.5"), remark="最小抽样5件，关键缺陷零接收。")
        for j, ind in enumerate(_indicators(item_index), 1):
            iid, tool, method, target, minimum, maximum, unit = ind
            _add(ctx, "mes_qc_template_indicator", item_index * 10 + j, template_id=tid,
                 indicator_id=iid, check_method=method, standard_value=target,
                 unit_measure_id=unit, threshold_min=minimum, threshold_max=maximum,
                 remark=f"受控工具：{tool}")
    ctx.ids["inventory_templates"] = {item: 986001 + i for i, item in enumerate(BASE_ITEMS)}

    # Arrival states are 0 draft, 2 pending IQC, 3 ready for receiving.
    iqc_no = 0
    for n in range(1, 37):
        vendor = 986001 + (n - 1) % 8
        status = (0, 2, 3)[(n - 1) % 3]
        code = f"TST-ARR-{n:04d}"
        arrival_when = (now + timedelta(days=(n % 17) - 8) if status == 0 else
                        now - timedelta(days=1) if status == 2 else
                        now - timedelta(days=n % 8))
        aid = _add(ctx, "mes_wm_arrival_notice", n, code=code,
                   name=f"{['毛坯及紧固件','急单补料','周期采购'][n % 3]}到货通知{n:02d}",
                   purchase_order_code=f"TST-PO-{n:04d}", vendor_id=vendor,
                   arrival_date=arrival_when, status=status, remark=REMARK)
        for j, item in enumerate(BASE_ITEMS[:2], 1):
            qty = (Decimal("0.5") if n == 1 and j == 1 else
                   Decimal("100000") if n == 2 and j == 2 else Decimal(30 + n * 7) * j)
            need_qc = status == 2 or status == 0 and n % 2 == 0
            lid = _add(ctx, "mes_wm_arrival_notice_line", n * 10 + j,
                       notice_id=aid, item_id=item, arrival_quantity=qty,
                       qualified_quantity=qty if not need_qc else 0,
                       iqc_check_flag=need_qc, remark="小数精度边界" if qty < 1 else REMARK)
            if status == 2:
                iqc_no += 1
                checked = qty  # IQC VO requires good + bad == received, including drafts.
                qid = _add(ctx, "mes_qc_iqc", iqc_no, code=f"TST-IQC-ARR-{iqc_no:04d}",
                           name=f"到货来料检验{n:02d}-{j}", template_id=986000 + j,
                           source_doc_type=100, source_doc_id=aid, source_line_id=lid,
                           source_doc_code=code, vendor_id=vendor, vendor_batch=f"TST-SUPLOT-{n:04d}-{j}",
                           item_id=item, received_quantity=qty, check_quantity=checked,
                           qualified_quantity=checked, unqualified_quantity=0,
                           critical_rate=0, major_rate=1, minor_rate=Decimal("2.5"),
                           check_result=None, receive_date=arrival_when,
                           inspect_date=now, inspector_user_id=980005, status=0, remark=REMARK)
                _qc_lines(ctx, "iqc", qid, j - 1)
        # Ready notices can legally have a draft receipt tied to their source lines.
        if status == 3:
            _receipt_case(ctx, n, vendor, aid, now, state=(0, 2, 3, 5)[(n // 3 - 1) % 4])
    ctx.ids["inventory_arrival_iqc_count"] = iqc_no

    # Independent receipts cover creation without arrival notice.
    for n in range(40, 46):
        _receipt_case(ctx, n, 986001 + n % 8, None, now, state=0)

    for n in range(1, 37):
        cid = 986001 + (n - 1) % 8
        nid = _add(ctx, "mes_wm_sales_notice", n, code=f"TST-SN-{n:04d}",
                   name=f"组件交付通知{n:02d}", sales_order_code=f"TST-SO-{n:04d}", client_id=cid,
                   sales_date=now + timedelta(days=(n % 21) - 7),
                   recipient_name="测试收货岗位", recipient_address=f"虚构测试市交付验证路{n}号",
                   status=0 if n % 3 == 0 else 3,
                   remark=("多批次分批交付；先完成出货检验，再拣货登记运单。" * 6) if n == 1 else REMARK)
        _add(ctx, "mes_wm_sales_notice_line", n * 10 + 1, notice_id=nid, item_id=900103,
             quantity=Decimal("0.125") if n == 1 else 10 + n * 3,
             oqc_check_flag=n % 2 == 0, remark="需出货检验" if n % 2 == 0 else "按客户协议免检")
        if n % 4 == 0:
            _add(ctx, "mes_wm_sales_notice_line", n * 10 + 2, notice_id=nid,
                 item_id=900102, quantity=40 + n, oqc_check_flag=False, remark="随主件交付的备件螺栓。")

    for n in range(1, 13):
        vendor = 986001 + n % 8
        _add(ctx, "mes_wm_outsource_issue", n, code=f"TST-OSI-{n:04d}", name=f"外协备料申请{n:02d}",
             vendor_id=vendor, work_order_id=None, issue_date=now + timedelta(days=n % 5), status=0, remark=REMARK)
        for j, item in enumerate(BASE_ITEMS[:2], 1):
            _add(ctx, "mes_wm_outsource_issue_line", n * 10 + j, issue_id=986000 + n,
                 item_id=item, quantity=n * 5 * j, material_stock_id=None, batch_id=None,
                 remark="草稿未指定批次，提交后由仓管选择真实库存。")
        code = f"TST-OSR-{n:04d}"
        need_qc = n % 2 == 0
        rid = _add(ctx, "mes_wm_outsource_receipt", n, code=code, name=f"外协加工收货{n:02d}",
                   vendor_id=vendor, work_order_id=None, receipt_date=now - timedelta(days=n % 7),
                   status=1 if need_qc else 0, remark=REMARK)
        qty = Decimal(n * 8)
        bid = _batch(ctx, 200 + n, 900103, vendor, now, quality=0 if need_qc else 1)
        lid = _add(ctx, "mes_wm_outsource_receipt_line", n, receipt_id=rid, item_id=900103,
                   quantity=qty, batch_id=bid, batch_code=f"TST-INBATCH-{200+n:04d}",
                   production_date=now - timedelta(days=15), expire_date=now + timedelta(days=350),
                   lot_number=f"TST-OSLOT-{n:04d}", iqc_check_flag=need_qc,
                   quality_status=0 if need_qc else 1, remark=REMARK)
        if need_qc:
            iqc_no += 1
            qid = _add(ctx, "mes_qc_iqc", iqc_no, code=f"TST-IQC-OS-{n:04d}", name=f"外协来料检验{n:02d}",
                       template_id=986003, source_doc_type=121, source_doc_id=rid, source_line_id=lid,
                       source_doc_code=code, vendor_id=vendor, vendor_batch=f"TST-OSLOT-{n:04d}",
                       item_id=900103, received_quantity=qty, check_quantity=qty,
                       qualified_quantity=qty, unqualified_quantity=0, check_result=None,
                       inspect_date=now, receive_date=now - timedelta(days=n % 7),
                       inspector_user_id=980005, status=0, remark=REMARK)
            _qc_lines(ctx, "iqc", qid, 2)

    for n in range(1, 13):
        rid = _add(ctx, "mes_wm_misc_receipt", n, code=f"TST-MR-{n:04d}", name=f"样品及盘盈待确认入库{n:02d}",
                   type=1, source_doc_type=None, receipt_date=now, status=0 if n % 2 else 3, remark=REMARK)
        for j, item in enumerate(BASE_ITEMS[:2], 1):
            _add(ctx, "mes_wm_misc_receipt_line", n * 10 + j, receipt_id=rid, item_id=item,
                 quantity=Decimal("1.250000") if j == 1 else 5 + n,
                 warehouse_id=986001, location_id=986011, area_id=986113,
                 remark="无来源的人工调整，需核实后执行；当前没有库存过账。")
        _add(ctx, "mes_wm_misc_issue", n, code=f"TST-MI-{n:04d}", name=f"{'报废' if n % 2 else '调整'}出库申请{n:02d}",
             type=2 if n % 2 else 1, source_doc_type=None, issue_date=now, status=0,
             remark="草稿待选择真实可出库库存；不得以隔离不良品伪造普通出库。")
        _add(ctx, "mes_wm_return_vendor", n, code=f"TST-RV-{n:04d}", name=f"供应商退货申请{n:02d}",
             vendor_id=986001 + (n - 1) % 8, purchase_order_code=f"TST-PO-{n:04d}", return_date=now,
             return_reason=["采购规格调整", "重复到货待核实", "包装破损待供应商确认"][n % 3], status=0, remark=REMARK)
        _add(ctx, "mes_wm_return_vendor_line", n, return_id=986000 + n,
             item_id=900101 if n % 2 else 900102, quantity=1 + n, remark="草稿尚未拣选批次。")
        _add(ctx, "mes_wm_return_sales", n, code=f"TST-RS-{n:04d}", name=f"客户退货登记{n:02d}",
             client_id=986001 + (n - 1) % 8, sales_order_code=f"TST-SO-{n:04d}", return_date=now,
             return_reason="客户申请待核验原销售出库流水，尚未创建退货行。", status=0, remark=REMARK)

    # Manual QC is a supported workflow, source IDs are deliberately NULL.
    for kind, qtype in [("oqc", 3), ("rqc", 4)]:
        for n in range(1, 7):
            item_index = (n - 1) % 3
            extra = dict(client_id=986001, out_quantity=5, min_check_quantity=5,
                         max_unqualified_quantity=0, out_date=now) if kind == "oqc" else dict(type=1)
            qid = _add(ctx, f"mes_qc_{kind}", n, code=f"TST-{kind.upper()}-MAN-{n:04d}",
                       name=f"独立{'出货' if kind == 'oqc' else '退货'}样品检验{n:02d}",
                       template_id=986001 + item_index, source_doc_type=0,
                       source_doc_id=None, source_line_id=None, source_doc_code=None,
                       item_id=BASE_ITEMS[item_index], check_quantity=5, qualified_quantity=5,
                       unqualified_quantity=0, check_result=None, inspect_date=now,
                       inspector_user_id=980006, status=0, remark="手工检验草稿，无库存回写。", **extra)
            _qc_lines(ctx, kind, qid, item_index)

    for n in range(1, 9):
        dynamic = n in (5, 6)
        pid = _add(ctx, "mes_wm_stock_taking_plan", n, code=f"TST-STP-{n:03d}",
                   name=["原料静态明盘", "成品静态盲盘", "按物料抽盘", "按质量状态抽盘",
                         "动态当班盘点", "动态夜班盘点", "停用方案供编辑", "冻结静态盘点方案"][n - 1],
                   type=2 if dynamic else 1,
                   start_time=(now - timedelta(hours=4)) if dynamic else None,
                   end_time=(now + timedelta(hours=4)) if dynamic else None,
                   blind_flag=n % 2 == 0, frozen=n == 8, status=1 if n == 7 else 0, remark=REMARK)
        ptype, value, code, name = (
            (600, 900101, "DEMO-RAW-001", "演示铝合金毛坯") if n == 3 else
            (900, 1, "1", "合格") if n == 4 else
            (102, 986001 if n == 1 else 900001,
             "TST-WH-01" if n == 1 else "DEMO-WH", "原料周转仓" if n == 1 else "演示原料成品仓"))
        _add(ctx, "mes_wm_stock_taking_plan_param", n, plan_id=pid, type=ptype,
             value_id=value, value_code=code, value_name=name, remark="有效范围参数；启用方案均具备筛选项。")


def _batch(ctx, n, item_id, vendor_id, now, quality=1):
    return _add(ctx, "mes_wm_batch", n, code=f"TST-INBATCH-{n:04d}", item_id=item_id,
                vendor_id=vendor_id, produce_date=now - timedelta(days=15),
                expire_date=now + timedelta(days=350), receipt_date=now,
                lot_number=f"TST-LOT-{n:04d}", quality_status=quality,
                remark="待入库批次元数据，不代表已存在实物库存。")


def _receipt_case(ctx, n, vendor, notice_id, now, state):
    rid = _add(ctx, "mes_wm_item_receipt", n, code=f"TST-REC-{n:04d}",
               name=f"采购入库验收案例{n:02d}", notice_id=notice_id, vendor_id=vendor,
               purchase_order_code=f"TST-PO-{n:04d}", receipt_date=now, status=state, remark=REMARK)
    for j, item_id in enumerate(BASE_ITEMS[:2], 1):
        qty = Decimal(30 + n * 7) * j if notice_id else Decimal(25 * j)
        bid = _batch(ctx, n * 2 + j, item_id, vendor, now)
        lid = _add(ctx, "mes_wm_item_receipt_line", n * 10 + j,
                   receipt_id=rid, arrival_notice_line_id=n * 10 + j + QC_BASE if notice_id else None,
                   item_id=item_id, received_quantity=qty, batch_id=bid,
                   batch_code=f"TST-INBATCH-{n*2+j:04d}", production_date=now - timedelta(days=15),
                   expire_date=now + timedelta(days=350), lot_number=f"TST-LOT-{n*2+j:04d}", remark=REMARK)
        if state in (2, 3):
            allocations = [qty / 2] if state == 2 else [qty / 2, qty / 2]
            for k, allocated in enumerate(allocations, 1):
                _add(ctx, "mes_wm_item_receipt_detail", n * 100 + j * 10 + k,
                     receipt_id=rid, line_id=lid, item_id=item_id, quantity=allocated, batch_id=bid,
                     warehouse_id=986001, location_id=986011, area_id=986112 + k,
                     remark="部分上架" if state == 2 else "两库位拆分，数量守恒。")


def seed_stock_cases(ctx):
    """Call after manufacturing API flows; creates no ledger or stock rows."""
    now = ctx.now
    stocks = ctx.query("""SELECT s.*,w.code warehouse_code FROM mes_wm_material_stock s
        JOIN mes_wm_warehouse w ON w.id=s.warehouse_id AND w.deleted=0 AND w.tenant_id=s.tenant_id
        WHERE s.deleted=0 AND s.tenant_id=1 AND s.quantity>=10 AND s.frozen=0
        AND COALESCE(s.quality_hold,0)=0
        AND w.code NOT IN ('WIP_VIRTUAL_WAREHOUSE','QUALITY_QUARANTINE_WAREHOUSE')
        AND s.work_order_id IS NULL ORDER BY s.id LIMIT 18""")
    for n, stock in enumerate(stocks, 1):
        qty = min(Decimal(stock["quantity"]) / 10, Decimal(5))
        iid, bid, bcode, sid = [stock[x] for x in ("item_id", "batch_id", "batch_code", "id")]
        location = {x: stock[x] for x in ("warehouse_id", "location_id", "area_id")}
        # Draft allocation references real inventory but does not reserve or move it.
        issue = _post(ctx, "mes_wm_misc_issue", n, code=f"TST-MIS-STK-{n:03d}",
                      name="真实库存调整申请", type=1, source_doc_type=None,
                      issue_date=now, status=0, remark=REMARK)
        _post(ctx, "mes_wm_misc_issue_line", n, issue_id=issue, material_stock_id=sid,
              item_id=iid, quantity=qty, batch_id=bid, batch_code=bcode, **location, remark=REMARK)
        tr = _post(ctx, "mes_wm_transfer", n, code=f"TST-TR-{n:03d}",
                   name="内部库位优化" if n % 2 else "外部协作仓调拨", type=1 if n % 2 else 2,
                   delivery_flag=n % 2 == 0, confirm_flag=False,
                   recipient_name="测试仓管岗位" if n % 2 == 0 else None,
                   destination_address="虚构协作仓测试路1号" if n % 2 == 0 else None,
                   transfer_date=now, status=0, remark=REMARK)
        _post(ctx, "mes_wm_transfer_line", n, transfer_id=tr, material_stock_id=sid,
              item_id=iid, quantity=qty, batch_id=bid,
              from_warehouse_id=stock["warehouse_id"], from_location_id=stock["location_id"],
              from_area_id=stock["area_id"], remark="草稿未移出库存。")
        if iid in BASE_ITEMS:
            # No notice linkage means there is no notice fulfillment reservation to fake.
            sales = _post(ctx, "mes_wm_product_sales", n, code=f"TST-PS-OQC-{n:03d}",
                          name="待出货检验样单", client_id=986001, sales_order_code=f"TST-SO-QC-{n:03d}",
                          notice_id=None, sales_date=now, contact_name="测试收货岗位",
                          contact_address="虚构测试市验收路1号", status=1, remark=REMARK)
            sl = _post(ctx, "mes_wm_product_sales_line", n, sales_id=sales, item_id=iid,
                       quantity=qty, batch_id=bid, batch_code=bcode, material_stock_id=None,
                       oqc_check_flag=True, quality_status=0, remark="需OQC，尚未拣货。")
            index = BASE_ITEMS.index(iid)
            qc = _post(ctx, "mes_qc_oqc", n, code=f"TST-OQC-SRC-{n:03d}", name="真实销售来源出货检验",
                       template_id=986001 + index, source_doc_type=118, source_doc_id=sales,
                       source_line_id=sl, source_doc_code=f"TST-PS-OQC-{n:03d}", client_id=986001,
                       batch_code=bcode, item_id=iid, min_check_quantity=min(5, int(qty)), max_unqualified_quantity=0,
                       out_quantity=qty, check_quantity=qty, qualified_quantity=qty, unqualified_quantity=0,
                       check_result=None, out_date=now, inspect_date=now, inspector_user_id=980005,
                       status=0, remark=REMARK)
            _qc_lines(ctx, "oqc", qc, index, post=True)

    workorders = ctx.query("""SELECT o.id,o.product_id,s.id workstation_id,s.process_id FROM mes_pro_work_order o
        JOIN mes_pro_route_product p ON p.item_id=o.product_id AND p.deleted=0 AND p.tenant_id=o.tenant_id
        JOIN mes_pro_route_process r ON r.route_id=p.route_id AND r.deleted=0 AND r.tenant_id=o.tenant_id
        JOIN mes_md_workstation s ON s.process_id=r.process_id AND s.deleted=0 AND s.status=0 AND s.tenant_id=o.tenant_id
        WHERE o.deleted=0 AND o.tenant_id=1 AND o.status=1 AND o.product_id=900103
        ORDER BY o.id,s.id LIMIT 1""")
    if workorders:
        wo = workorders[0]
        for n in range(1, 7):
            qc = _post(ctx, "mes_qc_ipqc", n, code=f"TST-IPQC-{n:03d}",
                       name=["工序检验", "首件检验", "当班巡检", "工位自检", "成品检验", "末件检验"][n - 1],
                       type=n, template_id=986003, source_doc_type=None,
                       source_doc_id=None, source_line_id=None, work_order_id=wo["id"],
                       workstation_id=wo["workstation_id"], process_id=wo["process_id"],
                       item_id=900103, check_quantity=5, qualified_quantity=5, unqualified_quantity=0,
                       labor_scrap_quantity=0, material_scrap_quantity=0, other_scrap_quantity=0,
                       check_result=None, inspect_date=now, inspector_user_id=980006, status=0, remark=REMARK)
            _qc_lines(ctx, "ipqc", qc, 2, post=True)

    wip = ctx.query("""SELECT s.*,o.status order_status,st.id workstation_id
        FROM mes_wm_material_stock s JOIN mes_pro_work_order o
        ON o.id=s.work_order_id AND o.tenant_id=s.tenant_id AND o.deleted=0
        JOIN mes_wm_warehouse w ON w.id=s.warehouse_id AND w.tenant_id=s.tenant_id
        JOIN mes_md_workstation st ON st.warehouse_id=s.warehouse_id AND st.tenant_id=s.tenant_id AND st.deleted=0 AND st.status=0
        WHERE s.deleted=0 AND s.tenant_id=1 AND s.quantity>=5 AND s.frozen=0 AND COALESCE(s.quality_hold,0)=0
        AND w.code='WIP_VIRTUAL_WAREHOUSE' AND s.item_id IN (900101,900102,900103)
        AND o.status=1 ORDER BY s.id,st.id LIMIT 36""")
    seen = set()
    for stock in wip:
        if stock["id"] in seen:
            continue
        seen.add(stock["id"])
        n = len(seen)
        qty = Decimal(2)
        qc_id = POST_BASE + n
        doc = _post(ctx, "mes_wm_return_issue", n, code=f"TST-RI-{n:03d}",
                    name="线边余料待检退仓", work_order_id=stock["work_order_id"],
                    workstation_id=stock["workstation_id"], type=1, return_date=now, status=1, remark=REMARK)
        line = _post(ctx, "mes_wm_return_issue_line", n, issue_id=doc,
                     material_stock_id=stock["id"], item_id=stock["item_id"], quantity=qty,
                     batch_id=stock["batch_id"], batch_code=stock["batch_code"],
                     rqc_id=qc_id, rqc_check_flag=True, quality_status=0, remark=REMARK)
        index = BASE_ITEMS.index(stock["item_id"])
        qc = _post(ctx, "mes_qc_rqc", n, code=f"TST-RQC-SRC-{n:03d}", name="真实线边退料检验",
                   template_id=986001 + index, source_doc_type=116, source_doc_id=doc,
                   source_line_id=line, source_doc_code=f"TST-RI-{n:03d}", type=1,
                   item_id=stock["item_id"], batch_code=stock["batch_code"], check_quantity=qty,
                   qualified_quantity=qty, unqualified_quantity=0, check_result=None,
                   inspect_date=now, inspector_user_id=980006, status=0, remark=REMARK)
        _qc_lines(ctx, "rqc", qc, index, post=True)


def seed_api(api, ctx):
    """Post commerce and quality through real services, including source budgets.

    Call after manufacturing and before ``seed_stock_cases``.  Every required
    transition is executed through its controller; a failure aborts the suite.
    """
    now = ctx.now.replace(microsecond=0).isoformat()
    product, client = 900103, 986001
    result = {"notices": [], "sales": [], "returns": [], "quality": []}
    ctx.ids["inventory_api"] = result

    def rows(value):
        return value.get("list", []) if isinstance(value, dict) else value

    def number(value):
        d = Decimal(str(value))
        return int(d) if d == d.to_integral_value() else float(d)

    def reading(kind, qid, item, *, rejected=0):
        qtype = {"iqc": 1, "ipqc": 2, "oqc": 3, "rqc": 4}[kind]
        index = BASE_ITEMS.index(item)
        items = []
        for offset, ind in enumerate(_indicators(index)):
            value = str(ind[3]) if ind[0] != 986005 else "合成样本：材质批次可追溯，包装完整。"
            if rejected and offset == 0:
                value = "51.00" if index == 0 else "0" if index == 1 else "9.00"
            items.append({"indicatorId": ind[0], "value": value, "remark": "实测合成样本，无真实产品。"})
        api.post("/mes/qc/indicator-result/create", {
            "code": f"TST-COM-READ-{kind.upper()}-{qid}", "qcType": qtype,
            "qcId": qid, "items": items, "remark": "验收测试测量记录，保留真实API审计时间。"})
        if rejected:
            qc_lines = rows(api.page(f"/mes/qc/{kind}/line", **{f"{kind}Id": qid}))
            if not qc_lines:
                raise AssertionError(("Missing generated QC lines", kind, qid))
            defect_id = 986004 if index == 0 else 986003 if index == 1 else 986005
            api.post("/mes/qc/defect-record/create", {
                "qcType": qtype, "qcId": qid, "lineId": qc_lines[0]["id"],
                "defectId": defect_id, "name": "关键尺寸或功能超差", "level": 2,
                "quantity": int(rejected), "remark": "同一不良件仅记录一次主缺陷。"})

    # Complete the two source IQCs of a genuine pending arrival fixture.
    for qid, rejected in [(986001, 0), (986002, 2)]:
        qc = api.get("/mes/qc/iqc/get", id=qid)
        total = Decimal(str(qc["receivedQuantity"]))
        payload = {key: qc.get(key) for key in (
            "id", "code", "name", "sourceDocType", "sourceDocId", "sourceLineId",
            "vendorId", "vendorBatch", "itemId", "receiveDate")}
        payload.update({"receivedQuantity": number(total), "qualifiedQuantity": number(total - rejected),
                        "unqualifiedQuantity": rejected, "checkResult": 2 if rejected else 1,
                        "inspectDate": now, "inspectorUserId": 980005,
                        "remark": "API完成的真实到货来源检验；合格与不合格数量守恒。"})
        api.call("PUT", "/mes/qc/iqc/update", payload)
        reading("iqc", qid, qc["itemId"], rejected=rejected)
        api.put("/mes/qc/iqc/finish", id=qid)
        result["quality"].append({"type": "iqc", "id": qid, "rejected": rejected})

    def notice(suffix, quantity):
        nid = api.post("/mes/wm/sales-notice/create", {
            "code": "TST-COM-NOTICE-" + suffix, "name": "测试·" + suffix + "交付",
            "salesOrderCode": "TST-COM-SO-" + suffix, "clientId": client, "salesDate": now,
            "recipientName": "测试收货岗位", "recipientAddress": "虚构测试市交付验证路1号",
            "remark": "多次销售出库验证同一通知的实际履约量。"})
        nlid = api.post("/mes/wm/sales-notice-line/create", {
            "noticeId": nid, "itemId": product, "quantity": quantity, "oqcCheckFlag": True,
            "remark": "每一批发运独立OQC，无预设批次约束。"})
        api.put("/mes/wm/sales-notice/submit", id=nid)
        result["notices"].append(nid)
        return nid, nlid

    def make_sale(suffix, quantity, parent=None, *, rejected=0, execute=True):
        # A resumed journal may already have posted this sale.  Recover its exact
        # allocation first, so replay payloads never drift to a different batch.
        code = "TST-COM-SALE-" + suffix
        available = ctx.query(f"""SELECT s.* FROM mes_wm_material_stock s
            JOIN mes_wm_product_sales_detail d ON d.material_stock_id=s.id AND d.deleted=0 AND d.tenant_id=s.tenant_id
            JOIN mes_wm_product_sales p ON p.id=d.sales_id AND p.deleted=0 AND p.tenant_id=s.tenant_id
            WHERE s.tenant_id=1 AND s.deleted=0 AND p.code='{code}' ORDER BY d.id LIMIT 1""")
        if not available:
            available = ctx.query(f"""SELECT s.* FROM mes_wm_material_stock s
                JOIN mes_wm_product_sales_line l ON l.item_id=s.item_id AND l.batch_id<=>s.batch_id
                AND l.tenant_id=s.tenant_id AND l.deleted=0
                JOIN mes_wm_product_sales p ON p.id=l.sales_id AND p.deleted=0 AND p.tenant_id=s.tenant_id
                WHERE s.tenant_id=1 AND s.deleted=0 AND s.warehouse_id=900001
                AND p.code='{code}' ORDER BY s.id DESC LIMIT 1""")
        if not available:
            available = ctx.query("""SELECT * FROM mes_wm_material_stock WHERE tenant_id=1 AND deleted=0
            AND warehouse_id=900001 AND item_id=900103 AND quantity>=6 AND frozen=0
            AND COALESCE(quality_hold,0)=0 AND work_order_id IS NULL ORDER BY id DESC LIMIT 1""")
        if not available:
            raise AssertionError("Manufacturing did not produce available physical stock for commerce")
        stock = available[0]
        sid = api.post("/mes/wm/product-sales/create", {
            "code": code, "name": "测试·销售交付·" + suffix,
            "clientId": client, "salesOrderCode": "TST-COM-SO-" + ("FULL" if parent and parent == full else "PARTIAL"),
            "noticeId": parent[0] if parent else None, "salesDate": now,
            "contactName": "测试收货岗位", "contactAddress": "虚构测试市交付验证路1号",
            "remark": "真实成品库存、独立OQC与实际销售单价。"})
        line = api.post("/mes/wm/product-sales-line/create", {
            "salesId": sid, "noticeLineId": parent[1] if parent else None,
            "itemId": product, "quantity": quantity, "batchCode": stock["batch_code"],
            "oqcCheckFlag": True, "remark": "与实际拣货批次一致。"})
        api.call("PUT", "/mes/cost/sales-line-price", {
            "lineId": line, "unitPrice": "88.000000", "remark": "合成人民币未税销售单价。"})
        api.put("/mes/wm/product-sales/submit", id=sid)
        qc = api.post("/mes/qc/oqc/create", {
            "code": "TST-COM-OQC-" + suffix, "name": "测试·发运检验·" + suffix,
            "sourceDocType": 118, "sourceDocId": sid, "sourceLineId": line,
            "clientId": client, "batchCode": stock["batch_code"], "itemId": product,
            "outQuantity": quantity, "checkQuantity": quantity,
            "qualifiedQuantity": quantity - rejected, "unqualifiedQuantity": rejected,
            "checkResult": 2 if rejected else 1, "outDate": now, "inspectDate": now,
            "inspectorUserId": 980005, "remark": "严格绑定真实销售行。"})
        reading("oqc", qc, product, rejected=rejected)
        api.put("/mes/qc/oqc/finish", id=qc)
        result["quality"].append({"type": "oqc", "id": qc, "rejected": rejected})
        if execute:
            api.post("/mes/wm/product-sales-detail/create", {
                "salesId": sid, "lineId": line, "itemId": product, "quantity": quantity,
                "materialStockId": stock["id"], "batchId": stock["batch_id"],
                "batchCode": stock["batch_code"], "warehouseId": stock["warehouse_id"],
                "locationId": stock["location_id"], "areaId": stock["area_id"],
                "remark": "实际可用成品拣货。"})
            api.put("/mes/wm/product-sales/stock", id=sid)
            api.call("PUT", "/mes/wm/product-sales/shipping", {
                "id": sid, "carrier": "合成测试承运方", "shippingNumber": "TST-TRACK-" + suffix})
            api.put("/mes/wm/product-sales/finish", id=sid)
        result["sales"].append(sid)
        return sid

    full = notice("FULL", 12)
    first_sale = make_sale("FULL-A", 6, full)
    # A replay can observe the second shipment's later committed status.
    assert api.get("/mes/wm/sales-notice/get", id=full[0])["status"] in (3, 4)
    second_sale = make_sale("FULL-B", 6, full)
    assert api.get("/mes/wm/sales-notice/get", id=full[0])["status"] == 4
    partial = notice("PARTIAL", 12)
    make_sale("PARTIAL-A", 6, partial)
    assert api.get("/mes/wm/sales-notice/get", id=partial[0])["status"] == 3
    failed = make_sale("QC-FAIL", 6, rejected=2, execute=False)
    assert api.get("/mes/wm/product-sales/get", id=failed)["status"] == 5
    result["failedOqcSales"] = failed

    def origin(sales_id):
        evidence = ctx.query(f"SELECT * FROM mes_wm_transaction WHERE tenant_id=1 AND deleted=0 AND biz_type=118 AND biz_id={int(sales_id)} AND quantity<0 ORDER BY id")
        if not evidence:
            raise AssertionError(("No posted sale evidence", sales_id))
        return evidence[0]

    def sales_return(suffix, evidence, quantity, good, *, finish=True, cancel=False):
        rid = api.post("/mes/wm/return-sales/create", {
            "code": "TST-COM-RETURN-" + suffix, "name": "测试·客户退货·" + suffix,
            "clientId": client, "salesOrderCode": "TST-COM-SO-FULL", "returnDate": now,
            "returnReason": "合成售后验收：须核对原实际出库流水。", "remark": REMARK})
        line = api.post("/mes/wm/return-sales-line/create", {
            "returnId": rid, "originTransactionId": evidence["id"],
            "quantity": quantity, "rqcCheckFlag": True, "remark": "由API认领原发货额度。"})
        if cancel:
            api.put("/mes/wm/return-sales/cancel", id=rid)
        else:
            api.put("/mes/wm/return-sales/submit", id=rid)
            if finish:
                bad = quantity - good
                qc = api.post("/mes/qc/rqc/create", {
                    "code": "TST-COM-RQC-" + suffix, "name": "测试·售后退货检验·" + suffix,
                    "sourceDocType": 119, "sourceDocId": rid, "sourceLineId": line,
                    "type": 2, "itemId": product, "batchCode": evidence["batch_code"],
                    "checkQuantity": quantity, "qualifiedQuantity": good, "unqualifiedQuantity": bad,
                    "checkResult": 2 if bad else 1, "inspectDate": now, "inspectorUserId": 980006,
                    "remark": "合格与不合格分行；不良品仅能入系统隔离仓。"})
                reading("rqc", qc, product, rejected=bad)
                api.put("/mes/qc/rqc/finish", id=qc)
                result["quality"].append({"type": "rqc", "id": qc, "rejected": bad})
                api.put("/mes/wm/return-sales/finish", id=rid)
                lines = rows(api.page("/mes/wm/return-sales-line", returnId=rid))
                quarantine = ctx.query("""SELECT w.id warehouse_id,l.id location_id,a.id area_id
                    FROM mes_wm_warehouse w JOIN mes_wm_warehouse_location l ON l.warehouse_id=w.id AND l.deleted=0
                    JOIN mes_wm_warehouse_area a ON a.location_id=l.id AND a.deleted=0
                    WHERE w.code='QUALITY_QUARANTINE_WAREHOUSE' AND w.deleted=0 AND w.tenant_id=1 LIMIT 1""")
                for returned in lines:
                    location = quarantine[0] if returned["qualityStatus"] == 2 else {
                        "warehouse_id": evidence["warehouse_id"], "location_id": evidence["location_id"], "area_id": evidence["area_id"]}
                    api.post("/mes/wm/return-sales-detail/create", {
                        "returnId": rid, "lineId": returned["id"], "itemId": returned["itemId"],
                        "quantity": returned["quantity"], "batchId": returned.get("batchId"),
                        "batchCode": returned.get("batchCode"), "warehouseId": location["warehouse_id"],
                        "locationId": location["location_id"], "areaId": location["area_id"], "remark": "按检验质量状态物理隔离。"})
                api.put("/mes/wm/return-sales/stock", id=rid)
        result["returns"].append(rid)
        return rid

    evidence = origin(first_sale)
    sales_return("PASS", evidence, 2, 2)
    sales_return("SPLIT", evidence, 2, 1)
    sales_return("FAIL", evidence, 2, 0)
    evidence2 = origin(second_sale)
    sales_return("CANCELED", evidence2, 1, 1, cancel=True)
    sales_return("PENDING", evidence2, 1, 1, finish=False)
    return result


def seed_operations_api(api, ctx):
    """Execute independent procurement and warehouse operations through APIs.

    The newly purchased washer is outside manufacturing BOM reservations.
    Sample and outsource receipts intentionally have no payable-cost document;
    their unknown costs must remain visible instead of becoming invented zeroes.
    """
    date = ctx.now.replace(microsecond=0).isoformat()
    prior = (ctx.now - timedelta(days=10)).replace(microsecond=0).isoformat()
    expiry = (ctx.now + timedelta(days=355)).replace(microsecond=0).isoformat()
    result = {"scenarios": OPERATIONS_SCENARIOS}
    ctx.ids["inventory_operations"] = result
    item, vendor = 981001, 986001
    base = {"warehouseId": 900001, "locationId": 900001, "areaId": 900001}

    # Complete planning identities through the actual work-order service, then
    # repair only this module's unposted fixtures' initially deferred references.
    planning_stock_before = ctx.query("SELECT id,item_id,batch_id,warehouse_id,location_id,area_id,work_order_id,quantity FROM mes_wm_material_stock WHERE tenant_id=1 AND deleted=0 ORDER BY id")
    result["outsourcePlans"] = []
    for n in range(1, 13):
        plan = api.post("/mes/pro/work-order/create", {
            "code": f"TST-OPS-OUTSOURCE-PLAN-{n:02d}", "name": f"测试·外协待执行计划{n:02d}",
            "type": 2, "orderSourceType": 2, "orderSourceCode": f"TST-EXT-PLAN-{n:02d}",
            "productId": 900103, "quantity": n * 8, "clientId": 986001,
            "vendorId": 986001 + n % 8, "batchCode": f"TST-EXT-LOT-{n:02d}",
            "requestDate": (ctx.now + timedelta(days=n)).replace(microsecond=0).isoformat(),
            "parentId": 0, "remark": "对应仓储外协草稿/待检案例；仅确认计划，不伪造领料、生产或入库。"})
        api.put("/mes/pro/work-order/confirm", id=plan)
        for table in ("mes_wm_outsource_issue", "mes_wm_outsource_receipt"):
            ctx.execute(f"UPDATE {table} SET work_order_id=%s WHERE id=%s AND tenant_id=1 AND deleted=0 AND status<>4", (plan, 986000 + n))
        ctx.execute("UPDATE mes_wm_batch SET work_order_id=%s,client_id=%s WHERE id=%s AND tenant_id=1 AND deleted=0", (plan, 986001, 986200 + n))
        ctx.conn.commit()
        result["outsourcePlans"].append(plan)
    planning_stock_after = ctx.query("SELECT id,item_id,batch_id,warehouse_id,location_id,area_id,work_order_id,quantity FROM mes_wm_material_stock WHERE tenant_id=1 AND deleted=0 ORDER BY id")
    assert planning_stock_before == planning_stock_after, "Confirming outsource plans must not move inventory"

    processed_item = api.post("/mes/md/item/create", {
        "code": "TST-OPS-OUTSOURCE-PRODUCT", "name": "测试清洗去毛刺垫片",
        "specification": "委外处理成品；与未处理垫片按独立物料编码管理", "unitMeasureId": 900001,
        "itemTypeId": 900002, "safeStockFlag": True, "minStock": 2, "maxStock": 200,
        "highValue": False, "batchFlag": True, "remark": "委外加工专用成品，1件原料产出1件。"})
    api.post("/mes/md/product-bom/create", {
        "itemId": processed_item, "bomItemId": item, "quantity": 1, "status": 0,
        "remark": "去毛刺委外单层BOM，一件不锈钢垫片对应一件处理成品。"})
    api.post("/mes/md/item-batch-config/save", {
        "itemId": processed_item, "produceDateFlag": False, "expireDateFlag": False,
        "receiptDateFlag": False, "vendorFlag": False, "clientFlag": False,
        "salesOrderCodeFlag": False, "purchaseOrderCodeFlag": False, "workOrderFlag": True,
        "taskFlag": False, "workstationFlag": False, "toolFlag": False, "moldFlag": False,
        "lotNumberFlag": True, "qualityStatusFlag": False})
    api.put("/mes/md/item/update-status", id=processed_item, status=0)
    outsource_order = api.post("/mes/pro/work-order/create", {
        "code": "TST-OPS-OUTSOURCE-WORK", "name": "测试·5件垫片委外去毛刺工单",
        "type": 2, "orderSourceType": 2, "orderSourceCode": "TST-OPS-EXT-001",
        "productId": processed_item, "quantity": 5, "clientId": 986001, "vendorId": 986003,
        "batchCode": "TST-OPS-EXT-FINISHED", "requestDate": date, "parentId": 0,
        "remark": "外协发料及验收入库实证；加工结算金额待核定。"})
    api.put("/mes/pro/work-order/confirm", id=outsource_order)
    result.update({"outsourceOrder": outsource_order, "outsourceProduct": processed_item})

    receipt = api.post("/mes/wm/item-receipt/create", {
        "code": "TST-OPS-PURCHASE", "name": "测试·独立仓储操作采购", "vendorId": vendor,
        "purchaseOrderCode": "TST-OPS-PO-001", "receiptDate": date,
        "remark": "100件非生产BOM不锈钢垫片，采购实际单价18.75元/件。"})
    purchase_line = api.post("/mes/wm/item-receipt-line/create", {
        "receiptId": receipt, "itemId": item, "receivedQuantity": 100,
        "productionDate": prior, "expireDate": expiry, "lotNumber": "TST-OPS-PURCHASE-LOT",
        "remark": "独立原批次，用于调拨、供应商退货及委外，避免占用生产预留。"})
    api.call("PUT", "/mes/cost/receipt-line-price", {
        "lineId": purchase_line, "unitPrice": "18.750000", "remark": "合成人民币未税实际采购价。"})
    purchased = api.get("/mes/wm/item-receipt-line/get", id=purchase_line)
    api.put("/mes/wm/item-receipt/submit", id=receipt)
    api.post("/mes/wm/item-receipt-detail/create", {
        "receiptId": receipt, "lineId": purchase_line, "itemId": item, "quantity": 100,
        "batchId": purchased["batchId"], **base, "remark": "100件实收上架。"})
    api.put("/mes/wm/item-receipt/stock", id=receipt)
    api.put("/mes/wm/item-receipt/finish", id=receipt)
    result["purchase"] = receipt
    stocks = ctx.query(f"SELECT * FROM mes_wm_material_stock WHERE tenant_id=1 AND deleted=0 AND item_id={item} AND batch_id={int(purchased['batchId'])} AND warehouse_id=900001 AND work_order_id IS NULL ORDER BY id LIMIT 1")
    if not stocks:
        raise AssertionError("Operations purchase did not create physical stock")
    stock = stocks[0]
    selection = {"materialStockId": stock["id"], "itemId": item,
                 "batchId": stock["batch_id"], "batchCode": stock["batch_code"], **base}

    transfer = api.post("/mes/wm/transfer/create", {
        "code": "TST-OPS-TRANSFER", "name": "测试·实体跨仓分位调拨", "type": 1,
        "deliveryFlag": False, "transferDate": date,
        "remark": "20件由原仓调往原料周转仓，拆分至两个有效库位，保持原成本依据。"})
    transfer_line = api.post("/mes/wm/transfer-line/create", {
        "transferId": transfer, "materialStockId": stock["id"], "itemId": item,
        "quantity": 20, "batchId": stock["batch_id"],
        "fromWarehouseId": 900001, "fromLocationId": 900001, "fromAreaId": 900001,
        "remark": "同一来源按目标库位拆分。"})
    api.put("/mes/wm/transfer/submit", id=transfer)
    for area in (986113, 986114):
        api.post("/mes/wm/transfer-detail/create", {
            "transferId": transfer, "lineId": transfer_line, "itemId": item, "quantity": 10,
            "batchId": stock["batch_id"], "toWarehouseId": 986001,
            "toLocationId": 986011, "toAreaId": area, "remark": "10件目标库位分配。"})
    api.put("/mes/wm/transfer/stock", id=transfer)
    api.put("/mes/wm/transfer/finish", id=transfer)
    result["transfer"] = transfer

    returned = api.post("/mes/wm/return-vendor/create", {
        "code": "TST-OPS-VENDOR-RETURN", "name": "测试·原供应商原批次退货", "vendorId": vendor,
        "purchaseOrderCode": "TST-OPS-PO-001", "returnDate": date,
        "returnReason": "合成采购规格调整，与供应商协商退回5件未使用原批次。", "remark": REMARK})
    rvline = api.post("/mes/wm/return-vendor-line/create", {
        "returnId": returned, "itemId": item, "quantity": 5,
        "batchId": stock["batch_id"], "batchCode": stock["batch_code"], "remark": "来源供应商一致。"})
    api.put("/mes/wm/return-vendor/submit", id=returned)
    api.post("/mes/wm/return-vendor-detail/create", {
        "returnId": returned, "lineId": rvline, "quantity": 5, **selection,
        "remark": "扣减本次已采购、可用、未冻结的实体库存。"})
    api.put("/mes/wm/return-vendor/stock", id=returned)
    api.put("/mes/wm/return-vendor/finish", id=returned)
    result["vendorReturn"] = returned

    issue = api.post("/mes/wm/outsource-issue/create", {
        "code": "TST-OPS-OUTSOURCE-ISSUE", "name": "测试·垫片委外去毛刺", "vendorId": 986003,
        "workOrderId": outsource_order, "issueDate": date, "remark": "5件原料垫片交外协方清洗去毛刺；属于专用代工工单。"})
    osline = api.post("/mes/wm/outsource-issue-line/create", {
        "issueId": issue, "itemId": item, "quantity": 5, "materialStockId": stock["id"],
        "batchCode": stock["batch_code"], "remark": "真实采购批次。"})
    api.put("/mes/wm/outsource-issue/submit", id=issue)
    api.post("/mes/wm/outsource-issue-detail/create", {
        "issueId": issue, "lineId": osline, "quantity": 5, **selection, "remark": "外协备料实际出库。"})
    api.put("/mes/wm/outsource-issue/stock", id=issue)
    api.put("/mes/wm/outsource-issue/finish", id=issue)
    osreceipt = api.post("/mes/wm/outsource-receipt/create", {
        "code": "TST-OPS-OUTSOURCE-RECEIPT", "name": "测试·委外垫片验收入库", "vendorId": 986003,
        "workOrderId": outsource_order, "receiptDate": date,
        "remark": "对应委外发出的5件垫片，清洗去毛刺后验收；外协结算单未到，成本保持待核定。"})
    osrline = api.post("/mes/wm/outsource-receipt-line/create", {
        "receiptId": osreceipt, "itemId": processed_item, "quantity": 5, "productionDate": prior,
        "expireDate": expiry, "lotNumber": "TST-OPS-OUTSOURCE-RETURN-LOT",
        "iqcCheckFlag": False, "remark": "验收员按合格供应商交付证明免检；生成独立加工返回批次。"})
    api.put("/mes/wm/outsource-receipt/submit", id=osreceipt)
    osreturned = api.get("/mes/wm/outsource-receipt-line/get", id=osrline)
    api.post("/mes/wm/outsource-receipt-detail/create", {
        "receiptId": osreceipt, "lineId": osrline, "itemId": processed_item, "quantity": 5,
        "batchId": osreturned["batchId"], **base, "remark": "实际返回上架。"})
    api.put("/mes/wm/outsource-receipt/stock", id=osreceipt)
    api.put("/mes/wm/outsource-receipt/finish", id=osreceipt)
    api.put("/mes/pro/work-order/finish", id=outsource_order)
    result["outsourceIssue"], result["outsourceReceipt"] = issue, osreceipt

    sample_item = api.post("/mes/md/item/create", {
        "code": "TST-OPS-NONBATCH-SAMPLE", "name": "测试无批次包装垫纸",
        "specification": "合成样品300×300 mm；普通低值辅料", "unitMeasureId": 900001,
        "itemTypeId": 980002, "safeStockFlag": True, "minStock": 5, "maxStock": 200,
        "highValue": False, "batchFlag": False,
        "remark": "无偿试用辅料无结算依据；批次管理关闭用于无批次收发验收。"})
    api.put("/mes/md/item/update-status", id=sample_item, status=0)
    mr = api.post("/mes/wm/misc-receipt/create", {
        "code": "TST-OPS-SAMPLE-IN", "name": "测试·无偿辅料样品入库", "type": 1,
        "receiptDate": date, "remark": "20件无偿试用样品，实际成本未知；不能以0元假装已核价。"})
    api.post("/mes/wm/misc-receipt-line/create", {
        "receiptId": mr, "itemId": sample_item, "quantity": 20, **base,
        "remark": "无批次20件，行保存时由服务自动产生对应上架明细。"})
    api.put("/mes/wm/misc-receipt/submit", id=mr)
    api.put("/mes/wm/misc-receipt/finish", id=mr)
    sample_stocks = ctx.query(f"SELECT * FROM mes_wm_material_stock WHERE tenant_id=1 AND deleted=0 AND item_id={int(sample_item)} AND warehouse_id=900001 ORDER BY id LIMIT 1")
    if not sample_stocks:
        raise AssertionError("Sample receipt did not create physical stock")
    mi = api.post("/mes/wm/misc-issue/create", {
        "code": "TST-OPS-SAMPLE-OUT", "name": "测试·样品包装验证消耗", "type": 1,
        "issueDate": date, "remark": "4件样品用于包装验证，无偿来源保留成本未核定状态。"})
    api.post("/mes/wm/misc-issue-line/create", {
        "issueId": mi, "materialStockId": sample_stocks[0]["id"], "itemId": sample_item,
        "quantity": 4, "batchId": None, "batchCode": None, **base, "remark": "无批次可用样品消耗。"})
    api.put("/mes/wm/misc-issue/submit", id=mi)
    api.put("/mes/wm/misc-issue/finish", id=mi)
    result.update({"sampleItem": sample_item, "sampleReceipt": mr, "sampleIssue": mi})

    # Physical WIP returns do not change feedback/output totals or any closed order.
    order = ctx.ids["manufacturing_orders"]["MFG-PROGRESS"]
    wip = ctx.query(f"""SELECT * FROM mes_wm_material_stock WHERE tenant_id=1 AND deleted=0
        AND warehouse_id=900002 AND work_order_id={int(order)} AND item_id=900102 ORDER BY id LIMIT 1""")
    if not wip:
        raise AssertionError("Manufacturing progress scenario lacks owned bolt WIP")
    wipstock = wip[0]
    quarantine = ctx.query("""SELECT w.id warehouse_id,l.id location_id,a.id area_id
        FROM mes_wm_warehouse w JOIN mes_wm_warehouse_location l ON l.warehouse_id=w.id AND l.deleted=0
        JOIN mes_wm_warehouse_area a ON a.location_id=l.id AND a.deleted=0
        WHERE w.code='QUALITY_QUARANTINE_WAREHOUSE' AND w.deleted=0 AND w.tenant_id=1 LIMIT 1""")[0]
    result["productionReturns"] = []
    for suffix, good in [("PASS", 2), ("FAIL", 0)]:
        ri = api.post("/mes/wm/return-issue/create", {
            "code": "TST-OPS-WIP-RETURN-" + suffix, "name": "测试·线边退料·" + suffix,
            "workOrderId": order, "workstationId": ctx.ids.get("station2", 900002),
            "type": 1 if good else 2, "returnDate": date,
            "remark": "在制工单2件螺栓退料：合格余料回仓/不良品隔离，继续生产应按齐套结果补料。"})
        ril = api.post("/mes/wm/return-issue-line/create", {
            "issueId": ri, "materialStockId": wipstock["id"], "itemId": 900102,
            "quantity": 2, "batchId": wipstock["batch_id"], "batchCode": wipstock["batch_code"],
            "rqcCheckFlag": True, "remark": "只退本工单所属真实线边库存。"})
        api.put("/mes/wm/return-issue/submit", id=ri)
        qc = api.post("/mes/qc/rqc/create", {
            "code": "TST-OPS-WIP-RQC-" + suffix, "name": "测试·生产退料检验·" + suffix,
            "sourceDocType": 116, "sourceDocId": ri, "sourceLineId": ril, "type": 1,
            "itemId": 900102, "batchCode": wipstock["batch_code"], "checkQuantity": 2,
            "qualifiedQuantity": good, "unqualifiedQuantity": 2 - good, "checkResult": 1 if good else 2,
            "inspectDate": date, "inspectorUserId": 980006,
            "remark": "原来源退料行与物料、批次、数量严格一致。"})
        api.post("/mes/qc/indicator-result/create", {
            "code": "TST-OPS-WIP-READ-" + suffix, "qcType": 4, "qcId": qc,
            "items": [{"indicatorId": 986002, "value": "1" if good else "0"},
                      {"indicatorId": 986004, "value": "1"},
                      {"indicatorId": 986005, "value": "合成螺栓通止规测试记录。"}],
            "remark": "RQC验收实测样本。"})
        if not good:
            qlines = api.page("/mes/qc/rqc/line", rqcId=qc)
            qlines = qlines.get("list", []) if isinstance(qlines, dict) else qlines
            api.post("/mes/qc/defect-record/create", {
                "qcType": 4, "qcId": qc, "lineId": qlines[0]["id"], "defectId": 986003,
                "name": "螺纹滑牙", "level": 2, "quantity": 2, "remark": "2件不良螺栓，隔离待受控处置。"})
        api.put("/mes/qc/rqc/finish", id=qc)
        dest = base if good else {"warehouseId": quarantine["warehouse_id"],
                                  "locationId": quarantine["location_id"], "areaId": quarantine["area_id"]}
        api.post("/mes/wm/return-issue-detail/create", {
            "issueId": ri, "lineId": ril, "materialStockId": wipstock["id"], "itemId": 900102,
            "quantity": 2, "batchId": wipstock["batch_id"], "batchCode": wipstock["batch_code"],
            **dest, "remark": "从工单线边扣减并转入对应质量的实体库位。"})
        api.put("/mes/wm/return-issue/stock", id=ri)
        api.put("/mes/wm/return-issue/finish", id=ri)
        result["productionReturns"].append(ri)
    return result


def validation(ctx):
    """Return count-zero audit checks; orchestration executes them after commit."""
    checks = []
    joins = [
        ("mes_wm_arrival_notice_line", "notice_id", "mes_wm_arrival_notice"),
        ("mes_wm_sales_notice_line", "notice_id", "mes_wm_sales_notice"),
        ("mes_wm_item_receipt_line", "receipt_id", "mes_wm_item_receipt"),
        ("mes_wm_outsource_issue_line", "issue_id", "mes_wm_outsource_issue"),
        ("mes_wm_outsource_receipt_line", "receipt_id", "mes_wm_outsource_receipt"),
        ("mes_wm_misc_receipt_line", "receipt_id", "mes_wm_misc_receipt"),
        ("mes_wm_misc_issue_line", "issue_id", "mes_wm_misc_issue"),
        ("mes_wm_return_vendor_line", "return_id", "mes_wm_return_vendor"),
        ("mes_wm_return_issue_line", "issue_id", "mes_wm_return_issue"),
        ("mes_wm_product_sales_line", "sales_id", "mes_wm_product_sales"),
        ("mes_wm_transfer_line", "transfer_id", "mes_wm_transfer"),
        ("mes_qc_template_item", "template_id", "mes_qc_template"),
        ("mes_qc_template_indicator", "template_id", "mes_qc_template"),
        ("mes_wm_stock_taking_plan_param", "plan_id", "mes_wm_stock_taking_plan"),
    ]
    joins += [(f"mes_qc_{kind}_line", f"{kind}_id", f"mes_qc_{kind}") for kind in ("iqc", "ipqc", "oqc", "rqc")]
    for table, key, parent in joins:
        checks.append((f"inventory_parent_{table}", f"SELECT COUNT(*) FROM {table} c LEFT JOIN {parent} p ON p.id=c.{key} AND p.tenant_id=c.tenant_id AND p.deleted=0 WHERE c.deleted=0 AND c.tenant_id=1 AND p.id IS NULL"))
    for kind in ("iqc", "ipqc", "oqc", "rqc"):
        checks.append((f"qc_quantity_{kind}", f"SELECT COUNT(*) FROM mes_qc_{kind} WHERE deleted=0 AND tenant_id=1 AND (check_quantity<=0 OR qualified_quantity<0 OR unqualified_quantity<0 OR qualified_quantity+unqualified_quantity<>check_quantity OR status NOT IN (0,4))"))
        checks.append((f"qc_indicator_{kind}", f"SELECT COUNT(*) FROM mes_qc_{kind}_line l LEFT JOIN mes_qc_indicator i ON i.id=l.indicator_id AND i.deleted=0 AND i.tenant_id=l.tenant_id WHERE l.deleted=0 AND l.tenant_id=1 AND (i.id IS NULL OR l.min_threshold>l.max_threshold)"))
    checks += [
        ("iqc_received_conservation", "SELECT COUNT(*) FROM mes_qc_iqc WHERE deleted=0 AND tenant_id=1 AND qualified_quantity+unqualified_quantity<>received_quantity"),
        ("qc_iqc_source_arrival", "SELECT COUNT(*) FROM mes_qc_iqc q LEFT JOIN mes_wm_arrival_notice_line l ON l.id=q.source_line_id AND l.notice_id=q.source_doc_id AND l.tenant_id=q.tenant_id AND l.deleted=0 WHERE q.deleted=0 AND q.tenant_id=1 AND q.source_doc_type=100 AND (l.id IS NULL OR l.item_id<>q.item_id OR l.iqc_check_flag<>1)"),
        ("qc_iqc_source_outsource", "SELECT COUNT(*) FROM mes_qc_iqc q LEFT JOIN mes_wm_outsource_receipt_line l ON l.id=q.source_line_id AND l.receipt_id=q.source_doc_id AND l.tenant_id=q.tenant_id AND l.deleted=0 WHERE q.deleted=0 AND q.tenant_id=1 AND q.source_doc_type=121 AND (l.id IS NULL OR l.item_id<>q.item_id OR l.iqc_check_flag<>1)"),
        ("qc_oqc_source", "SELECT COUNT(*) FROM mes_qc_oqc q LEFT JOIN mes_wm_product_sales_line l ON l.id=q.source_line_id AND l.sales_id=q.source_doc_id AND l.tenant_id=q.tenant_id AND l.deleted=0 LEFT JOIN mes_wm_product_sales s ON s.id=l.sales_id AND s.tenant_id=q.tenant_id AND s.deleted=0 WHERE q.deleted=0 AND q.tenant_id=1 AND q.source_doc_type=118 AND (l.id IS NULL OR q.item_id<>l.item_id OR q.client_id<>s.client_id OR NOT(q.batch_code<=>l.batch_code) OR q.out_quantity<>l.quantity OR l.oqc_check_flag<>1)"),
        ("qc_rqc_return_issue_source", "SELECT COUNT(*) FROM mes_qc_rqc q LEFT JOIN mes_wm_return_issue_line l ON l.id=q.source_line_id AND l.issue_id=q.source_doc_id AND l.tenant_id=q.tenant_id AND l.deleted=0 WHERE q.deleted=0 AND q.tenant_id=1 AND q.source_doc_type=116 AND (l.id IS NULL OR l.item_id<>q.item_id OR l.rqc_id<>q.id OR l.rqc_check_flag<>1 OR q.check_quantity<>l.quantity OR NOT(q.batch_code<=>l.batch_code))"),
        ("receipt_source_consistency", "SELECT COUNT(*) FROM mes_wm_item_receipt_line l JOIN mes_wm_item_receipt p ON p.id=l.receipt_id AND p.deleted=0 LEFT JOIN mes_wm_arrival_notice_line a ON a.id=l.arrival_notice_line_id AND a.deleted=0 AND a.tenant_id=l.tenant_id WHERE l.deleted=0 AND l.tenant_id=1 AND ((p.notice_id IS NOT NULL AND (a.id IS NULL OR a.notice_id<>p.notice_id OR a.item_id<>l.item_id OR l.received_quantity>a.arrival_quantity)) OR (p.notice_id IS NULL AND l.arrival_notice_line_id IS NOT NULL))"),
        ("receipt_approved_allocations", "SELECT COUNT(*) FROM mes_wm_item_receipt_line l JOIN mes_wm_item_receipt p ON p.id=l.receipt_id AND p.deleted=0 LEFT JOIN (SELECT line_id,SUM(quantity) qty FROM mes_wm_item_receipt_detail WHERE deleted=0 AND tenant_id=1 GROUP BY line_id) d ON d.line_id=l.id WHERE l.deleted=0 AND l.tenant_id=1 AND p.status IN (3,4) AND COALESCE(d.qty,0)<>l.received_quantity"),
        ("stocktaking_plan_configuration", "SELECT COUNT(*) FROM mes_wm_stock_taking_plan p WHERE p.deleted=0 AND p.tenant_id=1 AND ((p.type=2 AND (p.start_time IS NULL OR p.end_time IS NULL OR p.start_time>=p.end_time)) OR (p.type=1 AND (p.start_time IS NOT NULL OR p.end_time IS NOT NULL)) OR (p.status=0 AND NOT EXISTS(SELECT 1 FROM mes_wm_stock_taking_plan_param a WHERE a.plan_id=p.id AND a.deleted=0 AND a.tenant_id=p.tenant_id)))"),
        ("template_item_unique", "SELECT COUNT(*) FROM (SELECT template_id,item_id,tenant_id FROM mes_qc_template_item WHERE deleted=0 GROUP BY template_id,item_id,tenant_id HAVING COUNT(*)>1) d"),
        ("inventory_drafts_have_no_ledger", "SELECT COUNT(*) FROM mes_wm_transaction WHERE deleted=0 AND tenant_id=1 AND (biz_code REGEXP '^TST-(REC|MR|MI|RV|OSR|OSI|MIS-STK|TR|PS-OQC|RI)-[0-9]+$')"),
    ]
    if ctx.ids.get("inventory_api"):
        checks += [
            ("commerce_posted_sales", "SELECT ABS(COUNT(*)-3) FROM mes_wm_product_sales WHERE tenant_id=1 AND deleted=0 AND code LIKE 'TST-COM-SALE-%' AND status=4"),
            ("commerce_notice_states", "SELECT COUNT(*) FROM mes_wm_sales_notice WHERE tenant_id=1 AND deleted=0 AND ((code='TST-COM-NOTICE-FULL' AND status<>4) OR (code='TST-COM-NOTICE-PARTIAL' AND status<>3))"),
            ("commerce_failed_oqc_blocked", "SELECT COUNT(*) FROM mes_wm_product_sales p WHERE tenant_id=1 AND deleted=0 AND code='TST-COM-SALE-QC-FAIL' AND (status<>5 OR EXISTS(SELECT 1 FROM mes_wm_transaction t WHERE t.biz_type=118 AND t.biz_id=p.id AND t.tenant_id=p.tenant_id AND t.deleted=0))"),
            ("commerce_sales_priced", "SELECT COUNT(*) FROM mes_wm_product_sales_line l JOIN mes_wm_product_sales p ON p.id=l.sales_id AND p.tenant_id=l.tenant_id AND p.deleted=0 LEFT JOIN mes_cost_line_price c ON c.line_id=l.id AND c.kind='SALES' AND c.deleted=0 AND c.tenant_id=l.tenant_id WHERE l.deleted=0 AND l.tenant_id=1 AND p.code LIKE 'TST-COM-SALE-%' AND (c.id IS NULL OR c.unit_price<>88)"),
            ("commerce_return_states", "SELECT COUNT(*) FROM mes_wm_return_sales WHERE tenant_id=1 AND deleted=0 AND ((code IN ('TST-COM-RETURN-PASS','TST-COM-RETURN-SPLIT','TST-COM-RETURN-FAIL') AND status<>4) OR (code='TST-COM-RETURN-CANCELED' AND status<>5) OR (code='TST-COM-RETURN-PENDING' AND status<>1))"),
            ("commerce_return_posted_quantity", "SELECT ABS(COALESCE(SUM(quantity),0)-6) FROM mes_wm_transaction WHERE tenant_id=1 AND deleted=0 AND biz_type=119 AND biz_code LIKE 'TST-COM-RETURN-%'"),
            ("commerce_return_quarantine_quantity", "SELECT ABS(COALESCE(SUM(t.quantity),0)-3) FROM mes_wm_transaction t JOIN mes_wm_warehouse w ON w.id=t.warehouse_id AND w.tenant_id=t.tenant_id AND w.deleted=0 WHERE t.tenant_id=1 AND t.deleted=0 AND t.biz_type=119 AND t.biz_code LIKE 'TST-COM-RETURN-%' AND w.code='QUALITY_QUARANTINE_WAREHOUSE'"),
            ("commerce_return_budget_ledger", "SELECT COUNT(*) FROM mes_wm_return_source_budget b LEFT JOIN (SELECT tenant_id,return_source_transaction_id origin_id,SUM(quantity) posted FROM mes_wm_transaction WHERE deleted=0 AND biz_type=119 GROUP BY tenant_id,return_source_transaction_id) t ON t.tenant_id=b.tenant_id AND t.origin_id=b.origin_transaction_id WHERE b.tenant_id=1 AND b.returned_quantity<>COALESCE(t.posted,0)"),
            ("commerce_return_budget_reservation", "SELECT COUNT(*) FROM mes_wm_return_source_budget b LEFT JOIN (SELECT tenant_id,origin_transaction_id,SUM(quantity-posted_quantity) reserved FROM mes_wm_return_source_reservation WHERE state='RESERVED' GROUP BY tenant_id,origin_transaction_id) r ON r.tenant_id=b.tenant_id AND r.origin_transaction_id=b.origin_transaction_id WHERE b.tenant_id=1 AND b.reserved_quantity<>COALESCE(r.reserved,0)"),
            ("commerce_return_budget_ceiling", "SELECT COUNT(*) FROM mes_wm_return_source_budget b LEFT JOIN mes_wm_transaction t ON t.id=b.origin_transaction_id AND t.tenant_id=b.tenant_id AND t.deleted=0 WHERE b.tenant_id=1 AND (t.id IS NULL OR t.biz_type<>118 OR t.quantity>=0 OR b.returned_quantity<0 OR b.reserved_quantity<0 OR b.returned_quantity+b.reserved_quantity>ABS(t.quantity))"),
            ("commerce_return_evidence", "SELECT COUNT(*) FROM mes_wm_transaction t LEFT JOIN mes_wm_return_posted_evidence e ON e.return_transaction_id=t.id AND e.tenant_id=t.tenant_id WHERE t.tenant_id=1 AND t.deleted=0 AND t.biz_type=119 AND t.biz_code LIKE 'TST-COM-RETURN-%' AND (e.return_transaction_id IS NULL OR e.quantity<>t.quantity OR e.origin_transaction_id<>t.return_source_transaction_id)"),
        ]
    if ctx.ids.get("inventory_operations"):
        for table, code in [
            ("mes_wm_item_receipt", "TST-OPS-PURCHASE"),
            ("mes_wm_transfer", "TST-OPS-TRANSFER"),
            ("mes_wm_return_vendor", "TST-OPS-VENDOR-RETURN"),
            ("mes_wm_outsource_issue", "TST-OPS-OUTSOURCE-ISSUE"),
            ("mes_wm_outsource_receipt", "TST-OPS-OUTSOURCE-RECEIPT"),
            ("mes_wm_misc_receipt", "TST-OPS-SAMPLE-IN"),
            ("mes_wm_misc_issue", "TST-OPS-SAMPLE-OUT"),
            ("mes_wm_return_issue", "TST-OPS-WIP-RETURN-PASS"),
            ("mes_wm_return_issue", "TST-OPS-WIP-RETURN-FAIL"),
        ]:
            checks.append(("operations_finished_" + code, f"SELECT ABS(COUNT(*)-1) FROM {table} WHERE code='{code}' AND status=4 AND tenant_id=1 AND deleted=0"))
        for code, biz, qty in [
            ("TST-OPS-PURCHASE", 110, 100), ("TST-OPS-TRANSFER", 111, -20),
            ("TST-OPS-TRANSFER", 112, 20), ("TST-OPS-VENDOR-RETURN", 124, -5),
            ("TST-OPS-OUTSOURCE-ISSUE", 120, -5), ("TST-OPS-OUTSOURCE-RECEIPT", 121, 5),
            ("TST-OPS-SAMPLE-IN", 114, 20), ("TST-OPS-SAMPLE-OUT", 113, -4),
            ("TST-OPS-WIP-RETURN-PASS", 116, 0), ("TST-OPS-WIP-RETURN-FAIL", 116, 0),
        ]:
            checks.append((f"operations_ledger_{code}_{biz}", f"SELECT ABS(COALESCE(SUM(quantity),0)-({qty})) FROM mes_wm_transaction WHERE biz_code='{code}' AND biz_type={biz} AND tenant_id=1 AND deleted=0"))
        checks += [
            ("operations_transfer_pair_count", "SELECT ABS(COUNT(*)-2) FROM mes_wm_transaction WHERE biz_code='TST-OPS-TRANSFER' AND biz_type=112 AND type=4 AND tenant_id=1 AND deleted=0"),
            ("operations_transfer_pair_identity", "SELECT COUNT(*) FROM mes_wm_transaction i LEFT JOIN mes_wm_transaction o ON o.id=i.related_transaction_id AND o.tenant_id=i.tenant_id AND o.deleted=0 WHERE i.biz_code='TST-OPS-TRANSFER' AND i.biz_type=112 AND i.tenant_id=1 AND i.deleted=0 AND (o.id IS NULL OR o.biz_type<>111 OR o.type<>3 OR i.type<>4 OR o.quantity+i.quantity<>0 OR o.item_id<>i.item_id OR NOT(o.batch_id<=>i.batch_id))"),
            ("operations_transfer_actual_cost", "SELECT COUNT(*) FROM mes_cost_stock_layer l JOIN mes_wm_transaction t ON t.id=l.inbound_transaction_id AND t.tenant_id=l.tenant_id AND t.deleted=0 WHERE l.deleted=0 AND l.tenant_id=1 AND t.biz_code='TST-OPS-TRANSFER' AND t.biz_type=112 AND (l.unit_cost IS NULL OR l.unit_cost<>18.75 OR l.status<>'FINAL')"),
            ("operations_supplier_batch_identity", "SELECT COUNT(*) FROM mes_wm_return_vendor_detail d JOIN mes_wm_return_vendor r ON r.id=d.return_id AND r.tenant_id=d.tenant_id AND r.deleted=0 LEFT JOIN mes_wm_material_stock s ON s.id=d.material_stock_id AND s.tenant_id=d.tenant_id AND s.deleted=0 LEFT JOIN mes_wm_batch b ON b.id=d.batch_id AND b.tenant_id=d.tenant_id AND b.deleted=0 WHERE d.tenant_id=1 AND d.deleted=0 AND r.code='TST-OPS-VENDOR-RETURN' AND (s.id IS NULL OR b.id IS NULL OR NOT(s.vendor_id<=>r.vendor_id) OR NOT(b.vendor_id<=>r.vendor_id) OR s.item_id<>d.item_id OR s.batch_id<>d.batch_id)"),
            ("operations_sample_stock", "SELECT ABS(COALESCE(SUM(s.quantity),0)-16) FROM mes_wm_material_stock s JOIN mes_md_item i ON i.id=s.item_id AND i.tenant_id=s.tenant_id AND i.deleted=0 WHERE s.tenant_id=1 AND s.deleted=0 AND i.code='TST-OPS-NONBATCH-SAMPLE' AND s.warehouse_id=900001"),
            ("operations_sample_batch_policy", "SELECT COUNT(*) FROM mes_wm_material_stock s JOIN mes_md_item i ON i.id=s.item_id AND i.tenant_id=s.tenant_id AND i.deleted=0 WHERE s.tenant_id=1 AND s.deleted=0 AND i.code='TST-OPS-NONBATCH-SAMPLE' AND (i.batch_flag<>0 OR s.batch_id IS NOT NULL OR s.batch_code IS NOT NULL)"),
            ("operations_sample_cost_unknown", "SELECT ABS(COUNT(*)-1) FROM mes_cost_stock_layer l JOIN mes_wm_transaction t ON t.id=l.inbound_transaction_id AND t.tenant_id=l.tenant_id AND t.deleted=0 WHERE l.deleted=0 AND l.tenant_id=1 AND t.biz_code='TST-OPS-SAMPLE-IN' AND l.unit_cost IS NULL AND l.status<>'FINAL'"),
            ("operations_outsource_work_order", "SELECT ABS(COUNT(*)-1) FROM mes_pro_work_order WHERE code='TST-OPS-OUTSOURCE-WORK' AND type=2 AND status=2 AND quantity=5 AND quantity_produced=5 AND tenant_id=1 AND deleted=0"),
            ("operations_outsource_plans", "SELECT ABS(COUNT(*)-12) FROM mes_pro_work_order WHERE code LIKE 'TST-OPS-OUTSOURCE-PLAN-%' AND type=2 AND status=1 AND quantity_produced=0 AND tenant_id=1 AND deleted=0"),
            ("operations_outsource_fixture_issue_relation", "SELECT COUNT(*) FROM mes_wm_outsource_issue i LEFT JOIN mes_pro_work_order w ON w.id=i.work_order_id AND w.tenant_id=i.tenant_id AND w.deleted=0 WHERE i.id BETWEEN 986001 AND 986012 AND i.tenant_id=1 AND i.deleted=0 AND (w.id IS NULL OR w.type<>2 OR w.status<>1)"),
            ("operations_outsource_fixture_receipt_relation", "SELECT COUNT(*) FROM mes_wm_outsource_receipt i LEFT JOIN mes_pro_work_order w ON w.id=i.work_order_id AND w.tenant_id=i.tenant_id AND w.deleted=0 WHERE i.id BETWEEN 986001 AND 986012 AND i.tenant_id=1 AND i.deleted=0 AND (w.id IS NULL OR w.type<>2 OR w.status<>1)"),
            ("operations_wip_return_quality_segregation", "SELECT ABS(COALESCE(SUM(t.quantity),0)-2) FROM mes_wm_transaction t JOIN mes_wm_warehouse w ON w.id=t.warehouse_id AND w.tenant_id=t.tenant_id AND w.deleted=0 WHERE t.tenant_id=1 AND t.deleted=0 AND t.biz_code='TST-OPS-WIP-RETURN-FAIL' AND t.quantity>0 AND w.code='QUALITY_QUARANTINE_WAREHOUSE'"),
        ]
    return checks
