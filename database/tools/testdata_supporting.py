"""Deterministic supporting MES fixtures. Called by the reset orchestrator only.

All content is synthetic. No model calls, credentials, usage claims or biometric
samples are created. Enum values follow the current Java service contracts.
"""
from __future__ import annotations

from datetime import timedelta
import hashlib
import json


SCENARIOS = [
    {"code": "DV-01", "name": "设备分类、台账与工作站资源", "expectation": "设备分类父子树有效；12台设备覆盖停机、生产、维护；六个工作站绑定独占设备及真实岗位人员、工装。"},
    {"code": "DV-02", "name": "点检保养周期与任务认领", "expectation": "日点检、周保养及月/年草稿方案；45天历史加当日任务，覆盖待认领、已认领、已完成，未来游标避免重复生成。"},
    {"code": "DV-03", "name": "异常自动报修与验收", "expectation": "点检和保养异常对应唯一自动报修来源，覆盖草稿、维修、待验收、验收通过和验收失败退回维修。"},
    {"code": "TM-01", "name": "工装数量和维护边界", "expectation": "编码管理与数量管理、按日期/次数维护，在库/领用/维修/报废、零可用、到期和未来维护均有可筛选记录。"},
    {"code": "CAL-01", "name": "单白班、两班与三班排班", "expectation": "91天确认排班、跨午夜夜班、按天/周轮换；草稿月/季轮换；日期及班组、班次归属一致，人员排班不重叠。"},
    {"code": "CAL-02", "name": "工作日与停工覆盖", "expectation": "连续91天显式工作日/节假日，包含合成的周末调休与工作日停工，避免把测试日期误当法定节假日。"},
    {"code": "ANDON-01", "name": "三级安灯与处置", "expectation": "三种级别、六类原因，待处置与已处置并存；处置人员及时间完整，呼叫原因和级别保留快照。"},
    {"code": "REPORT-01", "name": "五种白名单业务报表与版本", "expectation": "工单、报工、库存、质检、设备任务报表配置均有效，包含分组汇总、日期筛选和A4画布；仓储补充流程发布两份并为首份保留新草稿版本。"},
    {"code": "KB-01", "name": "合成知识库草稿与原文校验", "expectation": "三个知识库、六份草稿，UTF-8原始文件、SHA-256、标题分段、字符偏移与片段哈希一致；不伪造模型对话。"},
]


