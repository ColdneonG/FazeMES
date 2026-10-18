#!/usr/bin/env python3
"""Verify Broky configuration, offline by default; never execute production.

    python -X utf8 deploy/verify-broky-product.py
    python -X utf8 deploy/verify-broky-product.py --base http://127.0.0.1:48080/admin-api

--base opts into authenticated, read-only HTTP verification. The separate
--publish-demo-configuration flag authorises only teaching SOP configuration
submit/approve/publish and enabling the exact checked Broky items, templates and
routes. It never confirms orders, creates child orders, posts stock, creates
inspection results, records work, books actual costs or impersonates operators.
"""
from __future__ import annotations

import argparse
from collections import defaultdict
from decimal import Decimal
import json
from pathlib import Path
import re
import sys
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "database/tools"))
from generate_broky_product import BOMS, FG, HEAD, STAND, PRODUCTS, RAW, ROUTES, OUT, build_rows, render_sql, validate


def require(value, message):
    if not value:
        raise RuntimeError(message)


def camel(name):
    return re.sub(r"_([a-z])", lambda m: m[1].upper(), name)


class Api:
    def __init__(self, base, secrets):
        self.base = base.rstrip("/")
        self.headers = {"Content-Type": "application/json", "tenant-id": "1"}
        self.calls = self.config_writes = 0
        auth = self.call("POST", "/system/auth/login", {"username": secrets["AdminUsername"], "password": secrets["AdminPassword"]})
        self.headers["Authorization"] = "Bearer " + auth["accessToken"]

    def call(self, method, path, body=None, **params):
        # A hard allowlist makes future edits unable to turn this into an
        # inventory/production acceptance script by mistake.
        if method != "GET" and path != "/system/auth/login":
            require(path in {"/mes/sop/version/submit", "/mes/sop/version/approve", "/mes/sop/version/publish",
                             "/mes/md/item/update-status", "/mes/pro/route/update-status", "/mes/qc/template/update"},
                    "Only allowlisted teaching configuration mutations are supported")
            self.config_writes += 1
        self.calls += 1
        url = self.base + path + ("?" + urlencode(params) if params else "")
        payload = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
        try:
            with urlopen(Request(url, data=payload, headers=self.headers, method=method), timeout=30) as response:
                result = json.load(response, parse_float=Decimal)
        except HTTPError as error:
            raise RuntimeError(f"{method} {path}: HTTP {error.code}") from None
        except URLError:
            raise RuntimeError(f"{method} {path}: backend unavailable") from None
        require(result.get("code") == 0, f"{method} {path}: code {result.get('code')}")
        return result.get("data")

    def get(self, path, **params):
        return self.call("GET", path, **params)


def compare(actual, expected, keys, label):
    require(isinstance(actual, dict), label + " missing")
    for key in keys:
        want = expected[key]
        got = actual.get(camel(key))
        if isinstance(want, Decimal):
            require(got is not None and Decimal(str(got)) == want, f"{label}: {key} differs")
        else:
            require(got == want, f"{label}: {key} differs")


def compare_list(actual, expected, keys, label):
    require(isinstance(actual, list) and len(actual) == len(expected), label + " row count differs")
    actual_ids = {row["id"] for row in actual}
    require(actual_ids == {row["id"] for row in expected}, label + " identities differ")
    lookup = {row["id"]: row for row in actual}
    for row in expected:
        compare(lookup[row["id"]], row, keys, label + "/" + str(row["id"]))


