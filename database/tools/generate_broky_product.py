#!/usr/bin/env python3
"""Generate the deterministic, non-destructive Broky teaching configuration.

This program never connects to a database.  The generated MySQL migration seeds
configuration and one DRAFT parent work order, never stock, approvals or inspection
results.  --check verifies the checked-in migration without rewriting it.
"""
from __future__ import annotations

import argparse
from collections import Counter, defaultdict
from decimal import Decimal
import json
from pathlib import Path
import re

from generate_mes_schema import JAVA, ROOT, fields

OUT = ROOT / "database/migrations/20261018_broky_product.sql"
OWNER = "broky-seed-v1"
FG, HEAD, STAND = 972101, 972102, 972103
PRODUCTS = (FG, HEAD, STAND)
RAW = {number: 972200 + number for number in range(1, 29)}
PRICES = tuple(map(Decimal, (
    "5", "6", "7", ".8", ".6", "1.5", "6.5", "5.5", "1.5", ".5", ".2", ".3", "4", "1", ".4",
    "63.9", "3", "7", "4", ".15", "4", "3", "8", "5", "4", "1.5", ".8", ".3")))
MATERIALS = (
    ("后网罩", "件", "与400mm扇叶配套；采购边界不含锁母"),
    ("前网罩", "件", "与后网罩、锁圈配套"),
    ("扇叶", "件", "400mm配套叶轮；安装方向按零件标识"),
    ("扇叶锁帽", "件", "与电机输出轴配套；旋向按零件标识"),
    ("后网罩锁母", "件", "与电机前端配套"),
    ("网罩锁圈组件", "套", "含配套锁圈及锁扣"),
    ("三档开关线束组件", "套", "含开关、内部预制连接线及接插件；不含外接电源线"),
    ("预制电源线组件", "套", "含插头、电缆和护线结构；不含内部控制线束"),
    ("机头俯仰锁紧旋钮组件", "套", "仅调节机头俯仰；与立柱高度旋钮不同"),
    ("摆头操作帽", "件", "与摆头齿箱配套；不包含齿箱"),
    ("线束固定夹", "个", "每台2个；按批准走线图安装"),
    ("整机铭牌标签", "张", "铭牌内容和额定参数须经批准后印制"),
    ("外包装纸箱", "个", "适配成品及护垫的教学包装结构"),
    ("包装泡沫护垫", "块", "每台上、下护垫各1块，采购按同规格件计量"),
    ("产品说明书", "本", "内容须与批准产品技术文件一致"),
    ("电机采购模块", "套", "不含摆头齿箱；外购电机不拆绕组制造"),
    ("机头安装支架", "件", "不含俯仰锁紧旋钮组件"),
    ("摆头齿箱模块", "套", "不含摆头操作帽"),
    ("机头外观防护后壳", "件", "与支架及电机模块配套"),
    ("M4×12固定螺钉", "颗", "每套机头4颗；扭矩按批准工艺文件执行"),
    ("底座下壳", "件", "与上盖及配重配套"),
    ("底座上盖", "件", "与下壳及外立柱配套"),
    ("底座配重件", "件", "与批准结构图一致；不虚构重量实测值"),
    ("外立柱", "件", "与伸缩内管和底座连接结构配套"),
    ("伸缩内管", "件", "与外立柱及高度锁紧旋钮配套"),
    ("高度锁紧旋钮", "件", "仅调节立柱高度；与机头俯仰旋钮不同"),
    ("M6连接紧固件套件", "套", "含配套垫圈及螺母；按套采购和消耗"),
    ("防滑脚垫", "个", "每套底座4个"),
)
BOMS = {
    FG: [(HEAD, 1), (STAND, 1)] + [(RAW[n], 2 if n in (11, 14) else 1) for n in range(1, 16)],
    HEAD: [(RAW[n], 4 if n == 20 else 1) for n in range(16, 21)],
    STAND: [(RAW[n], 4 if n == 28 else 1) for n in range(21, 29)],
}
# route id, product, ordered (process id, code suffix, title, allocated raw numbers).
ROUTES = (
    (972101, FG, (
        (972101, "FG-10", "结构总装", (HEAD, STAND, RAW[9])),
        (972102, "FG-20", "风叶网罩装配", tuple(RAW[n] for n in range(1, 7))),
        (972103, "FG-30", "控制组件装配", tuple(RAW[n] for n in (7, 8, 10, 11))),
        (972104, "FG-40", "试运行检查", ()),
        (972105, "FG-50", "包装与完工检验", tuple(RAW[n] for n in range(12, 16))),
    )),
    (972102, HEAD, (
        (972106, "H-10", "电机支架装配", tuple(RAW[n] for n in (16, 17, 20))),
        (972107, "H-20", "摆头机构及后壳装配", tuple(RAW[n] for n in (18, 19))),
        (972108, "H-30", "机头完工检验", ()),
    )),
    (972103, STAND, (
        (972109, "S-10", "底座装配", tuple(RAW[n] for n in (21, 22, 23, 27, 28))),
        (972110, "S-20", "立柱及高度调节装配", tuple(RAW[n] for n in (24, 25, 26))),
        (972111, "S-30", "底座完工检验", ()),
    )),
)


