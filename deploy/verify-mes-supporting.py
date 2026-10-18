#!/usr/bin/env python3
"""Verify three-level packing and a rotating two-team calendar via local HTTP.

Creates prefixed local acceptance records. Reuse --run-id to read/resume them.
Saves a token-free report to .runtime/mes-supporting-acceptance.json by default.
"""

import argparse
from collections import defaultdict
from datetime import datetime, timedelta, timezone
import json
import os
from pathlib import Path
import re
import runpy
import sys


helpers = runpy.run_path(str(Path(__file__).with_name("verify-mes-flow.py")))
MesApi, require, dec = (helpers[name] for name in ("MesApi", "require", "dec"))
SHANGHAI = timezone(timedelta(hours=8))


def rejected(api, path, expected, label, report, **params):
    try:
        api.put(path, **params)
    except RuntimeError as error:
        message = str(error)
        require(f"code={expected}," in message, f"Unexpected rejection for {label}: {message}")
        report["checks"].append({"check": label, "passed": True, "errorCode": expected, "message": message})
        return
    raise RuntimeError(f"{label} unexpectedly succeeded")


def ensure_document(api, path, code, payload):
    row = api.exact_code(path, code)
    if row is None:
        row_id = api.post(path + "/create", {"code": code, **payload})
        row = api.get(path + "/get", id=row_id)
    return row


def packing(api, run_id, when, report):
    order = ensure_document(api, "/mes/pro/work-order", "VERIFY-PACK-WO-" + run_id, {
        "name": "验收装箱引用工单", "type": 1, "orderSourceType": 2,
        "productId": 900103, "quantity": 5, "quantityProduced": 0, "quantityScheduled": 0,
        "quantityChanged": 0, "requestDate": (when + timedelta(days=7)).isoformat(timespec="seconds"),
        "remark": "Local packing acceptance fixture",
    })
    if order["status"] == 0:
        api.put("/mes/pro/work-order/confirm", id=order["id"])
    require(api.get("/mes/pro/work-order/get", id=order["id"])["status"] == 1,
            "Packaging fixture work order must stay confirmed")
    path = "/mes/wm/package"
    boxes = [ensure_document(api, path, "VERIFY-PK-" + level + "-" + run_id, {
        "packageDate": when.isoformat(timespec="seconds"), "clientId": 900001,
        "inspectorUserId": 1, "remark": "验收三层装箱：" + level,
    }) for level in ("OUTER", "MIDDLE", "INNER")]
    outer, middle, inner = (box["id"] for box in boxes)
    report["packing"] = {"workOrderId": order["id"], "outerId": outer, "middleId": middle, "innerId": inner}
    contents = api.page(path + "-line", packageId=inner)
    if not contents:
        api.post(path + "-line/create", {"packageId": inner, "materialStockId": 900003,
            "itemId": 900103, "quantity": 5, "workOrderId": order["id"], "remark": "验收内箱5件"})
    require(sum(dec(row["quantity"]) for row in api.page(path + "-line", packageId=inner)) == 5,
            "Inner package quantity mismatch")
    if api.get(path + "/get", id=inner)["status"] == 0:
        api.put(path + "/finish", id=inner)
    if api.get(path + "/get", id=inner)["parentId"] == 0:
        api.put(path + "/add-child-package", parentId=middle, childId=inner)
    require(api.get(path + "/get", id=inner)["parentId"] == middle, "Inner package parent mismatch")
    if api.get(path + "/get", id=middle)["status"] == 0:
        api.put(path + "/finish", id=middle)
    if api.get(path + "/get", id=middle)["parentId"] == 0:
        api.put(path + "/add-child-package", parentId=outer, childId=middle)
    require(api.get(path + "/get", id=middle)["parentId"] == outer, "Middle package parent mismatch")
    children = api.page(path, parentId=outer)
    require({row["id"] for row in children} == {middle}, "Direct child package query mismatch")
    descendants = api.page(path + "-line", packageId=outer)
    require(len(descendants) == 1 and descendants[0]["packageId"] == inner
            and dec(descendants[0]["quantity"]) == 5, "Outer package did not include inner package contents")
    report["checks"].append({"check": "three_level_packing_and_descendant_contents", "passed": True,
                              "levels": 3, "recursiveContentQuantity": 5})
    if api.get(path + "/get", id=outer)["status"] == 0:
        rejected(api, path + "/add-child-package", 1040740004, "package_self_parent_rejected", report,
                 parentId=outer, childId=outer)
        rejected(api, path + "/add-child-package", 1040740005, "package_second_parent_rejected", report,
                 parentId=outer, childId=inner)
        api.put(path + "/finish", id=outer)
    # A completed inner package cannot become a mutable parent. This prevents an
    # ancestor back-link before the dedicated ancestor traversal guard is reached.
    rejected(api, path + "/add-child-package", 1040740002, "ancestor_back_link_rejected_by_state_guard", report,
             parentId=inner, childId=outer)
    current = [api.get(path + "/get", id=box_id) for box_id in (outer, middle, inner)]
    require([box["parentId"] for box in current] == [0, outer, middle], "Rejected links changed package hierarchy")
    require(all(box["status"] == 4 for box in current), "Packages did not finish")


