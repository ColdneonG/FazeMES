"""Read-only authenticated API coverage for the synthetic fixture suite.

Only login/logout write authentication state. No business mutation, model call,
checkpoint replay, token persistence or new test record is performed here.
"""
from __future__ import annotations

from collections import Counter
from datetime import timedelta
import json
from pathlib import Path
import re
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen


ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "backend/yudao-module-mes/src/main/java/cn/iocoder/yudao/module/mes"
CONTROLLERS = JAVA / "controller/admin"
BASE = "http://127.0.0.1:48080/admin-api"


class ReadApi:
    def __init__(self):
        self.token = None
        self.owned_token = False

    def request(self, path, *, method="GET", payload=None, params=None):
        if method != "GET" and path not in ("/system/auth/login", "/system/auth/logout", "/mes/report/preview"):
            raise ValueError("Read-only verifier refused an unapproved non-GET endpoint")
        headers = {"tenant-id": "1", "Content-Type": "application/json"}
        if self.token:
            headers["Authorization"] = "Bearer " + self.token
        query = "?" + urlencode(params, doseq=True) if params else ""
        request = Request(BASE + path + query, headers=headers, method=method,
                          data=None if payload is None else json.dumps(payload).encode("utf-8"))
        try:
            with urlopen(request, timeout=30) as response:
                body = json.load(response)
                return response.status, body
        except HTTPError as error:
            try:
                body = json.loads(error.read())
            except (ValueError, UnicodeError):
                body = {"code": error.code, "msg": "HTTP error without JSON response"}
            return error.code, body
        except (URLError, TimeoutError, OSError, ValueError) as error:
            # Never include a Request/header representation containing credentials.
            return 0, {"code": None, "msg": type(error).__name__}

    def login(self, username, password):
        status, body = self.request("/system/auth/login", method="POST",
                                    payload={"username": username, "password": password})
        if status != 200 or body.get("code") != 0:
            return False, {"httpStatus": status, "code": body.get("code"), "message": body.get("msg", "")[:300]}
        self.token = body["data"]["accessToken"]
        self.owned_token = True
        return True, {"httpStatus": status, "code": 0}

    def close(self):
        if self.owned_token and self.token:
            self.request("/system/auth/logout", method="POST", payload={})
        self.token = None


def _snake(value):
    return re.sub(r"(?<!^)(?=[A-Z])", "_", value).lower()


def _first(ctx, table, column="id", where=""):
    if table not in ctx.meta or column not in ctx.meta[table]:
        return None
    live = "deleted=0" if "deleted" in ctx.meta[table] else "1=1"
    return ctx.scalar(f"SELECT `{column}` FROM `{table}` WHERE {live} AND `{column}` IS NOT NULL "
                      + ("AND " + where + " " if where else "") + "ORDER BY id LIMIT 1")


def _required_fields(source):
    required = []
    previous = 0
    for match in re.finditer(r"private\s+[\w.<>\[\]]+\s+(\w+)\s*(?:=[^;]*)?;", source):
        annotations = source[previous:match.start()]
        if re.search(r"@(NotNull|NotBlank|NotEmpty)\b", annotations):
            required.append(match.group(1))
        previous = match.end()
    return required