class Expr(str):
    """A fixed, reviewed SQL expression; never populated from user input."""


def literal(value):
    if isinstance(value, Expr):
        return str(value)
    if value is None:
        return "NULL"
    if isinstance(value, bool):
        return "b'1'" if value else "b'0'"
    if isinstance(value, (int, Decimal)):
        return str(value)
    return "'" + str(value).replace("\\", "\\\\").replace("'", "''") + "'"


def sop_steps(code, title):
    common = "本规程是教学配置，不代表实物已检验；实际操作须使用批准图纸、工艺参数和适用安全规程。"
    instructions = {
        "FG-10": "核对机头与底座产品编码、批次和父工单领料记录；安装俯仰连接旋钮，按批准工艺检查紧固和调节。",
        "FG-20": "依次安装后网罩、锁母、扇叶、锁帽、前网罩、锁圈；核对旋向及防漏装标识，停机检查手动转动无干涉。",
        "FG-30": "在断电条件下按批准走线图安装预制开关线束、电源线、摆头操作帽及2个固定夹；检查护线、连接及防夹伤。",
        "FG-40": "由授权人员使用适用设备执行批准的档位、摆头、俯仰和运行检查，记录真实结果；电气安全项目必须按批准技术要求及安全规程执行，异常先隔离处置。",
        "FG-50": "核对实物、铭牌、说明书及包装；执行正式末检并在质量模块记录真实结果，合格放行后才可转入成品仓。",
        "H-10": "核对电机、支架及4颗M4×12螺钉；按批准装配图安装，不加入齿箱或俯仰旋钮的重复用料。",
        "H-20": "安装摆头齿箱和后壳，按批准工艺检查配合与紧固；不包含另行在整机安装的摆头操作帽。",
        "H-30": "检查机头完整性、输出轴和摆头传动配合；正式末检记录真实结果后才可入半成品仓。",
        "S-10": "安装底座下壳、上盖、配重、M6紧固件套件和4个脚垫；按批准工艺检查连接和支承。",
        "S-20": "安装外立柱、伸缩内管及高度锁紧旋钮；检查调节和锁紧，不与机头俯仰旋钮混用。",
        "S-30": "检查底座支承、立柱锁紧和配件完整性；正式末检记录真实结果后才可入半成品仓。",
    }
    return [{"id": code + "-CHECK", "title": title + "必做确认", "instruction": common + instructions[code],
             "required": True, "minViewSeconds": 5, "photoRequired": True, "attachments": [], "parameters": []}]


