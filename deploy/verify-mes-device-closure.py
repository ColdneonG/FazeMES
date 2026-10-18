#!/usr/bin/env python3
"""Real HTTP device-closure acceptance; creates run-labelled fixtures, never uses SQL.

Run only after migrations and the new backend are deployed. Credentials are read
from .runtime/secrets.local.json. Reports contain identifiers/assertions only.
A successful run-id can be repeated to verify its retained fixtures read-only.
"""
import argparse
from datetime import datetime, timedelta
import json
from pathlib import Path
import re
import sys
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[1]


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


class Api:
    def __init__(self, base, tenant):
        self.base = base.rstrip("/")
        self.headers = {"Content-Type": "application/json", "tenant-id": str(tenant)}
        self.calls = 0

    def call(self, method, path, body=None, **params):
        url = self.base + path + ("?" + urlencode(params) if params else "")
        payload = None if body is None else json.dumps(body).encode("utf-8")
        self.calls += 1
        try:
            with urlopen(Request(url, data=payload, headers=self.headers, method=method), timeout=45) as response:
                result = json.load(response)
        except HTTPError as error:
            # Do not copy raw response bodies: auth/debug error pages can contain secrets.
            raise RuntimeError(f"{method} {path}: HTTP {error.code}") from None
        except URLError:
            raise RuntimeError(f"{method} {path}: backend unavailable") from None
        require(result.get("code") == 0, f"{method} {path}: code={result.get('code')}")
        return result.get("data")

    def get(self, path, **params):
        return self.call("GET", path, **params)

    def post(self, path, body):
        return self.call("POST", path, body)

    def put(self, path, body=None, **params):
        return self.call("PUT", path, body, **params)

    def rows(self, path, **params):
        page = self.get(path + "/page", pageNo=1, pageSize=100, **params)
        require(page["total"] <= 100, "Fixture query exceeded 100 rows")
        return page["list"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", default="http://127.0.0.1:48080/admin-api")
    parser.add_argument("--tenant", type=int, default=1)
    parser.add_argument("--workshop-id", type=int, default=900001)
    parser.add_argument("--run-id", default="device-" + datetime.now().strftime("%Y%m%d-%H%M%S"))
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    require(re.fullmatch(r"[A-Za-z0-9_-]{1,40}", args.run_id), "run-id must use 1-40 safe characters")
    output = args.output or ROOT / ".runtime" / ("mes-device-closure-" + args.run_id + ".json")
    prior = json.loads(output.read_text(encoding="utf-8")) if output.exists() else None
    require(prior is None or prior.get("result") == "passed", "Previous partial run retained; use a fresh run-id to avoid repeating writes")
    secrets = json.loads((ROOT / ".runtime/secrets.local.json").read_text(encoding="utf-8-sig"))
    api = Api(args.base, args.tenant)
    login = api.post("/system/auth/login", {"username": secrets["AdminUsername"], "password": secrets["AdminPassword"]})
    api.headers["Authorization"] = "Bearer " + login["accessToken"]
    report = {"runId": args.run_id, "checks": [], "retainedFixtures": True, "ids": {}, "result": "running"}

    def save():
        report["httpCalls"] = api.calls
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    def check(name, condition, **details):
        require(condition, name)
        report["checks"].append({"check": name, "passed": True, **details})
        save()

    try:
        if prior:
            report["ids"] = prior["ids"]
            report["resumedReadOnly"] = True
            ids = report["ids"]
            check("retained_machine_final_state", api.get("/mes/dv/machinery/get", id=ids["machineryId"])["status"] == 1)
            check("retained_repair_passed", api.get("/mes/dv/repair/get", id=ids["repairId"])["result"] == 1)
            check("retained_plan_stopped", api.get("/mes/dv/check-plan/get", id=ids["planId"])["status"] == 0)
            check("retained_occurrence_count_unchanged", len(api.rows("/mes/dv/task", planId=ids["planId"])) == 3)
        else:
            now = datetime.now().replace(microsecond=0)
            iso = lambda value: value.isoformat(timespec="seconds")
            prefix = "DVA-" + args.run_id
            ids = report["ids"]
            require(api.get("/mes/md-workshop/get", id=args.workshop_id) is not None, "Seed workshop missing")
            ids["reusedWorkshopId"] = args.workshop_id
            ids["machineryTypeId"] = api.post("/mes/dv/machinery-type/create", {
                "code": prefix + "-TYPE", "name": "设备闭环验收类型 " + args.run_id,
                "parentId": 0, "status": 0, "sort": 99, "remark": args.run_id,
            })
            save()
            ids["machineryId"] = api.post("/mes/dv/machinery/create", {
                "code": prefix + "-EQ", "name": "设备闭环验收 " + args.run_id,
                "brand": "示例品牌", "specification": "示例规格", "machineryTypeId": ids["machineryTypeId"],
                "workshopId": args.workshop_id, "status": 1, "remark": args.run_id,
            })
            save()
            ids["subjectId"] = api.post("/mes/dv/subject/create", {
                "code": prefix + "-SUB", "name": "润滑点检 " + args.run_id, "type": 1,
                "content": "检查润滑与振动", "standard": "无泄漏且无异常振动", "status": 0, "remark": args.run_id,
            })
            plan = {"code": prefix + "-PLAN", "name": "设备闭环计划 " + args.run_id, "type": 1,
                    "startDate": iso(now - timedelta(days=2)), "endDate": iso(now + timedelta(days=2)),
                    "cycleType": 1, "cycleCount": 1, "remark": args.run_id}
            ids["planId"] = api.post("/mes/dv/check-plan/create", plan)
            save()
            api.post("/mes/dv/check-plan-machinery/create", {"planId": ids["planId"], "machineryId": ids["machineryId"]})
            api.post("/mes/dv/check-plan-subject/create", {"planId": ids["planId"], "subjectId": ids["subjectId"]})
            api.put("/mes/dv/check-plan/enable", id=ids["planId"])
            tasks = api.rows("/mes/dv/task", planId=ids["planId"])
            check("enable_catches_up_three_daily_occurrences", len(tasks) == 3 and all(t["status"] == 0 for t in tasks), records=len(tasks))
            repeated = [api.put("/mes/dv/task/generate-due", planId=ids["planId"]) for _ in range(2)]
            check("repeated_generation_is_idempotent", repeated == [0, 0] and len(api.rows("/mes/dv/task", planId=ids["planId"])) == 3)
            tasks.sort(key=lambda row: row["id"])
            ids["abnormalTaskId"], ids["normalTaskId"], ids["pendingTaskId"] = [t["id"] for t in tasks]
            api.put("/mes/dv/task/claim", id=ids["abnormalTaskId"])
            api.put("/mes/dv/task/claim", id=ids["abnormalTaskId"])
            task = api.get("/mes/dv/task/get", id=ids["abnormalTaskId"])
            require(len(task["lines"]) == 1, "Unexpected task project snapshot count")
            answer = {"id": task["id"], "lines": [{"id": task["lines"][0]["id"], "result": 2, "description": "验收样例：润滑泄漏 " + args.run_id}]}
            ids["checkRecordId"] = api.put("/mes/dv/task/complete", answer)
            check("completed_retry_returns_same_record", api.put("/mes/dv/task/complete", answer) == ids["checkRecordId"])
            task = api.get("/mes/dv/task/get", id=ids["abnormalTaskId"])
            ids["repairId"] = task["repairId"]
            repair = api.get("/mes/dv/repair/get", id=ids["repairId"])
            check("abnormal_creates_source_linked_repair", task["status"] == 2 and repair["sourceDocType"] == 101 and repair["sourceDocId"] == ids["checkRecordId"])
            check("abnormal_marks_machine_maintenance", api.get("/mes/dv/machinery/get", id=ids["machineryId"])["status"] == 3)
            repairs = api.rows("/mes/dv/repair", machineryId=ids["machineryId"])
            check("completed_retry_creates_no_duplicate_repair", len(repairs) == 1)
            api.put("/mes/dv/repair/submit", id=ids["repairId"])
            line = api.rows("/mes/dv/repair-line", repairId=ids["repairId"])[0]
            process = "已更换密封并复测 " + args.run_id
            api.put("/mes/dv/repair-line/update", {"id": line["id"], "repairId": ids["repairId"], "malfunction": line["malfunction"], "description": process})
            check("repair_process_can_be_updated_while_in_progress", api.get("/mes/dv/repair-line/get", id=line["id"])["description"] == process)
            api.put("/mes/dv/repair/confirm", {"id": ids["repairId"], "finishDate": iso(datetime.now())})
            api.put("/mes/dv/repair/finish", id=ids["repairId"], result=2)
            repair = api.get("/mes/dv/repair/get", id=ids["repairId"])
            check("failed_acceptance_returns_to_repair", repair["status"] == 1 and repair["result"] == 2 and api.get("/mes/dv/machinery/get", id=ids["machineryId"])["status"] == 3)
            api.put("/mes/dv/repair-line/update", {"id": line["id"], "repairId": ids["repairId"], "malfunction": line["malfunction"], "description": process + "；整改完成"})
            api.put("/mes/dv/repair/confirm", {"id": ids["repairId"], "finishDate": iso(datetime.now())})
            api.put("/mes/dv/repair/finish", id=ids["repairId"], result=1)
            repair = api.get("/mes/dv/repair/get", id=ids["repairId"])
            check("passed_acceptance_closes_repair_and_stops_machine", repair["status"] == 4 and repair["result"] == 1 and api.get("/mes/dv/machinery/get", id=ids["machineryId"])["status"] == 1)
            api.put("/mes/dv/check-plan/disable", id=ids["planId"])
            # Reset the draft cursor to another already-due date. Disabled generation must still be a no-op.
            api.put("/mes/dv/check-plan/update", {**plan, "id": ids["planId"], "startDate": iso(now - timedelta(days=3))})
            check("disabled_plan_stops_new_due_tasks", api.put("/mes/dv/task/generate-due", planId=ids["planId"]) == 0 and len(api.rows("/mes/dv/task", planId=ids["planId"])) == 3)
            api.put("/mes/dv/task/claim", id=ids["normalTaskId"])
            normal = api.get("/mes/dv/task/get", id=ids["normalTaskId"])
            ids["normalCheckRecordId"] = api.put("/mes/dv/task/complete", {"id": normal["id"], "lines": [{"id": l["id"], "result": 1, "description": "正常"} for l in normal["lines"]]})
            check("existing_task_can_finish_after_plan_disabled", api.get("/mes/dv/task/get", id=normal["id"])["status"] == 2)
            check("normal_result_does_not_create_repair", len(api.rows("/mes/dv/repair", machineryId=ids["machineryId"])) == 1)
            check("unclaimed_task_retained_for_inspection", api.get("/mes/dv/task/get", id=ids["pendingTaskId"])["status"] == 0)
        server = api.get("/infra/monitor/server")
        check("native_server_monitor_is_live", server["uptimeMillis"] > 0 and server["heapUsed"] > 0)
        database = api.get("/infra/monitor/database")
        check("native_druid_monitor_is_live", len(database["pools"]) > 0, pools=len(database["pools"]))
        check("druid_sql_statistics_enabled", all(p["sqlStatisticsEnabled"] for p in database["pools"]))
        sessions = api.get("/system/online-session/page", pageNo=1, pageSize=100)
        serialized = json.dumps(sessions)
        check("online_session_query_excludes_credentials", "accessToken" not in serialized and "refreshToken" not in serialized and login["accessToken"] not in serialized and login["refreshToken"] not in serialized)
        report["result"] = "passed"
        report["retainedFixtureNote"] = "Run-labelled sample device/type/project/plan and task/repair records are retained. Plan is disabled; one task remains unclaimed. No inventory or production records are changed."
        save()
        print(json.dumps(report, ensure_ascii=False, indent=2))
        return 0
    except Exception as error:
        report["result"] = "failed"
        report["error"] = str(error)
        save()
        print(json.dumps(report, ensure_ascii=False, indent=2))
        return 1


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    try:
        raise SystemExit(main())
    except Exception as error:
        # No repr/request payload/traceback is emitted, keeping credentials out of output.
        print(json.dumps({"result": "failed", "error": str(error)}, ensure_ascii=False))
        raise SystemExit(1)
