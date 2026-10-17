#!/usr/bin/env python3
"""Verify persistent Quartz automatic and manual execution, then pause the probe."""
import importlib.util
import json
from pathlib import Path
import time

ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location("mes_flow", ROOT / "deploy/verify-mes-flow.py")
flow = importlib.util.module_from_spec(spec)
spec.loader.exec_module(flow)


def run(report):
    secrets = json.loads((ROOT / ".runtime/secrets.local.json").read_text(encoding="utf-8-sig"))
    api = flow.MesApi("http://127.0.0.1:48080/admin-api", 1)
    login = api.post("/system/auth/login", {"username": secrets["AdminUsername"], "password": secrets["AdminPassword"]})
    api.headers["Authorization"] = "Bearer " + login["accessToken"]
    jobs = api.get("/infra/job/page", pageNo=1, pageSize=100, handlerName="fazemesHealthJob")["list"]
    jobs = [job for job in jobs if job["handlerName"] == "fazemesHealthJob"]
    job_id = jobs[0]["id"] if jobs else None

    def logs():
        return api.get("/infra/job-log/page", pageNo=1, pageSize=100, jobId=job_id)["list"]

    def await_success(previous):
        # Quartz JDBC acquisition may already be sleeping for its idle interval
        # when a newly committed job is first scheduled.
        for _ in range(120):
            matches = [row for row in logs() if row["id"] not in previous and row["status"] == 1
                       and (row.get("result") or "").startswith("FazeMES scheduler OK")]
            if matches:
                return matches[0]["id"]
            time.sleep(0.5)
        raise RuntimeError("No successful execution within 60 seconds")

    try:
        if job_id is None:
            job_id = api.post("/infra/job/create", {
                "name": "FazeMES调度验收", "handlerName": "fazemesHealthJob", "handlerParam": "local acceptance",
                "cronExpression": "0/5 * * * * ?", "retryCount": 0, "retryInterval": 0, "monitorTimeout": 5000,
            })
            previous = set()
        else:
            previous = {row["id"] for row in logs()}
            if jobs[0]["status"] == 2:
                api.put("/infra/job/update-status", id=job_id, status=1)
            api.call("PUT", "/infra/job/update", {
                "id": job_id, "name": "FazeMES调度验收", "handlerName": "fazemesHealthJob", "handlerParam": "local acceptance",
                "cronExpression": "0/5 * * * * ?", "retryCount": 0, "retryInterval": 0, "monitorTimeout": 5000,
            })
        report["jobId"] = job_id
        automatic = await_success(previous)
        report["checks"].append({"check": "automatic_cron_execution", "passed": True, "logId": automatic})
        api.put("/infra/job/update-status", id=job_id, status=2)
        previous = {row["id"] for row in logs()}
        api.put("/infra/job/trigger", id=job_id)
        manual = await_success(previous)
        report["checks"].append({"check": "manual_trigger_execution", "passed": True, "logId": manual})
        report["passed"] = True
    finally:
        if job_id is not None:
            job = api.get("/infra/job/get", id=job_id)
            if job["status"] == 1:
                api.put("/infra/job/update-status", id=job_id, status=2)
            report["probePaused"] = api.get("/infra/job/get", id=job_id)["status"] == 2


if __name__ == "__main__":
    report = {"passed": False, "checks": []}
    try:
        run(report)
    except Exception as error:
        report["error"] = str(error)
    output = json.dumps(report, ensure_ascii=False, indent=2)
    (ROOT / ".runtime/scheduler-acceptance.json").write_text(output + "\n", encoding="utf-8")
    print(output)
    raise SystemExit(0 if report["passed"] else 1)