def _page_endpoints(ctx):
    """Use current Java mappings rather than a stale hand-maintained page list."""
    classes = {p.stem: p for p in CONTROLLERS.rglob("*.java")}
    objects = {p.stem: p for p in (JAVA / "dal/dataobject").rglob("*.java")}
    seen = set()
    for path in sorted(CONTROLLERS.rglob("*Controller.java")):
        source = path.read_text(encoding="utf-8-sig")
        root = re.search(r'@RequestMapping\("([^"\n]+)"\)', source)
        if not root:
            continue
        for match in re.finditer(r'@GetMapping\("([^"\n]+)"\)', source):
            tail = source[match.end():]
            header = tail.split("{", 1)[0]
            url = root.group(1) + match.group(1)
            if not (match.group(1).endswith("page") or "PageResult<" in header):
                continue
            if url in seen:
                continue
            seen.add(url)
            params = {"pageNo": 1, "pageSize": 10}
            vo = re.search(r"\b(\w+(?:PageReqVO|PageRequest))\b", header)
            required = []
            table = None
            if vo and vo.group(1) in classes:
                required = _required_fields(classes[vo.group(1)].read_text(encoding="utf-8-sig"))
                object_name = vo.group(1).replace("PageReqVO", "DO")
                if object_name in objects:
                    do_source = objects[object_name].read_text(encoding="utf-8-sig")
                    table_match = re.search(r'@TableName\((?:value\s*=\s*)?"([^"\n]+)"', do_source)
                    table = table_match.group(1) if table_match else None
            # When a child row exists, derive all required parent filters from
            # that same row so receipt/line and quality-type/quality-ID agree.
            columns = {field: _snake(field) for field in required}
            usable = [field for field, column in columns.items() if table in ctx.meta and column in ctx.meta[table]]
            existing = []
            if table and usable:
                select = ",".join(f"`{columns[f]}` AS `{f}`" for f in usable)
                live = "deleted=0 AND " if "deleted" in ctx.meta[table] else ""
                valid = " AND ".join(f"`{columns[f]}` IS NOT NULL" for f in usable)
                existing = ctx.query(f"SELECT {select} FROM `{table}` WHERE {live}{valid} ORDER BY id LIMIT 1")
            if existing:
                params.update(existing[0])
            masters = {"itemId": ("mes_md_item", 900103), "teamId": ("mes_cal_team", 980001),
                       "processId": ("mes_pro_process", 900001), "workOrderId": ("mes_pro_work_order", 0),
                       "feedbackId": ("mes_pro_feedback", 0), "templateId": ("mes_qc_template", 0),
                       "iqcId": ("mes_qc_iqc", 0), "ipqcId": ("mes_qc_ipqc", 0),
                       "oqcId": ("mes_qc_oqc", 0), "rqcId": ("mes_qc_rqc", 0),
                       "cardId": ("mes_pro_card", 0), "packageId": ("mes_wm_package", 0)}
            for field in required:
                if field in params:
                    continue
                if field == "qcType":
                    params[field] = 1  # MesBizTypeConstants.QC_IQC
                elif field == "qcId":
                    params[field] = _first(ctx, "mes_qc_iqc") or 0
                elif field == "returnId" and "/return-sales" in url:
                    params[field] = _first(ctx, "mes_wm_return_sales") or 0
                elif field in masters:
                    target, default = masters[field]
                    params[field] = _first(ctx, target) or default
                else:
                    params[field] = 0  # Valid non-matching child filter; never counted as fixture presence.
            yield {"path": url, "params": params, "page": True,
                   "source": str(path.relative_to(ROOT)), "requiredFields": required}


def _extras(ctx):
    now = ctx.now
    order = _first(ctx, "mes_pro_work_order", where="code LIKE 'TST-%' AND status<>0")
    task = _first(ctx, "mes_pro_task", where="status NOT IN(4,5)")
    feedback = _first(ctx, "mes_pro_feedback")
    batch = _first(ctx, "mes_wm_batch", "code")
    row = _first(ctx, "mes_pro_sn_trace")
    endpoints = []

    def get(path, **params):
        endpoints.append({"path": path, "params": params, "page": False})

    for path in ["/mes/home-statistics/summary", "/mes/home-statistics/work-order-status",
                 "/mes/home-statistics/production-trend", "/mes/md/item/stock-warning",
                 "/mes/dashboard/production", "/mes/insight/risks", "/mes/insight/quality",
                 "/mes/insight/flow", "/mes/insight/incidents", "/mes/insight/drafts",
                 "/mes/insight/jev/status", "/mes/report/datasets", "/mes/report/list",
                 "/mes/assistant/status", "/mes/assistant/bases",
                 "/mes/pro/kitting/board", "/mes/pro/workbench/workstations",
                 "/mes/sop/document/list"]:
        get(path)
    get("/mes/qc/analysis/comparison", startDate=(now-timedelta(days=30)).date().isoformat(),
        endDate=now.date().isoformat(), compareMode="PREVIOUS", rateThreshold=5, increaseThreshold=2)
    for suffix in ["product", "profile", "profile-history"]:
        get("/mes/cost/" + suffix, itemId=ctx.ids.get("product", 900103))
    if order:
        get("/mes/cost/workorder", workorderId=order)
        get("/mes/insight/timeline", workOrderId=order)
        get("/mes/pro/work-order/loss-record", id=order)
        for suffix in ["analyze", "reservations", "history"]:
            get("/mes/pro/kitting/" + suffix, workOrderId=order)
        get("/mes/pro/sn-trace/feedback-options", workOrderId=order)
    if task:
        get("/mes/pro/workbench/context", taskId=task)
        get("/mes/sop/task", taskId=task)
    get("/mes/pro/workbench/tasks", workstationId=900001)
    get("/mes/pro/workbench/machines", workstationId=900001)
    if feedback:
        get("/mes/pro/sn-trace/consume-options", feedbackId=feedback)
    if row:
        get("/mes/pro/sn-trace/get", id=row)
    if batch:
        for suffix in ["trace-coverage", "forward-list", "backward-list"]:
            get("/mes/wm/batch/" + suffix, code=batch)
    for station in ctx.ids.get("stations", []):
        get("/mes/md-workstation/availability", id=station)
    for report in ctx.ids.get("reports", []):
        get("/mes/report/get", id=report)
        endpoints.append({"path": "/mes/report/preview", "params": {}, "method": "POST",
                          "payload": {"id": report, "version": 1, "pageNo": 1, "pageSize": 10}, "page": False})
    for document in ctx.ids.get("knowledge_documents", []):
        get("/mes/assistant/document", id=document)
    for table, path, field in [
        ("mes_wm_product_sales", "/mes/cost/sales", "salesId"),
        ("mes_wm_return_sales", "/mes/cost/return-sales", "id"),
        ("mes_wm_item_receipt_line", "/mes/cost/receipt-line-price", "lineId"),
        ("mes_wm_product_sales_line", "/mes/cost/sales-line-price", "lineId"),
        ("mes_insight_incident", "/mes/insight/incident-history", "id"),
    ]:
        id_ = _first(ctx, table)
        if id_:
            get(path, **{field: id_})
    return endpoints


