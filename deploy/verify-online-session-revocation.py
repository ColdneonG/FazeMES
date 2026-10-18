#!/usr/bin/env python3
"""Create two dedicated logins, revoke only the new target login, and verify access/refresh denial."""
import argparse
import json
from datetime import datetime
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
        self.tenant = tenant
        self.calls = 0

    def call(self, method, path, token=None, body=None, **params):
        headers = {"Content-Type": "application/json", "tenant-id": str(self.tenant)}
        if token:
            headers["Authorization"] = "Bearer " + token
        url = self.base + path + ("?" + urlencode(params) if params else "")
        payload = None if body is None else json.dumps(body).encode("utf-8")
        self.calls += 1
        try:
            with urlopen(Request(url, data=payload, headers=headers, method=method), timeout=45) as response:
                return response.status, json.load(response)
        except HTTPError as error:
            # Never echo the request URL, body, error body or login response.
            try:
                result = json.load(error)
            except (ValueError, UnicodeError):
                result = {"code": error.code}
            return error.code, result
        except URLError:
            raise RuntimeError(f"{method} {path}: backend unavailable") from None

    def success(self, method, path, token=None, body=None, **params):
        status, result = self.call(method, path, token, body, **params)
        require(status == 200 and result.get("code") == 0,
                f"{method} {path}: HTTP {status}, code={result.get('code')}")
        return result.get("data")

    def sessions(self, token, user_id):
        rows = []
        for page_no in range(1, 101):
            page = self.success("GET", "/system/online-session/page", token,
                                pageNo=page_no, pageSize=100, userId=user_id)
            rows.extend(page["list"])
            if len(rows) >= page["total"]:
                return rows
        raise RuntimeError("Session snapshot exceeded the verification limit")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", default="http://127.0.0.1:48080/admin-api")
    parser.add_argument("--tenant", type=int, default=1)
    parser.add_argument("--run-id", default="sessions-" + datetime.now().strftime("%Y%m%d-%H%M%S"))
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    require(re.fullmatch(r"[A-Za-z0-9_-]{1,40}", args.run_id), "run-id must use 1-40 safe characters")
    output = args.output or ROOT / ".runtime" / ("mes-online-session-" + args.run_id + ".json")
    require(not output.exists(), "Report already exists; use a fresh run-id")
    secrets = json.loads((ROOT / ".runtime/secrets.local.json").read_text(encoding="utf-8-sig"))
    login_body = {"username": secrets["AdminUsername"], "password": secrets["AdminPassword"]}
    api = Api(args.base, args.tenant)
    report = {"runId": args.run_id, "result": "running", "checks": [], "httpCalls": 0,
              "note": "Only the newly created target B is forcibly revoked. Existing UI logins are excluded from selection."}

    def save():
        report["httpCalls"] = api.calls
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    def check(name, condition):
        require(condition, name)
        report["checks"].append({"check": name, "passed": True})
        save()

    def denied(name, method, path, token=None, **params):
        status, result = api.call(method, path, token, **params)
        # This application normally wraps HTTP 200 with CommonResult.code=401.
        check(name, status == 401 or result.get("code") == 401)

    try:
        manager = api.success("POST", "/system/auth/login", body=login_body)
        check("dedicated_management_login_created", bool(manager.get("accessToken")))
        baseline = api.sessions(manager["accessToken"], manager["userId"])
        baseline_ids = {row["id"] for row in baseline}
        target = api.success("POST", "/system/auth/login", body=login_body)
        check("dedicated_target_is_independent", target["accessToken"] != manager["accessToken"]
              and target["refreshToken"] != manager["refreshToken"])
        api.success("GET", "/system/auth/get-permission-info", target["accessToken"])
        check("target_protected_request_works_before_revoke", True)
        after_login = api.sessions(manager["accessToken"], manager["userId"])
        created_ids = {row["id"] for row in after_login} - baseline_ids
        require(len(created_ids) == 1, "New target session is ambiguous; no session has been revoked")
        target_id = created_ids.pop()
        require(target_id not in baseline_ids, "Existing sessions must never be selected")
        report["targetSessionId"] = target_id
        check("target_selected_only_from_new_login", True)
        safe_json = json.dumps(after_login)
        check("session_response_has_no_credentials", "accessToken" not in safe_json and "refreshToken" not in safe_json
              and all(value not in safe_json for login in (manager, target)
                      for value in (login["accessToken"], login["refreshToken"])))
        check("target_revoke_succeeded", api.success("DELETE", "/system/online-session/revoke",
                                                     manager["accessToken"], id=target_id) is True)
        denied("target_access_rejected_with_401", "GET", "/system/auth/get-permission-info", target["accessToken"])
        denied("target_refresh_rejected_with_401", "POST", "/system/auth/refresh-token",
               refreshToken=target["refreshToken"])
        denied("target_refresh_as_access_rejected_with_401", "GET", "/system/auth/get-permission-info", target["refreshToken"])
        api.success("GET", "/system/auth/get-permission-info", manager["accessToken"])
        check("management_session_remains_valid", True)
        final = api.sessions(manager["accessToken"], manager["userId"])
        final_ids = {row["id"] for row in final}
        check("target_absent_and_existing_sessions_retained", target_id not in final_ids and baseline_ids.issubset(final_ids))
        report["result"] = "passed"
        report["retainedFixtureNote"] = "Dedicated manager A is retained until normal expiry; target B is revoked. No pre-existing UI session was selected."
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
        print(json.dumps({"result": "failed", "error": str(error)}, ensure_ascii=False))
        raise SystemExit(1)