def build_rows():
    rows = []

    def add(table, identity=None, **values):
        values.update(creator=OWNER, deleted=False, tenant_id=1)
        if table != "mes_cost_profile":
            values["updater"] = OWNER
        rows.append((table, values, tuple(identity or (["code"] if "code" in values else []))))

    units = {name: 972001 + i for i, name in enumerate(("台", "套", "件", "个", "颗", "张", "块", "本"))}
    for name, unit_id in units.items():
        add("mes_md_unit_measure", id=unit_id, code="BROKY-U" + str(unit_id - 972000), name=name,
            primary_flag=True, primary_id=0, change_rate=1, status=0, remark="Broky教学配置计量单位")
    for type_id, suffix, name, kind, sort in (
        (972001, "RAW", "Broky采购制造物料", "ITEM", 1),
        (972002, "PACK", "Broky采购包装物料", "ITEM", 2),
        (972003, "SA", "Broky自制半成品", "PRODUCT", 3),
        (972004, "FG", "Broky整机成品", "PRODUCT", 4),
    ):
        add("mes_md_item_type", id=type_id, code="BROKY-TYPE-" + suffix, name=name, parent_id=0,
            item_or_product=kind, sort=sort, status=0, remark="独立叶分类；自制半成品和整机均为PRODUCT")
    for item_id, code, name in ((FG, "BROKY-FG-V2", "Faze-UP Broky"),
                                (HEAD, "BROKY-SA-H", "Broky动力机头组件"),
                                (STAND, "BROKY-SA-S", "Broky立柱底座组件")):
        add("mes_md_item", id=item_id, code=code, name=name, specification="可调速摆头落地扇教学制造配置V2" if item_id == FG else "Broky自制半成品V2",
            unit_measure_id=units["台" if item_id == FG else "套"], item_type_id=972004 if item_id == FG else 972003,
            status=1, safe_stock_flag=False, min_stock=0, max_stock=0, high_value=False, batch_flag=True,
            remark="教学产品；SOP审核发布并完成配置核验后由正常API启用；不代表实物生产或认证")
    for n, (name, unit, spec) in enumerate(MATERIALS, 1):
        add("mes_md_item", id=RAW[n], code=f"BROKY-R{n:02d}", name="Broky" + name, specification=spec,
            unit_measure_id=units[unit], item_type_id=972002 if 12 <= n <= 15 else 972001,
            status=1, safe_stock_flag=False, min_stock=0, max_stock=0, high_value=False, batch_flag=True,
            remark="教学采购物料；初始库存为0，必须通过实际采购入库流程增加库存")
    flags = ("produce_date", "expire_date", "receipt_date", "vendor", "client", "sales_order_code", "purchase_order_code",
             "work_order", "task", "workstation", "tool", "mold", "lot_number", "quality_status")
    for item_id in (*PRODUCTS, *RAW.values()):
        enabled = {"produce_date", "work_order"} if item_id in PRODUCTS else {"receipt_date", "vendor", "lot_number"}
        add("mes_md_item_batch_config", identity=["item_id"], id=item_id, item_id=item_id,
            **{flag + "_flag": flag in enabled for flag in flags})
    bom_number = 0
    for product, children in BOMS.items():
        for child, qty in children:
            bom_number += 1
            add("mes_md_product_bom", identity=["item_id", "bom_item_id"], id=972300 + bom_number,
                item_id=product, bom_item_id=child, quantity=qty, status=0,
                remark="Broky V2本层单件用量；父工单不重复领取半成品内部原料")
    add("mes_md_client", id=972001, code="BROKY-CLIENT", name="Broky教学客户", nickname="教学客户", type=1,
        status=0, remark="虚构教学客户，无真实联系人；销售价格不代表市场报价")
    add("mes_md_vendor", id=972001, code="BROKY-VENDOR", name="Broky教学供应商", nickname="教学供应商", level="A",
        status=0, remark="虚构教学供应商；实际采购批号由入库时记录，不预置合格结果")
    add("mes_md_workshop", id=972001, code="BROKY-SHOP", name="Broky装配车间", area=120, status=0,
        remark="教学车间；实际人员和设备资源按现场配置")
    for warehouse_id, suffix, name in ((972001, "RAW", "原材料仓"), (972002, "SA", "半成品仓"), (972003, "FG", "成品仓")):
        add("mes_wm_warehouse", id=warehouse_id, code="BROKY-WH-" + suffix, name="Broky" + name, address="教学环境",
            area=50, frozen=False, remark="空仓配置；未预置库存或已完成交易")
        add("mes_wm_warehouse_location", id=warehouse_id, code="BROKY-LOC-" + suffix, name=name + "库区",
            warehouse_id=warehouse_id, area=50, frozen=False, remark="教学三级仓储")
        add("mes_wm_warehouse_area", id=warehouse_id, code="BROKY-AREA-" + suffix, name=name + "A01库位",
            location_id=warehouse_id, area=20, max_load=3000, status=0, frozen=False,
            allow_item_mixing=True, allow_batch_mixing=True, remark="教学库位；支持不同物料、批次分别记账")
    wip = {"warehouse_id": ("mes_wm_warehouse", "WIP_VIRTUAL_WAREHOUSE"),
           "location_id": ("mes_wm_warehouse_location", "WIP_VIRTUAL_LOCATION"),
           "area_id": ("mes_wm_warehouse_area", "WIP_VIRTUAL_AREA")}
    wip_refs = {key: Expr(f"(SELECT id FROM {table} WHERE code='{code}' AND tenant_id=1 AND deleted=b'0')")
                for key, (table, code) in wip.items()}
    allocation_number = 0
    for route_id, product, processes in ROUTES:
        add("mes_pro_route", id=route_id, code={FG: "BROKY-ROUTE-FG", HEAD: "BROKY-ROUTE-H", STAND: "BROKY-ROUTE-S"}[product],
            name={FG: "Broky整机装配路线V2", HEAD: "Broky机头制造路线V2", STAND: "Broky底座制造路线V2"}[product],
            description=" → ".join(process[2] for process in processes), status=1,
            remark="仅末道关键且检验；待SOP审核发布后启用")
        add("mes_pro_route_product", identity=["route_id", "item_id"], id=route_id,
            route_id=route_id, item_id=product, quantity=1, production_time={FG: 20, HEAD: 10, STAND: 6}[product],
            time_unit_type="MINUTE", remark="教学节拍定额，非实测工时；费用由价格成本版本独立维护")
        quantities = dict(BOMS[product])
        for idx, (process_id, suffix, title, consumed) in enumerate(processes):
            last = idx == len(processes) - 1
            add("mes_pro_process", id=process_id, code="BROKY-" + suffix, name="Broky" + title, status=0,
                attention="按受控SOP执行；只记录真实操作及检查证据", remark="Broky教学工序，不预设实测参数")
            add("mes_md_workstation", id=process_id, code="BROKY-ST-" + suffix, name="Broky" + title + "工作站",
                workshop_id=972001, process_id=process_id, status=0, **wip_refs,
                remark="复用系统虚拟线边库，库存按工单隔离")
            add("mes_pro_route_process", identity=["route_id", "process_id"], id=process_id, route_id=route_id,
                process_id=process_id, sort=(idx + 1) * 10,
                next_process_id=0 if last else processes[idx + 1][0], link_type=3, prepare_time=0, wait_time=0,
                color_code="#67C23A" if last else "#409EFF", key_flag=last, check_flag=last,
                remark="末道检验合格后产出；中间工序仅更新进度" if last else "中间质量通过必做SOP确认，不重复生成产品库存")
            for material in consumed:
                allocation_number += 1
                add("mes_pro_route_product_bom", identity=["route_id", "process_id", "product_id", "item_id"],
                    id=972500 + allocation_number, route_id=route_id, process_id=process_id,
                    product_id=product, item_id=material, quantity=quantities[material],
                    remark="本层BOM用量仅分配一次；报工按实际数量扣线边库存")
            add("mes_sop_document", identity=["item_id", "process_id"], id=process_id, code="BROKY-SOP-" + suffix,
                name="Broky" + title + "作业规程", item_id=product, process_id=process_id)
            add("mes_sop_version", identity=["document_id", "version_no"], id=process_id,
                document_id=process_id, version_no="V2-TEACHING-1", title="Broky" + title + "教学规程",
                change_note="教学配置草稿；通过正常提交、审批和发布流程后生效。不是实物检验记录或安全认证。",
                status="DRAFT", steps_json=json.dumps(sop_steps(suffix, title), ensure_ascii=False, separators=(",", ":")),
                attachments_json="[]", model_url="")
    # Text indicators deliberately avoid invented electrical/mechanical pass limits.
    indicators = (
        (972001, "INCOMING", "来料编码、批号和外观核对", 2, "按批准采购规格核对，异常隔离并记录；不预置合格结果"),
        (972002, "HEAD", "机头完整性与传动配合", 4, "按批准图纸和作业规程检查；记录实际发现和判定依据"),
        (972003, "STAND", "底座支承及立柱锁紧", 4, "按批准图纸检查支承、连接、调节和锁紧；记录实际结果"),
        (972004, "FG-ASSEMBLY", "整机装配及包装完整性", 2, "按批准BOM核对防漏装、铭牌、附件和包装"),
        (972005, "FG-FUNCTION", "档位、摆头、俯仰及运行检查", 4, "按批准技术文件及安全规程执行，不用教学数据替代真实结果"),
        (972006, "FG-SAFETY", "电气安全检验依据与记录", 4, "由授权人员按批准适用技术要求执行；记录文件版本、设备和真实结果，未完成不得判合格"),
    )
    for indicator_id, suffix, title, kind, method in indicators:
        add("mes_qc_indicator", id=indicator_id, code="BROKY-QI-" + suffix, name="Broky" + title, type=kind,
            tool="按批准检验文件选用适用设备", result_type=3, result_specification="",
            remark="教学定性检查项目；不虚构标准限值；" + method)
    templates = ((972001, "IQC", "采购来料检查", "1", "", tuple(RAW.values()), (972001,)),
                 (972002, "H", "机头完工检查", "2", "6", (HEAD,), (972002,)),
                 (972003, "S", "底座完工检查", "2", "6", (STAND,), (972003,)),
                 (972004, "FG", "整机末检与出货检查", "2,3", "6", (FG,), (972004, 972005, 972006)))
    template_item_n = template_indicator_n = 0
    methods = {i[0]: i[4] for i in indicators}
    for template_id, suffix, name, types, ipqc_types, items, checks in templates:
        add("mes_qc_template", id=template_id, code="BROKY-QT-" + suffix, name="Broky" + name,
            types=types, ipqc_types=ipqc_types, status=1,
            remark="教学检查模板，待审核启用；按件全检、不允许预设已检数量或结果；电气项目以批准文件为准")
        for item_id in items:
            template_item_n += 1
            add("mes_qc_template_item", identity=["template_id", "item_id"], id=972000 + template_item_n,
                template_id=template_id, item_id=item_id, quantity_check=1, quantity_unqualified=0,
                critical_rate=0, major_rate=0, minor_rate=0,
                remark="教学单件全检门槛：缺陷不得以预设结果放行；生产按实际检验数量记录")
        for indicator_id in checks:
            template_indicator_n += 1
            add("mes_qc_template_indicator", identity=["template_id", "indicator_id"], id=972000 + template_indicator_n,
                template_id=template_id, indicator_id=indicator_id, check_method=methods[indicator_id],
                standard_value=None, threshold_min=None, threshold_max=None, unit_measure_id=None, doc_url="",
                remark="文本检查不伪设数值阈值；正式量测标准须经批准另配置")
    order_number = 0
    # Child orders must be created through the service after parent confirmation;
    # never bypass its parent state, BOM membership and quantity gates via SQL.
    for product, code in ((FG, "BROKY-WO-FG-100"),):
        order_id = {FG: 972901, HEAD: 972902, STAND: 972903}[product]
        add("mes_pro_work_order", id=order_id, code=code, name="Broky100台基线教学草稿" if product == FG else "Broky100套半成品教学草稿",
            type=1, order_source_type=2, order_source_code="BROKY-TEACHING-100", product_id=product,
            quantity=100, quantity_produced=0, quantity_changed=0, quantity_scheduled=0, client_id=972001,
            parent_id=0, status=0, request_date=Expr("DATE_ADD(CURRENT_TIMESTAMP, INTERVAL 7 DAY)"),
            remark="仅草稿，不代表排产或生产；先完成采购来料、真实入库，再子工单生产并入半成品仓，父工单仅领本层物料；确认前设置交期")
        for child, quantity in BOMS[product]:
            order_number += 1
            add("mes_pro_work_order_bom", identity=["work_order_id", "item_id"], id=972900 + order_number,
                work_order_id=order_id, item_id=child, quantity=100 * quantity,
                remark="草稿本层BOM快照；原料需求可另展开汇总，不能将叶子汇总作为父工单重复领料")
    for item_id in (*PRODUCTS, *RAW.values()):
        add("mes_cost_profile", identity=["item_id", "version"], id=item_id, item_id=item_id, version=1,
            purchase_price=PRICES[item_id - 972201] if item_id in RAW.values() else None,
            sale_price=Decimal("299") if item_id == FG else None,
            labor_cost={FG: Decimal("10"), HEAD: Decimal("5"), STAND: Decimal("3")}.get(item_id),
            overhead_cost={FG: Decimal("3"), HEAD: Decimal("2"), STAND: Decimal("1")}.get(item_id),
            remark="教学预算设定；CNY人民币不含税；非市场报价、非实测成本；加工费仅为本层，不重复计半成品费用")
    return rows