def live_verify(api, rows):
    grouped = defaultdict(list)
    for table, row, _ in rows:
        grouped[table].append(row)
    items, versions, templates = {}, {}, {}
    for row in grouped["mes_md_item"]:
        actual = api.get("/mes/md/item/get", id=row["id"])
        compare(actual, row, ("id", "code", "name", "item_type_id", "unit_measure_id", "batch_flag"), "item")
        require(actual.get("status") in (0, 1), "Invalid item status")
        items[row["id"]] = actual
        cfg = next(r for r in grouped["mes_md_item_batch_config"] if r["item_id"] == row["id"])
        actual_cfg = api.get("/mes/md/item-batch-config/get-by-item-id", itemId=row["id"])
        compare(actual_cfg, cfg, tuple(k for k in cfg if k.endswith("_flag")) + ("item_id",), "batch configuration")
        profile = next(r for r in grouped["mes_cost_profile"] if r["item_id"] == row["id"])
        compare(api.get("/mes/cost/profile", itemId=row["id"]), profile,
                ("item_id", "purchase_price", "sale_price", "labor_cost", "overhead_cost"), "price profile")
    for product, bom in BOMS.items():
        compare_list(api.get("/mes/md/product-bom/list-by-item-id", itemId=product),
                     [r for r in grouped["mes_md_product_bom"] if r["item_id"] == product],
                     ("item_id", "bom_item_id", "quantity", "status"), "BOM")
    route_states = {}
    for route_id, product, processes in ROUTES:
        route = next(r for r in grouped["mes_pro_route"] if r["id"] == route_id)
        actual = api.get("/mes/pro/route/get", id=route_id)
        compare(actual, route, ("id", "code", "name"), "route")
        require(actual.get("status") in (0, 1), "Invalid route status")
        route_states[route_id] = actual.get("status")
        compare_list(api.get("/mes/pro/route-process/list-by-route", routeId=route_id),
                     [r for r in grouped["mes_pro_route_process"] if r["route_id"] == route_id],
                     ("route_id", "process_id", "sort", "next_process_id", "key_flag", "check_flag"), "route steps")
        compare_list(api.get("/mes/pro/route-product-bom/list", routeId=route_id, productId=product),
                     [r for r in grouped["mes_pro_route_product_bom"] if r["product_id"] == product],
                     ("route_id", "product_id", "process_id", "item_id", "quantity"), "process materials")
        compare_list(api.get("/mes/pro/route-product/list-by-route", routeId=route_id),
                     [r for r in grouped["mes_pro_route_product"] if r["route_id"] == route_id],
                     ("route_id", "item_id", "quantity", "production_time", "time_unit_type"), "route products")
    docs = api.get("/mes/sop/document/list", keyword="Broky")
    for row in grouped["mes_sop_document"]:
        actual = next((d for d in docs if d["id"] == row["id"]), None)
        compare(actual, row, ("id", "code", "name", "item_id", "process_id"), "SOP document")
        candidates = api.get("/mes/sop/version/list", documentId=row["id"])
        version = next((v for v in candidates if v["id"] == row["id"]), None)
        expected = next(r for r in grouped["mes_sop_version"] if r["document_id"] == row["id"])
        compare(version, expected, ("id", "document_id", "version_no", "title"), "SOP version")
        for field in ("steps_json", "attachments_json"):
            require(json.loads(version[camel(field)]) == json.loads(expected[field]), "SOP content drift; review it before publication")
        require(version.get("status") in {"DRAFT", "SUBMITTED", "APPROVED", "PUBLISHED"}, "SOP version is rejected, disabled or superseded")
        require(not any(v["id"] != version["id"] and v["status"] == "PUBLISHED" for v in candidates),
                "A different SOP release exists; automatic teaching publication refused")
        versions[row["id"]] = version
    for row in grouped["mes_qc_template"]:
        actual = api.get("/mes/qc/template/get", id=row["id"])
        compare(actual, row, ("id", "code", "name", "remark"), "quality template")
        require(actual.get("status") in (0, 1), "Invalid quality template status")
        for key in ("types", "ipqc_types"):
            require((actual.get(camel(key)) or []) == [int(n) for n in row[key].split(",") if n], "Quality template scope differs")
        templates[row["id"]] = actual
        for table, endpoint, keys in (
            ("mes_qc_template_item", "/mes/qc/template/item/page", ("template_id", "item_id", "quantity_check", "quantity_unqualified", "critical_rate", "major_rate", "minor_rate")),
            ("mes_qc_template_indicator", "/mes/qc/template/indicator/page", ("template_id", "indicator_id", "check_method", "standard_value", "threshold_min", "threshold_max")),
        ):
            page = api.get(endpoint, templateId=row["id"], pageNo=1, pageSize=100)
            expected = [r for r in grouped[table] if r["template_id"] == row["id"]]
            require(page.get("total") == len(expected), "Quality template association count differs")
            compare_list(page.get("list"), expected, keys, table)
    cost = api.get("/mes/cost/product", itemId=FG)
    require(cost.get("status") == "COMPLETE" and cost.get("currency") == "CNY" and cost.get("taxBasis") == "EXCLUSIVE", "Broky cost is incomplete or currency/tax differs")
    for key, amount in (("materialCost", "148"), ("laborCost", "18"), ("overheadCost", "6"), ("totalCost", "172"), ("salePrice", "299")):
        require(Decimal(str(cost.get(key))) == Decimal(amount), "Broky rolled cost differs: " + key)
    require(len(cost.get("leaves", [])) == 28, "Cost must include exactly 28 different purchased leaves")
    for product, total in ((HEAD, "85.5"), (STAND, "31.5")):
        half = api.get("/mes/cost/product", itemId=product)
        require(half.get("status") == "COMPLETE" and Decimal(str(half.get("totalCost"))) == Decimal(total), "Semi-finished rolled cost differs")
    return items, versions, templates, route_states