def day_string(value):
    if isinstance(value, (int, float)):
        return datetime.fromtimestamp(value / 1000, SHANGHAI).date().isoformat()
    return str(value)[:10]


def calendar(api, run_id, when, report):
    teams = [ensure_document(api, "/mes/cal/team", "VERIFY-TEAM-" + name + "-" + run_id,
        {"name": "验收轮班" + name + "组", "calendarType": 1, "remark": "Local calendar acceptance fixture"})
        for name in ("A", "B")]
    start = (when + timedelta(days=1)).replace(hour=0, minute=0, second=0)
    plan_path = "/mes/cal/plan"
    plan = ensure_document(api, plan_path, "VERIFY-CAL-" + run_id, {
        "name": "验收两班倒每日轮换", "calendarType": 1,
        "startDate": start.isoformat(timespec="seconds"),
        "endDate": (start + timedelta(days=2)).isoformat(timespec="seconds"),
        "shiftType": 2, "shiftMethod": 4, "shiftCount": 1, "remark": "Local three-day rotating schedule",
    })
    plan_id = plan["id"]
    shifts = api.get("/mes/cal/plan-shift/list-by-plan", planId=plan_id)
    require(len(shifts) == 2, "Two-shift plan did not generate two default shifts")
    require({(shift["startTime"], shift["endTime"]) for shift in shifts} == {("08:00", "20:00"), ("20:00", "08:00")},
            "Default day/night shift times mismatch")
    if plan["status"] == 0:
        associated = api.get("/mes/cal/plan-team/list-by-plan", planId=plan_id)
        for team in teams:
            if not any(row["teamId"] == team["id"] for row in associated):
                api.post("/mes/cal/plan-team/create", {"planId": plan_id, "teamId": team["id"]})
        api.put(plan_path + "/confirm", id=plan_id)
    require(api.get(plan_path + "/get", id=plan_id)["status"] == 1, "Calendar plan did not confirm")
    records = api.get("/mes/cal/team-shift/list", planId=plan_id)
    require(len(records) == 6, f"Expected six calendar records, got {len(records)}")
    by_day = defaultdict(dict)
    for row in records:
        by_day[day_string(row["day"])][row["teamId"]] = row["shiftId"]
    dates = sorted(by_day)
    require(len(dates) == 3, "Expected three schedule dates")
    expected_teams = {team["id"] for team in teams}
    expected_shifts = {shift["id"] for shift in shifts}
    for assignments in by_day.values():
        require(set(assignments) == expected_teams and set(assignments.values()) == expected_shifts,
                "Each date must have both teams and both shifts")
    for team_id in expected_teams:
        require(by_day[dates[0]][team_id] != by_day[dates[1]][team_id]
                and by_day[dates[0]][team_id] == by_day[dates[2]][team_id], "Teams did not rotate daily")
    report["checks"].append({"check": "two_team_daily_rotation_calendar", "passed": True,
                              "days": 3, "records": 6, "defaultNightShiftCrossesMidnight": True})
    rejected(api, plan_path + "/confirm", 1040202002, "calendar_reconfirmation_rejected", report, id=plan_id)
    require(len(api.get("/mes/cal/team-shift/list", planId=plan_id)) == 6,
            "Repeated plan confirmation duplicated calendar records")
    report["calendar"] = {"planId": plan_id, "teamIds": sorted(expected_teams), "dates": dates,
        "records": [{"date": day_string(row["day"]), "teamId": row["teamId"],
                     "team": row["teamName"], "shift": row["shiftName"]} for row in records]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:48080/admin-api")
    parser.add_argument("--password", default=os.environ.get("YUDAO_LOCAL_ADMIN_PASSWORD", "MesLocal@2026"))
    parser.add_argument("--run-id", default=datetime.now(SHANGHAI).strftime("%Y%m%d%H%M%S"))
    parser.add_argument("--output", default=str(Path(__file__).resolve().parent.parent / ".runtime" / "mes-supporting-acceptance.json"))
    args = parser.parse_args()
    require(re.fullmatch(r"[A-Za-z0-9_-]{1,40}", args.run_id), "Invalid --run-id")
    report = {"runId": args.run_id, "checks": []}
    try:
        api = MesApi(args.base_url, 1)
        login = api.post("/system/auth/login", {"username": "admin", "password": args.password})
        api.headers["Authorization"] = "Bearer " + login["accessToken"]
        when = datetime.now(SHANGHAI).replace(tzinfo=None, microsecond=0)
        packing(api, args.run_id, when, report)
        calendar(api, args.run_id, when, report)
        report["httpCalls"] = api.calls
        report["result"] = "passed"
    except Exception as error:
        report["result"] = "failed"
        report["error"] = str(error)
    result = json.dumps(report, ensure_ascii=False, indent=2, default=str)
    Path(args.output).write_text(result + "\n", encoding="utf-8")
    print(result)
    return 0 if report["result"] == "passed" else 1


if __name__ == "__main__":
    sys.exit(main())