def _json(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


def _sha(value):
    return hashlib.sha256(value).hexdigest()


def seed(ctx):
    """Insert records using the orchestrator's schema-checked add interface."""
    now = ctx.now.replace(microsecond=0)
    today = now.replace(hour=0, minute=0, second=0)
    users = ctx.ids["users"]
    stations = ctx.ids["stations"]
    workshops = ctx.ids["workshops"]
    admin = ctx.ids.get("admin", 1)
    engineer = users[6]
    operators = [users[2], users[3], users[11]]
    operator_post = ctx.ids["posts"][2]
    remark = "TEST-SUITE-V1 合成测试数据；非真实生产记录"
    seq = {}

    def add(table, **values):
        if "id" not in values:
            seq[table] = seq.get(table, 980000) + 1
            values["id"] = seq[table]
        return ctx.add(table, **values)

    # Build evidence in memory before inserting equipment so latest event times
    # and repair-driven machinery states are accurate without post-insert edits.
    machine_ids = list(range(980001, 980013))
    machinery_names = ["数控加工中心01", "数控加工中心02", "自动压装机01", "自动压装机02", "扭矩装配台01", "在线检验台01", "备用加工中心01", "故障诊断台01", "待修压装机01", "待验收检验台01", "返修装配台01", "封存备用机01"]
    last_check = {m: None for m in machine_ids}
    last_mainten = {m: None for m in machine_ids}
    task_specs = []
    check_plan = 980001
    maint_plan = 980002
    fault_check_plan = 980003
    fault_maint_plan = 980004
    daily_due = today.replace(hour=8)
    check_offsets = list(range(-45, 1 if daily_due <= now else 0))
    for offset in check_offsets:
        due = daily_due + timedelta(days=offset)
        for mi in range(6):
            completed = offset < 0
            spec = {"plan": check_plan, "type": 1, "machine": machine_ids[mi], "due": due,
                    "subjects": [980001, 980002, 980003], "status": 2 if completed else mi % 2,
                    "completed": due + timedelta(minutes=20 + mi) if completed else None,
                    "abnormal": False}
            task_specs.append(spec)
            if completed:
                last_check[spec["machine"]] = spec["completed"]
    weekly_due = today.replace(hour=9)
    weekly_offsets = list(range(-42, 1 if weekly_due <= now else 0, 7))
    for offset in weekly_offsets:
        due = weekly_due + timedelta(days=offset)
        for mi in range(6):
            completed = offset < 0
            spec = {"plan": maint_plan, "type": 2, "machine": machine_ids[mi], "due": due,
                    "subjects": [980004, 980005, 980006], "status": 2 if completed else mi % 2,
                    "completed": due + timedelta(minutes=50 + mi) if completed else None,
                    "abnormal": False}
            task_specs.append(spec)
            if completed:
                last_mainten[spec["machine"]] = spec["completed"]
    # One isolated fault per bench asset gives each repair state its own fixture.
    for i, status in enumerate([4, 0, 1, 2, 1]):
        task_type = 1 if i < 3 else 2
        due = today - timedelta(days=2) + timedelta(hours=8)
        completed = due + timedelta(minutes=30 + i)
        machine = machine_ids[6 + i]
        task_specs.append({"plan": fault_check_plan if task_type == 1 else fault_maint_plan,
                           "type": task_type, "machine": machine, "due": due,
                           "subjects": [980001, 980002, 980003] if task_type == 1 else [980004, 980005, 980006],
                           "status": 2, "completed": completed, "abnormal": True,
                           "repair_status": status, "repair_failed": i == 4})
        (last_check if task_type == 1 else last_mainten)[machine] = completed

    root_type = add("mes_dv_machinery_type", code="TS-DVT-ROOT", name="测试设备", parent_id=0, status=0, sort=1, remark=remark)
    machine_types = [add("mes_dv_machinery_type", code=f"TS-DVT-{i:02}", name=name,
                         parent_id=root_type, status=0, sort=i, remark=remark)
                     for i, name in enumerate(["机加工设备", "压装设备", "装配设备", "检验设备"], 1)]
    add("mes_dv_machinery_type", code="TS-DVT-DISABLED", name="停用分类", parent_id=root_type, status=1, sort=99, remark="分类停用筛选用；无活动设备引用")
    for i, machine in enumerate(machine_ids):
        add("mes_dv_machinery", id=machine, code=f"TS-DV-{i + 1:03}", name=machinery_names[i],
            brand="TEST-FAB", specification=f"TS-{['CNC', 'PRESS', 'ASSEMBLY', 'QC'][i % 4]}-{i + 1:02}",
            machinery_type_id=machine_types[(i // 2) % 4], workshop_id=workshops[min(i // 2, 2)],
            status=3 if 7 <= i <= 10 else (2 if i in (0, 2, 4) else 1),
            last_check_time=last_check[machine], last_mainten_time=last_mainten[machine], remark=remark)
    subjects = [
        (1, "安全联锁检查", "逐项检查急停、门锁和安全光幕。", "急停复位有效，安全门打开时禁止循环"),
        (1, "气源与油压检查", "核对面板气压和润滑油位，观察接头渗漏。", "气压0.50至0.70MPa，油位在上下限内且无渗漏"),
        (1, "定位与外观检查", "清洁定位面并检查夹具紧固及警示灯。", "无松动、异物及红色故障提示"),
        (2, "润滑系统保养", "停机隔离能源后清洁过滤网并补充指定润滑油。", "过滤网洁净，复机后润滑压力稳定"),
        (2, "紧固与传动保养", "核对扭矩基准并检查传动防护和限位。", "紧固标记一致，防护和限位有效"),
        (2, "精度与功能复核", "使用标准件复核重复定位及设备全循环。", "标准件复测通过，连续三循环无报警"),
    ]
    for i, (kind, name, content, standard) in enumerate(subjects, 1):
        add("mes_dv_subject", id=980000 + i, code=f"TS-DVS-{i:03}", name=name,
            type=kind, content=content, standard=standard, status=0, remark=remark)
    add("mes_dv_subject", id=980007, code="TS-DVS-OLD", name="旧版点检项目", type=1,
        content="仅供停用项目筛选验证", standard="不加入已启用方案", status=1, remark=remark)
    plans = {
        check_plan: ("TS-DVP-DAY", "日常开机点检", 1, 1, 1, daily_due - timedelta(days=45), daily_due + timedelta(days=1 if daily_due <= now else 0), machine_ids[:6], [980001, 980002, 980003]),
        maint_plan: ("TS-DVP-WEEK", "设备周预防保养", 2, 2, 1, weekly_due - timedelta(days=42), weekly_due + timedelta(days=7 if weekly_due <= now else 0), machine_ids[:6], [980004, 980005, 980006]),
        fault_check_plan: ("TS-DVP-FAULT-C", "备用设备专项点检", 1, 2, 1, today - timedelta(days=2) + timedelta(hours=8), today + timedelta(days=5, hours=8), machine_ids[6:9], [980001, 980002, 980003]),
        fault_maint_plan: ("TS-DVP-FAULT-M", "检验设备专项保养", 2, 2, 1, today - timedelta(days=2) + timedelta(hours=8), today + timedelta(days=5, hours=8), machine_ids[9:11], [980004, 980005, 980006]),
    }
    for plan_id, (code, name, kind, cycle, count, start, next_due, machines, project_ids) in plans.items():
        add("mes_dv_check_plan", id=plan_id, code=code, name=name, type=kind,
            start_date=start, end_date=today + timedelta(days=365, hours=23, minutes=59),
            cycle_type=cycle, cycle_count=count, status=1, next_task_time=next_due, remark=remark)
        for machine in machines:
            add("mes_dv_check_plan_machinery", plan_id=plan_id, machinery_id=machine, remark=remark)
        for subject in project_ids:
            add("mes_dv_check_plan_subject", plan_id=plan_id, subject_id=subject, remark=remark)
    for plan_id, cycle, name in [(980005, 3, "月末精度保养草稿"), (980006, 4, "年度设备大修草稿")]:
        add("mes_dv_check_plan", id=plan_id, code=f"TS-DVP-DRAFT-{cycle}", name=name,
            type=2, start_date=today + timedelta(days=7), end_date=today + timedelta(days=730),
            cycle_type=cycle, cycle_count=1, status=0, next_task_time=None, remark=remark)
        add("mes_dv_check_plan_machinery", plan_id=plan_id, machinery_id=machine_ids[11], remark=remark)
        for subject in [980004, 980005, 980006]:
            add("mes_dv_check_plan_subject", plan_id=plan_id, subject_id=subject, remark=remark)

    for spec in task_specs:
        completed = spec["completed"]
        claimed = completed - timedelta(minutes=15) if completed else (min(now, spec["due"] + timedelta(minutes=1)) if spec["status"] == 1 else None)
        record_id = repair_id = None
        fault_text = "测试故障：定位气缸动作迟滞，复测超出标准；设备已隔离，待设备工程师处理。"
        if completed:
            record_table = "mes_dv_check_record" if spec["type"] == 1 else "mes_dv_mainten_record"
            time_field = "check_time" if spec["type"] == 1 else "mainten_time"
            record_id = add(record_table, plan_id=spec["plan"], machinery_id=spec["machine"],
                            user_id=engineer, status=20 if spec["type"] == 1 else 4,
                            remark=remark, create_time=claimed, update_time=completed, **{time_field: completed})
            for j, subject in enumerate(spec["subjects"]):
                bad = spec["abnormal"] and j == 0
                line_values = {"record_id": record_id, "subject_id": subject, "remark": remark,
                               "create_time": completed, "update_time": completed}
                if spec["type"] == 1:
                    add("mes_dv_check_record_line", **line_values, check_status=2 if bad else 1, check_result=fault_text if bad else None)
                else:
                    add("mes_dv_mainten_record_line", **line_values, status=2 if bad else 1, result=fault_text if bad else None)
            if spec["abnormal"]:
                state = spec["repair_status"]
                failed = spec["repair_failed"]
                finish = completed + timedelta(hours=2) if state in (2, 4) or failed else None
                confirm = completed + timedelta(hours=3) if state == 4 or failed else None
                source_type = 101 if spec["type"] == 1 else 102
                repair_id = add("mes_dv_repair", code=f"DV-AUTO-{source_type}-{record_id}",
                    name="点检异常自动报修" if spec["type"] == 1 else "保养异常自动报修",
                    machinery_id=spec["machine"], require_date=completed, finish_date=finish, confirm_date=confirm,
                    result=2 if failed else (1 if state == 4 else None), accepted_user_id=engineer if state != 0 else None,
                    confirm_user_id=admin if confirm else None, source_doc_type=source_type, source_doc_id=record_id,
                    source_doc_code=("CHECK-" if spec["type"] == 1 else "MAINTEN-") + str(record_id), status=state,
                    remark="测试验收失败已退回维修，设备继续维护" if failed else remark,
                    create_time=completed, update_time=confirm or finish or completed)
                add("mes_dv_repair_line", repair_id=repair_id, subject_id=spec["subjects"][0],
                    malfunction=fault_text, malfunction_url=None,
                    description="已更换密封件并复测，连续三循环通过。" if state == 4 else ("复测仍有迟滞，验收不通过，继续排查。" if failed else ("已完成初步检查，等待复测或验收。" if state in (1, 2) else None)),
                    remark=remark, create_time=completed, update_time=confirm or finish or completed)
        task_id = add("mes_dv_task", plan_id=spec["plan"], plan_name=plans[spec["plan"]][1],
            machinery_id=spec["machine"], machinery_name=machinery_names[spec["machine"] - 980001],
            type=spec["type"], due_time=spec["due"], status=spec["status"],
            user_id=engineer if spec["status"] else None, claimed_time=claimed,
            completed_time=completed, record_id=record_id, repair_id=repair_id,
            create_time=spec["due"], update_time=completed or claimed or spec["due"])
        for j, subject in enumerate(spec["subjects"]):
            _, subject_name, content, standard = subjects[subject - 980001]
            add("mes_dv_task_line", task_id=task_id, subject_id=subject, subject_name=subject_name,
                content=content, standard=standard, result=(2 if spec["abnormal"] and j == 0 else 1) if completed else None,
                description=fault_text if completed and spec["abnormal"] and j == 0 else None,
                create_time=spec["due"], update_time=completed or spec["due"])
    # Editable manual drafts stay outside the reserved automatic-source namespace.
    manual_repair = add("mes_dv_repair", code="TS-DVR-MANUAL", name="备用机预防维修草稿",
        machinery_id=machine_ids[11], require_date=today - timedelta(days=1), finish_date=None,
        confirm_date=None, result=None, accepted_user_id=None, confirm_user_id=None,
        source_doc_type=None, source_doc_id=None, source_doc_code=None, status=0, remark=remark)
    add("mes_dv_repair_line", repair_id=manual_repair, subject_id=980004,
        malfunction="测试发现备用机润滑滤芯接近更换周期，申请预防维护。", malfunction_url=None,
        description=None, remark=remark)
    # Two editable records cover the manual draft screens without claiming work.
    draft_check = add("mes_dv_check_record", plan_id=check_plan, machinery_id=machine_ids[0],
        check_time=now, user_id=engineer, status=10, remark="测试点检草稿，未计入设备最近完成时间")
    for subject in [980001, 980002, 980003]:
        add("mes_dv_check_record_line", record_id=draft_check, subject_id=subject, check_status=1, check_result=None, remark=remark)
    draft_maint = add("mes_dv_mainten_record", plan_id=maint_plan, machinery_id=machine_ids[1],
        mainten_time=now, user_id=engineer, status=0, remark="测试保养草稿，未计入设备最近完成时间")
    for subject in [980004, 980005, 980006]:
        add("mes_dv_mainten_record_line", record_id=draft_maint, subject_id=subject, status=1, result=None, remark=remark)

    tool_types = []
    tools = []
    for i, name in enumerate(["扭矩扳手", "定位夹具", "压装治具", "检验量规", "装配工装", "校准标准件"]):
        kind = 1 if i % 2 == 0 else 2
        tool_type = add("mes_tm_tool_type", code=f"TS-TMT-{i + 1:02}", name=name,
            code_flag=i % 2 == 0, mainten_type=kind, mainten_period=30 if kind == 1 else 1000, remark=remark)
        tool_types.append(tool_type)
        for alt in range(2):
            status = 1 if alt == 0 else [2, 3, 4, 1, 2, 3][i]
            quantity = 1 if i % 2 == 0 else 4
            available = quantity if status == 1 else (max(0, quantity - 1) if status == 2 else 0)
            tool_id = add("mes_tm_tool", code=f"TS-TM-{i + 1:02}-{alt + 1}", name=f"{name}{'主用' if alt == 0 else '备件'}",
                brand="TEST-METROLOGY", specification=f"TS-GAUGE-{i + 1:02}", tool_type_id=tool_type,
                quantity=quantity, available_quantity=available, mainten_type=kind,
                next_mainten_period=1000 if kind == 2 else None,
                next_mainten_date=today + timedelta(days=30 if alt == 0 else -1) if kind == 1 else None,
                status=status, remark=remark)
            tools.append(tool_id)
    for i, station in enumerate(stations):
        add("mes_md_workstation_machine", workstation_id=station, machinery_id=machine_ids[i], quantity=1, remark=remark)
        add("mes_md_workstation_tool", workstation_id=station, tool_type_id=tool_types[i], quantity=1,
            tool_ids=str(tools[i * 2]), remark="绑定实际可用工装；备用、维修和报废工具不计入此工作站")
        add("mes_md_workstation_worker", workstation_id=station, post_id=operator_post, quantity=1,
            user_ids=",".join(str(user) for user in operators), remark="三班操作人员池，每班一名在岗；以实际班次校验可用性")
    ctx.ids.update({"machines": machine_ids, "tools": tools, "tool_types": tool_types})

    _seed_calendar(ctx, add, today, users, operators, engineer, remark)
    _seed_andon(ctx, add, today, now, stations, operators, engineer, remark)
    _seed_reports(ctx, add, today, remark)
    _seed_knowledge(ctx, add, admin, remark)


def _seed_calendar(ctx, add, today, users, operators, engineer, remark):
    teams = []
    for i, (name, kind, members) in enumerate([
        ("装配甲班", 3, [operators[0]]), ("装配乙班", 3, [operators[1]]), ("装配丙班", 3, [operators[2]]),
        ("质量甲班", 2, [users[4]]), ("质量乙班", 2, [users[5]]), ("设备保障班", 4, [engineer]),
    ]):
        team = add("mes_cal_team", code=f"TS-TEAM-{i + 1:02}", name=name, calendar_type=kind, remark=remark)
        teams.append(team)
        for member in members:
            add("mes_cal_team_member", team_id=team, user_id=member, remark=remark)
    start = today - timedelta(days=45)
    end = today + timedelta(days=45)
    configs = [
        ("三班连续生产", 3, 3, 4, 7, teams[:3], [("早班", "06:00", "14:00"), ("中班", "14:00", "22:00"), ("夜班", "22:00", "06:00")]),
        ("质量两班巡检", 2, 2, 3, None, teams[3:5], [("白班", "08:00", "20:00"), ("夜班", "20:00", "08:00")]),
        ("设备保障日班", 4, 1, None, None, teams[5:], [("日班", "08:00", "17:00")]),
    ]
    plan_ids = []
    for pindex, (name, kind, shift_type, method, shift_count, assigned, shifts) in enumerate(configs, 1):
        plan = add("mes_cal_plan", code=f"TS-CAL-{pindex:02}", name=name, calendar_type=kind,
            start_date=start, end_date=end, shift_type=shift_type, shift_method=method,
            shift_count=shift_count, status=1, remark=remark)
        plan_ids.append(plan)
        shift_ids = [add("mes_cal_plan_shift", plan_id=plan, sort=i + 1, name=s[0], start_time=s[1], end_time=s[2], remark=remark)
                     for i, s in enumerate(shifts)]
        for team in assigned:
            add("mes_cal_plan_team", plan_id=plan, team_id=team, remark=remark)
        rotation = 0
        for day_offset in range(91):
            day = start + timedelta(days=day_offset)
            if day_offset and shift_type != 1:
                if method == 4 and day_offset % shift_count == 0:
                    rotation += 1
                elif method == 3 and day.weekday() == 0:
                    rotation += 1
            for team_index, team in enumerate(assigned):
                shift_index = (team_index + rotation) % shift_type
                add("mes_cal_team_shift", plan_id=plan, team_id=team, shift_id=shift_ids[shift_index],
                    day=day, sort=shift_index + 1, remark=remark)
    # Independent future drafts avoid overlapping confirmed user assignments.
    for offset, method, name in [(1, 2, "下月两班月轮换草稿"), (2, 1, "季度三班轮换草稿")]:
        shift_type = offset + 1
        plan = add("mes_cal_plan", code=f"TS-CAL-DRAFT-{offset}", name=name, calendar_type=3,
            start_date=end + timedelta(days=1), end_date=end + timedelta(days=90),
            shift_type=shift_type, shift_method=method, shift_count=None, status=0, remark=remark)
        for i, (label, begin, finish) in enumerate(configs[0 if shift_type == 3 else 1][6]):
            add("mes_cal_plan_shift", plan_id=plan, sort=i + 1, name=label, start_time=begin, end_time=finish, remark=remark)
        for team in teams[:shift_type]:
            add("mes_cal_plan_team", plan_id=plan, team_id=team, remark=remark)
    # Synthetic exceptions are explicitly labelled; no legal-holiday inference.
    for offset in range(-45, 46):
        day = today + timedelta(days=offset)
        workday = day.weekday() < 5
        note = "测试日历：常规工作日" if workday else "测试日历：常规周末"
        if offset == -7:
            workday, note = True, "测试日历：周末调班工作日（合成场景，并非法定安排）"
        elif offset == -2:
            workday, note = False, "测试日历：计划停工日（合成场景，并非法定假日）"
        add("mes_cal_holiday", day=day, type=1 if workday else 2, remark=note)
    ctx.ids.update({"teams": teams, "calendar_plans": plan_ids})


def _seed_andon(ctx, add, today, now, stations, operators, engineer, remark):
    reasons = [("物料补给请求", 1), ("工具换型支援", 1), ("尺寸异常需质量复核", 2),
               ("设备停机需维修", 2), ("安全联锁异常", 3), ("批次追溯信息不一致", 3)]
    configs = []
    for reason, level in reasons:
        configs.append(add("mes_pro_andon_config", reason=reason, level=level,
                           handler_role_id=None, handler_user_id=engineer, remark=remark))
    for i in range(72):
        config_index = i % len(configs)
        reason, level = reasons[config_index]
        handled = i < 66
        created = today - timedelta(days=1 + i // 6) + timedelta(hours=8, minutes=i % 60)
        handle_time = created + timedelta(minutes=10 + (i % 6) * 5) if handled else None
        station = stations[i % len(stations)]
        process = ctx.scalar(f"SELECT process_id FROM mes_md_workstation WHERE id={int(station)} AND tenant_id=1 AND deleted=0")
        add("mes_pro_andon_record", config_id=configs[config_index], workstation_id=station,
            user_id=operators[i % len(operators)], work_order_id=None, process_id=process,
            reason=reason, level=level, status=1 if handled else 0, handle_time=handle_time,
            handler_user_id=engineer if handled else None,
            remark="测试处置完成：原因确认、措施落实及现场复核已记录。" if handled else "测试待处置呼叫，请通过安灯处置页面处理。",
            create_time=created, update_time=handle_time or created)
    ctx.ids["andon_configs"] = configs


def _seed_reports(ctx, add, today, remark):
    specs = [
        ("workorders", "测试工单完成量", "bar", [("status", "状态", None), ("quantity", "计划数量", "SUM"), ("quantity_produced", "已产数量", "SUM")], ["status"], "quantity"),
        ("feedback", "测试工位报工质量", "bar", [("workstation_id", "工作站", None), ("feedback_quantity", "报工数量", "SUM"), ("qualified_quantity", "合格数量", "SUM"), ("unqualified_quantity", "不合格数量", "SUM")], ["workstation_id"], "feedback_quantity"),
        ("inventory", "测试批次库存明细", "table", [("item_id", "物料", None), ("batch_code", "批次", None), ("warehouse_id", "仓库", None), ("quantity", "现存量", None), ("frozen", "冻结", None)], [], "quantity"),
        ("quality", "测试过程质检结果", "pie", [("check_result", "检验结果", None), ("qualified_quantity", "合格数量", "SUM"), ("unqualified_quantity", "不合格数量", "SUM")], ["check_result"], "qualified_quantity"),
        ("equipmentTasks", "测试设备任务明细", "table", [("machinery_name", "设备", None), ("plan_name", "方案", None), ("type", "类型", None), ("status", "状态", None), ("due_time", "到期时间", None), ("completed_time", "完成时间", None)], [], "due_time"),
    ]
    report_ids = []
    for index, (dataset, title, chart, columns, groups, sort) in enumerate(specs, 1):
        config = {"dataset": dataset, "chart": chart,
                  "columns": [{"field": field, "title": label, "width": 160, "visible": True, "aggregate": aggregate} for field, label, aggregate in columns],
                  "filters": [], "groupBy": groups, "sortField": sort, "sortDirection": "DESC"}
        if dataset == "equipmentTasks":
            config["filters"] = [{"field": "due_time", "operator": "ge", "value": (today - timedelta(days=45)).isoformat(sep=" ")}]
        if index == 1:
            config["canvas"] = {"schemaVersion": 1, "paper": "A4", "orientation": "landscape", "background": "#ffffff",
                "blocks": [
                    {"id": "title", "type": "text", "x": 32, "y": 24, "width": 1050, "height": 48, "title": "", "text": "合成测试生产工单报表", "style": {"fontSize": 24, "color": "#183247", "background": "#ffffff", "align": "left", "bold": True, "border": False}, "binding": {"dimension": None, "metrics": [], "summary": "SUM", "rowLimit": 10}},
                    {"id": "production", "type": "bar", "x": 32, "y": 96, "width": 1050, "height": 380, "title": "各状态计划与产出", "text": "", "style": {"fontSize": 12, "color": "#183247", "background": "#ffffff", "align": "left", "bold": False, "border": True}, "binding": {"dimension": "status", "metrics": ["quantity", "quantity_produced"], "summary": "SUM", "rowLimit": 20}},
                    {"id": "notes", "type": "text", "x": 32, "y": 500, "width": 1050, "height": 64, "title": "", "text": remark, "style": {"fontSize": 12, "color": "#183247", "background": "#ffffff", "align": "left", "bold": False, "border": False}, "binding": {"dimension": None, "metrics": [], "summary": "SUM", "rowLimit": 10}},
                ]}
        report = add("mes_report_definition", latest_version=1, published_version=None)
        add("mes_report_version", report_id=report, version=1, name=title, config_json=_json(config))
        report_ids.append(report)
    ctx.ids["reports"] = report_ids


def _seed_knowledge(ctx, add, admin, remark):
    bases = [add("mes_assistant_knowledge_base", name=name, description=remark + "；全部文档默认草稿，供上传、预览、发布与检索流程验证。", creator=admin)
             for name in ["测试制造操作规范", "测试质量与追溯", "测试设备与仓储"]]
    docs = [
        (0, "生产报工与工序流转", [("适用范围", "本文是本地合成测试规范，仅描述测试流程。操作前确认工单已确认、工序已启用、工作站与工序匹配。"), ("报工数量", "报工数量必须为正数，合格数量、不合格数量与待检数量之和应等于报工数量。不得把草稿当作已完成产量。"), ("来源与流转", "需要上工序来源的报工，应选择有剩余可用量的真实来源报工。检查批次、工单、工序及剩余数量，不允许重复消耗或循环来源。")]),
        (0, "排班与工作站资源", [("人员资源", "岗位人员应存在、启用并属于配置岗位。工作站人员绑定使用实际人员编号；按班组排班核查生产任务时段的可用人数。"), ("设备工装", "每台设备只能归属一个工作站。维修设备、报废工装和零可用工具不能被当作可用能力。使用工装前核对类型、数量和维护周期。"), ("跨日班次", "夜班例如22:00至次日06:00，结束日期必须顺延到下一天。确认排班之前检查日期范围、班组数量、轮班方式和倒班天数。")]),
        (1, "质量隔离与放行", [("检验与判定", "依据检验模板记录抽检数量及结果。不合格数量、缺陷明细和判定应相互一致，待检数量不应冒充已合格数量。"), ("隔离台账", "不合格物料进入隔离流程时，核查来源批次、库存、库位和隔离数量；可用库存不得包含冻结或隔离数量。"), ("处置与复核", "返工、让步、退货及报废应保留明确处置依据。只有完成授权复核及有效过账后，才更新库存和业务单据状态。")]),
        (1, "序列号与批次追溯", [("追溯起点", "选择测试产品的批次或序列号，核查生产报工、原料批次和检验记录。序列号唯一，不应同时属于不同成品。"), ("上下游证据", "追溯关系必须由真实单据来源和数量关系构成。源报工分配量不得超过可用量，祖先链路不得形成循环。"), ("异常复核", "发现客户退货来源不一致、批次缺失或来源用量超限时停止继续发货，保留差异记录并通过对应异常流程处理。")]),
        (2, "设备点检保养与维修", [("任务认领", "点检保养任务按方案、设备和到期时间唯一生成。仅认领者可以提交任务，完成时须回答所有任务项目。"), ("异常结果", "项目结果分正常与异常。异常必须填写明确描述；提交后生成对应点检或保养记录，并通过唯一来源自动报修。"), ("维修验收", "异常设备保持维护状态。维修完成后进入待验收；验收失败退回维修。验收通过且没有其他未完成维修单，设备才恢复停机可用状态。")]),
        (2, "库存过账与仓库作业", [("库存口径", "库存台账应与已过账库存事务净额一致，草稿单据不改变库存。批次、物料、仓库、库区、库位须使用同一套有效主数据。"), ("收发作业", "入库、领料、退料、移库和盘点均需核查数量与单据状态。移库的出入数量相等，禁止负库存及重复过账。"), ("退货成本", "销售退货关联原发货行和原出库成本，累计退货数量不得超过已发货数量。没有有效来源时不得伪造历史成本。")]),
    ]
    document_ids = []
    for index, (base_index, title, sections) in enumerate(docs, 1):
        content = "".join(f"# {heading}\n{text}\n\n" for heading, text in sections)
        raw = content.encode("utf-8")
        document = add("mes_assistant_document", knowledge_base_id=bases[base_index], title=title,
            file_name=f"test-suite-{index:02}.md", file_type="md", file_size=len(raw), file_sha256=_sha(raw),
            original_bytes=raw, content_text=content, chunk_count=len(sections), version=1, status="DRAFT", creator=admin)
        cursor = 0
        for chunk_index, (heading, text) in enumerate(sections):
            start = content.index(text, cursor)
            end = start + len(text)
            add("mes_assistant_chunk", document_id=document, chunk_index=chunk_index, heading=heading,
                page_number=None, start_offset=start, end_offset=end, content_text=text, content_sha256=_sha(text.encode("utf-8")))
            cursor = end
        document_ids.append(document)
    ctx.ids.update({"knowledge_bases": bases, "knowledge_documents": document_ids})


def validation(ctx):
    """Return named queries; every query counts invalid rows and must return 0."""
    checks = []

    def check(name, sql):
        checks.append((name, sql))

    def reference(table, column, target, optional=False):
        nullable = f"s.{column} IS NOT NULL AND " if optional else ""
        check(f"support.{table}.{column}", f"SELECT COUNT(*) FROM {table} s LEFT JOIN {target} p ON p.id=s.{column} AND p.tenant_id=s.tenant_id AND p.deleted=0 WHERE s.id BETWEEN 980001 AND 989999 AND s.deleted=0 AND {nullable}p.id IS NULL")

    for table, column, target, optional in [
        ("mes_dv_machinery", "machinery_type_id", "mes_dv_machinery_type", False),
        ("mes_dv_machinery", "workshop_id", "mes_md_workshop", False),
        ("mes_dv_check_plan_machinery", "plan_id", "mes_dv_check_plan", False),
        ("mes_dv_check_plan_machinery", "machinery_id", "mes_dv_machinery", False),
        ("mes_dv_check_plan_subject", "plan_id", "mes_dv_check_plan", False),
        ("mes_dv_check_plan_subject", "subject_id", "mes_dv_subject", False),
        ("mes_dv_task", "plan_id", "mes_dv_check_plan", False),
        ("mes_dv_task", "machinery_id", "mes_dv_machinery", False),
        ("mes_dv_task", "user_id", "system_users", True),
        ("mes_dv_task", "repair_id", "mes_dv_repair", True),
        ("mes_dv_task_line", "task_id", "mes_dv_task", False),
        ("mes_dv_task_line", "subject_id", "mes_dv_subject", False),
        ("mes_dv_check_record", "plan_id", "mes_dv_check_plan", False),
        ("mes_dv_check_record", "machinery_id", "mes_dv_machinery", False),
        ("mes_dv_check_record", "user_id", "system_users", False),
        ("mes_dv_mainten_record", "plan_id", "mes_dv_check_plan", False),
        ("mes_dv_mainten_record", "machinery_id", "mes_dv_machinery", False),
        ("mes_dv_mainten_record", "user_id", "system_users", False),
        ("mes_dv_check_record_line", "record_id", "mes_dv_check_record", False),
        ("mes_dv_check_record_line", "subject_id", "mes_dv_subject", False),
        ("mes_dv_mainten_record_line", "record_id", "mes_dv_mainten_record", False),
        ("mes_dv_mainten_record_line", "subject_id", "mes_dv_subject", False),
        ("mes_dv_repair", "machinery_id", "mes_dv_machinery", False),
        ("mes_dv_repair", "accepted_user_id", "system_users", True),
        ("mes_dv_repair", "confirm_user_id", "system_users", True),
        ("mes_dv_repair_line", "repair_id", "mes_dv_repair", False),
        ("mes_dv_repair_line", "subject_id", "mes_dv_subject", False),
        ("mes_tm_tool", "tool_type_id", "mes_tm_tool_type", False),
        ("mes_md_workstation_machine", "workstation_id", "mes_md_workstation", False),
        ("mes_md_workstation_machine", "machinery_id", "mes_dv_machinery", False),
        ("mes_md_workstation_tool", "workstation_id", "mes_md_workstation", False),
        ("mes_md_workstation_tool", "tool_type_id", "mes_tm_tool_type", False),
        ("mes_md_workstation_worker", "workstation_id", "mes_md_workstation", False),
        ("mes_md_workstation_worker", "post_id", "system_post", False),
        ("mes_cal_team_member", "team_id", "mes_cal_team", False),
        ("mes_cal_team_member", "user_id", "system_users", False),
        ("mes_cal_plan_team", "plan_id", "mes_cal_plan", False),
        ("mes_cal_plan_team", "team_id", "mes_cal_team", False),
        ("mes_cal_plan_shift", "plan_id", "mes_cal_plan", False),
        ("mes_cal_team_shift", "plan_id", "mes_cal_plan", False),
        ("mes_cal_team_shift", "team_id", "mes_cal_team", False),
        ("mes_cal_team_shift", "shift_id", "mes_cal_plan_shift", False),
        ("mes_pro_andon_config", "handler_user_id", "system_users", False),
        ("mes_pro_andon_record", "config_id", "mes_pro_andon_config", False),
        ("mes_pro_andon_record", "workstation_id", "mes_md_workstation", False),
        ("mes_pro_andon_record", "user_id", "system_users", False),
        ("mes_pro_andon_record", "handler_user_id", "system_users", True),
        ("mes_pro_andon_record", "process_id", "mes_pro_process", True),
        ("mes_report_version", "report_id", "mes_report_definition", False),
        ("mes_assistant_document", "knowledge_base_id", "mes_assistant_knowledge_base", False),
        ("mes_assistant_document", "creator", "system_users", False),
        ("mes_assistant_chunk", "document_id", "mes_assistant_document", False),
    ]:
        reference(table, column, target, optional)
    check("support.dv_task_state", """SELECT COUNT(*) FROM mes_dv_task WHERE id BETWEEN 980001 AND 989999 AND
        (status NOT IN(0,1,2) OR (status=0 AND (user_id IS NOT NULL OR claimed_time IS NOT NULL OR completed_time IS NOT NULL OR record_id IS NOT NULL))
        OR (status=1 AND (user_id IS NULL OR claimed_time IS NULL OR completed_time IS NOT NULL OR record_id IS NOT NULL))
        OR (status=2 AND (user_id IS NULL OR claimed_time IS NULL OR completed_time IS NULL OR record_id IS NULL OR completed_time<claimed_time)))""")
    for kind, table, finished in [(1, "mes_dv_check_record", 20), (2, "mes_dv_mainten_record", 4)]:
        check(f"support.dv_task_record_{kind}", f"""SELECT COUNT(*) FROM mes_dv_task t LEFT JOIN {table} r ON r.id=t.record_id AND r.tenant_id=t.tenant_id AND r.deleted=0
            WHERE t.id BETWEEN 980001 AND 989999 AND t.type={kind} AND t.status=2
            AND (r.id IS NULL OR r.plan_id<>t.plan_id OR r.machinery_id<>t.machinery_id OR r.status<>{finished} OR r.user_id<>t.user_id)""")
    check("support.dv_task_snapshots", """SELECT COUNT(*) FROM mes_dv_task t JOIN mes_dv_check_plan p ON p.id=t.plan_id JOIN mes_dv_machinery m ON m.id=t.machinery_id
        WHERE t.id BETWEEN 980001 AND 989999 AND (t.type<>p.type OR t.plan_name<>p.name OR t.machinery_name<>m.name OR t.due_time<p.start_date OR t.due_time>p.end_date
        OR NOT EXISTS(SELECT 1 FROM mes_dv_task_line l WHERE l.task_id=t.id AND l.deleted=0))""")
    check("support.dv_task_results", """SELECT COUNT(*) FROM mes_dv_task_line l JOIN mes_dv_task t ON t.id=l.task_id WHERE l.id BETWEEN 980001 AND 989999
        AND ((t.status=2 AND (l.result IS NULL OR l.result NOT IN(1,2))) OR (t.status<>2 AND l.result IS NOT NULL)
        OR (l.result=2 AND (l.description IS NULL OR TRIM(l.description)='')))""")
    check("support.dv_completed_record_time", """SELECT COUNT(*) FROM mes_dv_task t
        LEFT JOIN mes_dv_check_record c ON t.type=1 AND c.id=t.record_id AND c.tenant_id=t.tenant_id
        LEFT JOIN mes_dv_mainten_record m ON t.type=2 AND m.id=t.record_id AND m.tenant_id=t.tenant_id
        WHERE t.id BETWEEN 980001 AND 989999 AND t.status=2 AND
        ((t.type=1 AND NOT(t.completed_time<=>c.check_time)) OR (t.type=2 AND NOT(t.completed_time<=>m.mainten_time)))""")
    check("support.dv_cursor_after_generated_tasks", """SELECT COUNT(*) FROM mes_dv_check_plan p
        WHERE p.id BETWEEN 980001 AND 989999 AND p.status=1 AND (p.next_task_time IS NULL
        OR p.next_task_time<=(SELECT MAX(t.due_time) FROM mes_dv_task t WHERE t.plan_id=p.id AND t.tenant_id=p.tenant_id AND t.deleted=0))""")
    check("support.dv_record_line_results", """SELECT COUNT(*) FROM mes_dv_task_line l JOIN mes_dv_task t ON t.id=l.task_id
        LEFT JOIN mes_dv_check_record_line c ON t.type=1 AND c.record_id=t.record_id AND c.subject_id=l.subject_id AND c.tenant_id=t.tenant_id AND c.deleted=0
        LEFT JOIN mes_dv_mainten_record_line m ON t.type=2 AND m.record_id=t.record_id AND m.subject_id=l.subject_id AND m.tenant_id=t.tenant_id AND m.deleted=0
        WHERE t.id BETWEEN 980001 AND 989999 AND t.status=2 AND ((t.type=1 AND (c.id IS NULL OR c.check_status<>l.result OR NOT(c.check_result<=>l.description)))
        OR (t.type=2 AND (m.id IS NULL OR m.status<>l.result OR NOT(m.result<=>l.description))))""")
    check("support.dv_abnormal_repair", """SELECT COUNT(*) FROM mes_dv_task t LEFT JOIN mes_dv_repair r ON r.id=t.repair_id AND r.tenant_id=t.tenant_id AND r.deleted=0
        WHERE t.id BETWEEN 980001 AND 989999 AND t.status=2 AND EXISTS(SELECT 1 FROM mes_dv_task_line l WHERE l.task_id=t.id AND l.result=2 AND l.deleted=0)
        AND (r.id IS NULL OR r.machinery_id<>t.machinery_id OR r.source_doc_id<>t.record_id OR r.source_doc_type<>IF(t.type=1,101,102))""")
    check("support.dv_open_repairs_hold_machine", """SELECT COUNT(*) FROM mes_dv_repair r JOIN mes_dv_machinery m ON m.id=r.machinery_id WHERE r.id BETWEEN 980001 AND 989999
        AND r.source_doc_type IN(101,102) AND r.status<>4 AND m.status<>3""")
    check("support.dv_repair_state", """SELECT COUNT(*) FROM mes_dv_repair WHERE id BETWEEN 980001 AND 989999 AND
        (status NOT IN(0,1,2,4) OR (status IN(1,2,4) AND accepted_user_id IS NULL) OR (status IN(2,4) AND finish_date IS NULL)
        OR (status=4 AND (result<>1 OR confirm_user_id IS NULL OR confirm_date IS NULL)) OR (finish_date IS NOT NULL AND finish_date<require_date)
        OR (confirm_date IS NOT NULL AND (finish_date IS NULL OR confirm_date<finish_date)))""")
    check("support.dv_latest_check", """SELECT COUNT(*) FROM mes_dv_machinery m WHERE m.id BETWEEN 980001 AND 980012
        AND NOT(m.last_check_time<=>(SELECT MAX(r.check_time) FROM mes_dv_check_record r WHERE r.machinery_id=m.id AND r.status=20 AND r.deleted=0))""")
    check("support.dv_latest_maintenance", """SELECT COUNT(*) FROM mes_dv_machinery m WHERE m.id BETWEEN 980001 AND 980012
        AND NOT(m.last_mainten_time<=>(SELECT MAX(r.mainten_time) FROM mes_dv_mainten_record r WHERE r.machinery_id=m.id AND r.status=4 AND r.deleted=0))""")
    check("support.tool_quantity", """SELECT COUNT(*) FROM mes_tm_tool WHERE id BETWEEN 980001 AND 989999
        AND (quantity<1 OR available_quantity<0 OR available_quantity>quantity OR status NOT IN(1,2,3,4) OR (status IN(3,4) AND available_quantity<>0)
        OR mainten_type NOT IN(1,2) OR (mainten_type=1 AND next_mainten_date IS NULL) OR (mainten_type=2 AND (next_mainten_period IS NULL OR next_mainten_period<=0)))""")
    check("support.workstation_tool_binding", """SELECT COUNT(*) FROM mes_md_workstation_tool w
        JOIN JSON_TABLE(CONCAT('[',COALESCE(NULLIF(w.tool_ids,''),'0'),']'),'$[*]' COLUMNS(bound_id BIGINT PATH '$')) j
        LEFT JOIN mes_tm_tool t ON t.id=j.bound_id AND t.tenant_id=w.tenant_id AND t.deleted=0
        WHERE w.id BETWEEN 980001 AND 989999 AND (t.id IS NULL OR t.tool_type_id<>w.tool_type_id OR w.quantity<1)""")
    check("support.workstation_worker_binding", """SELECT COUNT(*) FROM mes_md_workstation_worker w
        JOIN JSON_TABLE(CONCAT('[',COALESCE(NULLIF(w.user_ids,''),'0'),']'),'$[*]' COLUMNS(bound_id BIGINT PATH '$')) j
        LEFT JOIN system_users u ON u.id=j.bound_id AND u.tenant_id=w.tenant_id AND u.deleted=0
        WHERE w.id BETWEEN 980001 AND 989999 AND (u.id IS NULL OR u.status<>0 OR NOT JSON_CONTAINS(u.post_ids,CAST(w.post_id AS JSON)) OR w.quantity<1)""")
    check("support.workstation_machine_unique", "SELECT COUNT(*) FROM (SELECT machinery_id FROM mes_md_workstation_machine WHERE deleted=0 GROUP BY tenant_id,machinery_id HAVING COUNT(*)>1) x")
    check("support.calendar_shift_membership", """SELECT COUNT(*) FROM mes_cal_team_shift s JOIN mes_cal_plan p ON p.id=s.plan_id JOIN mes_cal_plan_shift h ON h.id=s.shift_id
        WHERE s.id BETWEEN 980001 AND 989999 AND (h.plan_id<>p.id OR DATE(s.day)<DATE(p.start_date) OR DATE(s.day)>DATE(p.end_date)
        OR NOT EXISTS(SELECT 1 FROM mes_cal_plan_team pt WHERE pt.plan_id=p.id AND pt.team_id=s.team_id AND pt.deleted=0))""")
    check("support.calendar_plan_types", """SELECT COUNT(*) FROM mes_cal_plan WHERE id BETWEEN 980001 AND 989999 AND
        (start_date>end_date OR shift_type NOT IN(1,2,3) OR status NOT IN(0,1) OR (shift_type=1 AND (shift_method IS NOT NULL OR shift_count IS NOT NULL))
        OR (shift_type>1 AND (shift_method IS NULL OR shift_method NOT IN(1,2,3,4))) OR (shift_method=4 AND (shift_count IS NULL OR shift_count<=0)))""")
    check("support.calendar_complete_days", """SELECT COUNT(*) FROM mes_cal_plan p WHERE p.id BETWEEN 980001 AND 989999 AND p.status=1
        AND (SELECT COUNT(*) FROM mes_cal_team_shift s WHERE s.plan_id=p.id AND s.deleted=0)<>(DATEDIFF(p.end_date,p.start_date)+1)*p.shift_type""")
    check("support.calendar_draft_has_no_assignments", "SELECT COUNT(*) FROM mes_cal_team_shift s JOIN mes_cal_plan p ON p.id=s.plan_id WHERE p.id BETWEEN 980001 AND 989999 AND p.status=0")
    check("support.calendar_user_overlap", """WITH spans AS (
        SELECT s.id,tm.user_id,TIMESTAMP(DATE(s.day),h.start_time) AS begins,
        DATE_ADD(TIMESTAMP(DATE(s.day),h.end_time),INTERVAL IF(h.end_time<=h.start_time,1,0) DAY) AS finishes
        FROM mes_cal_team_shift s JOIN mes_cal_plan_shift h ON h.id=s.shift_id AND h.tenant_id=s.tenant_id AND h.deleted=0
        JOIN mes_cal_team_member tm ON tm.team_id=s.team_id AND tm.tenant_id=s.tenant_id AND tm.deleted=0
        WHERE s.id BETWEEN 980001 AND 989999 AND s.deleted=0)
        SELECT COUNT(*) FROM spans a JOIN spans b ON a.user_id=b.user_id AND a.id<b.id AND a.begins<b.finishes AND a.finishes>b.begins""")
    check("support.andon_disposition", """SELECT COUNT(*) FROM mes_pro_andon_record WHERE id BETWEEN 980001 AND 989999 AND
        (level NOT IN(1,2,3) OR status NOT IN(0,1) OR (status=0 AND (handle_time IS NOT NULL OR handler_user_id IS NOT NULL))
        OR (status=1 AND (handle_time IS NULL OR handler_user_id IS NULL OR handle_time<create_time)))""")
    check("support.report_version", """SELECT COUNT(*) FROM mes_report_definition d LEFT JOIN mes_report_version v ON v.report_id=d.id AND v.version=d.latest_version AND v.tenant_id=d.tenant_id AND v.deleted=0
        WHERE d.id BETWEEN 980001 AND 989999 AND (v.id IS NULL OR NOT JSON_VALID(v.config_json)
        OR (d.published_version IS NOT NULL AND (d.published_version>d.latest_version OR NOT EXISTS(
        SELECT 1 FROM mes_report_version p WHERE p.report_id=d.id AND p.version=d.published_version AND p.tenant_id=d.tenant_id AND p.deleted=0))))""")
    check("support.knowledge_document_integrity", """SELECT COUNT(*) FROM mes_assistant_document d WHERE d.id BETWEEN 980001 AND 989999 AND
        (d.status<>'DRAFT' OR d.file_size<>OCTET_LENGTH(d.original_bytes) OR d.file_sha256<>SHA2(d.original_bytes,256)
        OR CAST(d.original_bytes AS CHAR CHARACTER SET utf8mb4)<>d.content_text
        OR d.chunk_count<>(SELECT COUNT(*) FROM mes_assistant_chunk c WHERE c.document_id=d.id AND c.tenant_id=d.tenant_id AND c.deleted=0))""")
    check("support.knowledge_chunk_integrity", """SELECT COUNT(*) FROM mes_assistant_chunk c JOIN mes_assistant_document d ON d.id=c.document_id WHERE c.id BETWEEN 980001 AND 989999
        AND (c.start_offset<0 OR c.end_offset<=c.start_offset OR c.end_offset>CHAR_LENGTH(d.content_text)
        OR SUBSTRING(d.content_text,c.start_offset+1,c.end_offset-c.start_offset)<>c.content_text
        OR c.content_sha256<>SHA2(c.content_text,256))""")
    check("support.knowledge_chunk_indices", """SELECT COUNT(*) FROM mes_assistant_document d
        WHERE d.id BETWEEN 980001 AND 989999 AND (d.chunk_count<1
        OR (SELECT MIN(c.chunk_index) FROM mes_assistant_chunk c WHERE c.document_id=d.id AND c.deleted=0)<>0
        OR (SELECT MAX(c.chunk_index) FROM mes_assistant_chunk c WHERE c.document_id=d.id AND c.deleted=0)<>d.chunk_count-1)""")
    return checks