def _evidence(status, body, endpoint):
    data = body.get("data")
    evidence = {"method": endpoint.get("method", "GET"), "path": endpoint["path"],
                "params": endpoint.get("params", {}), "httpStatus": status, "code": body.get("code")}
    if "source" in endpoint:
        evidence.update(source=endpoint["source"], requiredFields=endpoint["requiredFields"])
    if isinstance(data, dict) and "total" in data:
        evidence.update(total=data["total"], returnedRows=len(data.get("list", [])))
    elif isinstance(data, list):
        evidence["returnedRows"] = len(data)
    if body.get("code") != 0:
        evidence["message"] = str(body.get("msg", ""))[:300]
    return evidence


def verify_api(ctx):
    """Return checks with explicit outcomes; the orchestrator owns report writing."""
    checks = []
    admin = ReadApi()
    # Reuse the current admin token when available; do not store it in evidence.
    admin.token = ctx.scalar("SELECT access_token FROM system_oauth2_access_token WHERE user_id=1 "
                             "AND deleted=0 AND expires_time>NOW() ORDER BY create_time DESC LIMIT 1")
    if not admin.token:
        ok, evidence = admin.login(ctx.secrets["AdminUsername"], ctx.secrets["AdminPassword"])
        checks.append({"check": "api.auth.admin", "passed": ok, "evidence": evidence})
        if not ok:
            return checks
    try:
        endpoints = list(_page_endpoints(ctx)) + _extras(ctx)
        seen = set()
        for endpoint in endpoints:
            identity = json.dumps([endpoint.get("method", "GET"), endpoint["path"], endpoint.get("params"), endpoint.get("payload")], sort_keys=True)
            if identity in seen:
                continue
            seen.add(identity)
            status, body = admin.request(endpoint["path"], method=endpoint.get("method", "GET"),
                                         params=endpoint.get("params"), payload=endpoint.get("payload"))
            passed = status == 200 and body.get("code") == 0
            evidence = _evidence(status, body, endpoint)
            if passed and endpoint.get("page"):
                data = body.get("data")
                passed = isinstance(data, dict) and isinstance(data.get("list"), list) and isinstance(data.get("total"), (int, float))
                if not passed:
                    evidence["message"] = "Successful envelope lacks PageResult list/total"
            parameter_needed = status == 400 or body.get("code") == 400
            evidence["outcome"] = "PASS" if passed else "REQUIRED_PARAMETERS" if parameter_needed else "FAIL"
            checks.append({"check": "api.endpoint." + endpoint["path"] + ":" + str(len(checks)), "passed": passed, "evidence": evidence})
        checks.extend(_role_checks(ctx))
    finally:
        admin.close()
    endpoint_checks = [c for c in checks if c["check"].startswith("api.endpoint.")]
    stats = Counter(c["evidence"]["outcome"] for c in endpoint_checks)
    checks.append({"check": "api.endpoint_coverage", "passed": bool(endpoint_checks) and all(c["passed"] for c in endpoint_checks),
                   "evidence": {"endpointCount": len(endpoint_checks), "passed": stats["PASS"],
                                "requiredParameters": stats["REQUIRED_PARAMETERS"], "failures": stats["FAIL"],
                                "uniqueRouteCount": len({(c["evidence"]["method"], c["evidence"]["path"]) for c in endpoint_checks}),
                                "pageEndpointCount": sum("source" in c["evidence"] for c in endpoint_checks)}})
    return checks