def publish(api, items, versions, templates, route_states):
    comment = "教学配置审核与发布：已核验Broky本层BOM、工序分料及预算；非实物检验、非已完成生产、非安全认证。"
    for version in versions.values():
        state = version["status"]
        for before, action, after in (("DRAFT", "submit", "SUBMITTED"), ("SUBMITTED", "approve", "APPROVED"), ("APPROVED", "publish", "PUBLISHED")):
            if state == before:
                api.call("POST", "/mes/sop/version/" + action, {"id": version["id"], "comment": comment})
                state = after
    # Quality configuration must be enabled before any production item/route.
    for row in templates.values():
        if row["status"] != 0:
            api.call("PUT", "/mes/qc/template/update", {k: row.get(k) for k in ("id", "code", "name", "types", "ipqcTypes", "remark")} | {"status": 0})
    for item_id in (*RAW.values(), HEAD, STAND, FG):
        if items[item_id]["status"] != 0:
            api.call("PUT", "/mes/md/item/update-status", id=item_id, status=0)
    for route_id in route_states:
        if route_states[route_id] != 0:
            api.call("PUT", "/mes/pro/route/update-status", id=route_id, status=0)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", help="Opt into live verification against this admin-api URL")
    parser.add_argument("--secrets", type=Path, default=ROOT / ".runtime/secrets.local.json")
    parser.add_argument("--publish-demo-configuration", action="store_true")
    args = parser.parse_args()
    require(not args.publish_demo_configuration or args.base, "Publication requires explicit --base")
    rows = build_rows()
    report = validate(rows)
    require(OUT.exists() and OUT.read_text(encoding="utf-8") == render_sql(rows), "Regenerate and review the Broky migration")
    report.update(result="passed", mode="static-only", migrationMatches=True, postedTransactions=0)
    if args.base:
        require(args.secrets.exists(), "Credential file not found")
        api = Api(args.base, json.loads(args.secrets.read_text(encoding="utf-8-sig")))
        states = live_verify(api, rows)
        if args.publish_demo_configuration:
            publish(api, *states)
            states = live_verify(api, rows)
            require(all(v["status"] == "PUBLISHED" for v in states[1].values()), "Publication incomplete")
            require(all(r["status"] == 0 for r in states[0].values()) and all(r["status"] == 0 for r in states[2].values())
                    and all(s == 0 for s in states[3].values()), "Configuration enabling incomplete")
        report.update(mode="live-teaching-publication" if args.publish_demo_configuration else "live-read-only",
                      httpCalls=api.calls, configurationWrites=api.config_writes)
    print(json.dumps(report, ensure_ascii=False))


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(json.dumps({"result": "failed", "error": str(error)}, ensure_ascii=False), file=sys.stderr)
        sys.exit(1)