def validate(rows):
    # Validate only the tables this migration writes.  Unrelated DOs may contain
    # complex mapper-only fields that the older whole-schema generator rejects.
    contracts = {}
    for path in (JAVA / "dal/dataobject").rglob("*.java"):
        source = path.read_text(encoding="utf-8-sig")
        match = re.search(r'@TableName\(\s*(?:value\s*=\s*)?"([^\"]+)"', source)
        if match:
            contracts[match[1]] = {"columns": {col for _, col, _, _ in fields(source)} |
                {"creator", "create_time", "updater", "update_time", "deleted", "tenant_id"}}
    contracts["mes_cost_profile"] = {"columns": set(("id", "tenant_id", "item_id", "version", "purchase_price", "sale_price",
        "labor_cost", "overhead_cost", "remark", "creator", "create_time", "deleted"))}
    seen = set()
    by_table = defaultdict(list)
    for table, row, identity in rows:
        if table not in contracts or set(row) - set(contracts[table]["columns"]):
            raise ValueError(f"Unrecognised persistence columns: {table}: {set(row) - set(contracts.get(table, {}).get('columns', {}))}")
        key = (table, row["id"])
        if key in seen:
            raise ValueError(f"Duplicate fixed id: {key}")
        seen.add(key)
        by_table[table].append(row)
    assert len(by_table["mes_md_item"]) == 31
    assert len(BOMS[FG]) == 17 and len(BOMS[HEAD]) == 5 and len(BOMS[STAND]) == 8
    assert sum(1 for c, _ in BOMS[FG] if c in set(RAW.values()) - {RAW[n] for n in range(12, 16)}) == 11
    allocation = defaultdict(lambda: defaultdict(Decimal))
    for r in by_table["mes_pro_route_product_bom"]:
        allocation[r["product_id"]][r["item_id"]] += Decimal(r["quantity"])
    for product, bom in BOMS.items():
        assert dict(allocation[product]) == dict(bom), "Every BOM item must be allocated exactly once"
    for route, product, processes in ROUTES:
        process_rows = [r for r in by_table["mes_pro_route_process"] if r["route_id"] == route]
        assert sum(r["key_flag"] for r in process_rows) == sum(r["check_flag"] for r in process_rows) == 1
        assert process_rows[-1]["key_flag"] and process_rows[-1]["check_flag"]
    profiles = {r["item_id"]: r for r in by_table["mes_cost_profile"]}

    def rolled(product, ancestry=()):
        assert product not in ancestry, "BOM cycle"
        if product not in BOMS:
            return (profiles[product]["purchase_price"], Decimal(0), Decimal(0))
        parts = [rolled(child, (*ancestry, product)) for child, _ in BOMS[product]]
        values = [sum(part[k] * qty for part, (_, qty) in zip(parts, BOMS[product])) for k in range(3)]
        return values[0], values[1] + profiles[product]["labor_cost"], values[2] + profiles[product]["overhead_cost"]

    def leaves(product, multiple=Decimal(1), ancestry=()):
        assert product not in ancestry, "BOM cycle"
        if product not in BOMS:
            return {product: multiple}
        result = defaultdict(Decimal)
        for child, qty in BOMS[product]:
            for material, required in leaves(child, multiple * qty, (*ancestry, product)).items():
                result[material] += required
        return dict(result)

    leaf_requirements = leaves(FG)
    assert set(leaf_requirements) == set(RAW.values()), "Every purchased material must be used"
    assert len(leaf_requirements) == 28
    assert leaf_requirements[RAW[20]] == leaf_requirements[RAW[28]] == Decimal(4)
    assert leaf_requirements[RAW[11]] == leaf_requirements[RAW[14]] == Decimal(2)
    assert rolled(FG) == (Decimal("148"), Decimal("18"), Decimal("6"))
    assert sum(rolled(HEAD)) == Decimal("85.5") and sum(rolled(STAND)) == Decimal("31.5")
    assert {r["status"] for r in by_table["mes_sop_version"]} == {"DRAFT"}
    assert all(r["status"] == 0 for r in by_table["mes_pro_work_order"])
    assert not any(table in by_table for table in ("mes_wm_material_stock", "mes_wm_transaction", "mes_qc_ipqc", "mes_pro_feedback"))
    return {"items": 31, "directBomItems": 17, "directManufacturingMaterials": 11, "leafMaterials": 28,
            "manufacturingLeafMaterials": 24, "bomLevels": 3, "routes": 3, "processes": 11, "draftSops": 11,
            "draftOrders": 1, "standardMaterial": "148.00", "standardLabor": "18.00", "standardOverhead": "6.00",
            "standardCost": "172.00", "salePrice": "299.00", "currency": "CNY", "taxIncluded": False,
            "source": "teaching budget, not actuals", "rowsByTable": dict(sorted(Counter(t for t, _, _ in rows).items()))}