def _role_checks(ctx):
    checks = []
    for username in ["store01", "operator01", "viewer01"]:
        api = ReadApi()
        ok, evidence = api.login(username, "Test@2026!")
        checks.append({"check": "api.role." + username + ".login", "passed": ok, "evidence": evidence})
        if not ok:
            continue
        try:
            status, body = api.request("/system/auth/get-permission-info")
            passed = status == 200 and body.get("code") == 0
            data = body.get("data") or {}
            permissions = set(data.get("permissions", []))
            checks.append({"check": "api.role." + username + ".permissions", "passed": passed,
                           "evidence": {"httpStatus": status, "code": body.get("code"), "permissionCount": len(permissions),
                                        "roles": data.get("roles", [])}})
            if not passed:
                continue
            if username == "store01":
                status, body = api.request("/mes/cost/product", params={"itemId": ctx.ids.get("product", 900103)})
                checks.append({"check": "api.role.warehouse.cost_denied", "passed": status == 403 or body.get("code") == 403,
                               "evidence": {"httpStatus": status, "code": body.get("code"), "costPermissionPresent": "mes:cost:query" in permissions}})
            if username == "operator01":
                stock_mutations = sorted(p for p in permissions if p.startswith("mes:wm-") and not p.endswith((":query", ":export", ":read")))
                checks.append({"check": "api.role.operator.stock_mutation_denied", "passed": not stock_mutations,
                               "evidence": {"assertion": "No warehouse mutation authority in effective server permission info; no write endpoint invoked", "unexpectedPermissions": stock_mutations}})
            if username == "viewer01":
                mutations = sorted(p for p in permissions if p.startswith("mes:") and not p.endswith((":query", ":export", ":read")))
                checks.append({"check": "api.role.viewer.query_only", "passed": not mutations and any(p.endswith(":query") for p in permissions),
                               "evidence": {"unexpectedPermissions": mutations, "queryPermissionCount": sum(p.endswith(":query") for p in permissions)}})
                status, body = api.request("/mes/pro/work-order/page", params={"pageNo": 1, "pageSize": 5})
                checks.append({"check": "api.role.viewer.workorders_read", "passed": status == 200 and body.get("code") == 0,
                               "evidence": {"httpStatus": status, "code": body.get("code")}})
                lifecycle = ctx.ids.get("warehouse_ops", {}).get("reports", {})
                if lifecycle:
                    published = lifecycle["published"]
                    status, body = api.request("/mes/report/list")
                    visible = {r["id"] for r in body.get("data", [])} if body.get("code") == 0 else set()
                    checks.append({"check": "api.role.viewer.published_reports_only",
                                   "passed": status == 200 and body.get("code") == 0 and visible == set(published),
                                   "evidence": {"httpStatus": status, "code": body.get("code"), "visibleReportIds": sorted(visible)}})
                    report = lifecycle["unpublishedLatest"]
                    status, body = api.request("/mes/report/get", params={"id": report})
                    snapshot = body.get("data") or {}
                    checks.append({"check": "api.role.viewer.published_snapshot",
                                   "passed": status == 200 and body.get("code") == 0 and snapshot.get("version") == 1
                                   and snapshot.get("definition", {}).get("latestVersion") == 1,
                                   "evidence": {"httpStatus": status, "code": body.get("code"), "visibleVersion": snapshot.get("version")}})
                    status, body = api.request("/mes/report/get", params={"id": report, "version": 2})
                    checks.append({"check": "api.role.viewer.new_draft_hidden",
                                   "passed": status == 200 and body.get("code") == 1047130001,
                                   "evidence": {"httpStatus": status, "code": body.get("code"), "requestedDraftVersion": 2}})
        finally:
            api.close()
    return checks