def render_sql(rows):
    lines = ["-- Generated by database/tools/generate_broky_product.py; edit the generator, then regenerate.",
             "-- Faze-UP Broky: independent teaching configuration, not completed business records.",
             "-- Prerequisites: mes_schema.sql + mes_seed.sql; md_qc_coverage, controlled_sop, broky_cost migrations.",
             "-- Apply to the dedicated FazeMES database with a MySQL client supporting DELIMITER.",
             "-- Fixed IDs are reserved in 972xxx. Unexpected ownership/natural-key collisions abort atomically.",
             "-- Re-running inserts only missing rows; it never resets changes, prices, published SOPs or executed orders.",
             "-- All items/routes/templates begin DISABLED and all SOP versions DRAFT. Publish using the normal API.",
             "-- No stock, batch, financial actual, receipt execution, inspection or approval result is fabricated.",
             "SET NAMES utf8mb4;", "DROP PROCEDURE IF EXISTS fazemes_seed_broky_20261018;", "DELIMITER $$",
             "CREATE PROCEDURE fazemes_seed_broky_20261018()", "BEGIN", "  DECLARE seed_lock INT DEFAULT 0;",
             "  DECLARE EXIT HANDLER FOR SQLEXCEPTION", "  BEGIN", "    ROLLBACK;",
             "    IF seed_lock = 1 THEN DO RELEASE_LOCK(CONCAT(DATABASE(), ':broky-seed-v1')); END IF;", "    RESIGNAL;", "  END;",
             "  SELECT GET_LOCK(CONCAT(DATABASE(), ':broky-seed-v1'), 10) INTO seed_lock;",
             "  IF seed_lock IS NULL OR seed_lock <> 1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Broky seed lock unavailable'; END IF;",
             "  START TRANSACTION;",
             "  IF (SELECT COUNT(*) FROM mes_wm_warehouse WHERE code='WIP_VIRTUAL_WAREHOUSE' AND tenant_id=1 AND deleted=b'0') <> 1",
             "     OR (SELECT COUNT(*) FROM mes_wm_warehouse_location WHERE code='WIP_VIRTUAL_LOCATION' AND tenant_id=1 AND deleted=b'0') <> 1",
             "     OR (SELECT COUNT(*) FROM mes_wm_warehouse_area WHERE code='WIP_VIRTUAL_AREA' AND tenant_id=1 AND deleted=b'0') <> 1 THEN",
             "    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Broky requires unique configured system WIP warehouse/location/area';", "  END IF;", ""]
    lines += ["  IF NOT EXISTS (SELECT 1 FROM mes_wm_warehouse w JOIN mes_wm_warehouse_location l ON l.warehouse_id=w.id",
              "      JOIN mes_wm_warehouse_area a ON a.location_id=l.id WHERE w.tenant_id=1 AND l.tenant_id=1 AND a.tenant_id=1",
              "      AND w.code='WIP_VIRTUAL_WAREHOUSE' AND l.code='WIP_VIRTUAL_LOCATION' AND a.code='WIP_VIRTUAL_AREA'",
              "      AND w.deleted=b'0' AND l.deleted=b'0' AND a.deleted=b'0') THEN",
              "    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Broky system WIP warehouse/location/area hierarchy is inconsistent';", "  END IF;", ""]
    for table, row, identity in rows:
        same_id = ["tenant_id <> 1", "deleted <> b'0'", f"creator <> {literal(OWNER)}"]
        if "code" in row:
            same_id.append(f"NOT (`code` <=> {literal(row['code'])})")
        collision = f"(`id`={row['id']} AND ({' OR '.join(same_id)}))"
        if identity:
            natural = " AND ".join(f"`{key}` <=> {literal(row[key])}" for key in identity)
            collision += f" OR (`tenant_id`=1 AND `id`<>{row['id']} AND {natural})"
        lines += [f"  IF EXISTS (SELECT 1 FROM `{table}` WHERE {collision}) THEN",
                  f"    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '{table} Broky reserved identity {row['id']} conflicts';", "  END IF;"]
    lines.append("")
    for table, row, _ in rows:
        columns = ",".join(f"`{key}`" for key in row)
        values = ",".join(literal(value) for value in row.values())
        lines.append(f"  INSERT INTO `{table}` ({columns}) SELECT {values} WHERE NOT EXISTS (SELECT 1 FROM `{table}` WHERE id={row['id']});")
    lines += ["  COMMIT;", "  DO RELEASE_LOCK(CONCAT(DATABASE(), ':broky-seed-v1'));", "  SET seed_lock=0;", "END$$", "DELIMITER ;",
              "CALL fazemes_seed_broky_20261018();", "DROP PROCEDURE fazemes_seed_broky_20261018;", ""]
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    rows = build_rows()
    report = validate(rows)
    sql = render_sql(rows)
    if args.check:
        if not OUT.exists() or OUT.read_text(encoding="utf-8") != sql:
            raise SystemExit("Broky migration differs from generator; regenerate and review it")
    else:
        OUT.write_text(sql, encoding="utf-8")
    print(json.dumps({"result": "passed", "mode": "static-only", "migrationMatches": True, **report}, ensure_ascii=False))


if __name__ == "__main__":
    main()
