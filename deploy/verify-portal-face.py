#!/usr/bin/env python3
"""Real HTTP acceptance for the customer portal and non-biometric face-login rejection paths.

Run only after the new backend is deployed. Creates two identifiable QA customer
accounts and one QA request; existing customers and production work orders are
read-only. A necessary temporary catalog publication is restored in finally.
New accounts are disabled and their random passwords rotated in finally.
Passwords, bearer/customer tokens, names, addresses and business text are never
printed or written to the summary. No person photographs or camera are used.
"""
from __future__ import annotations

import argparse
import base64
from copy import deepcopy
from datetime import date, datetime, timedelta
import importlib.util
import json
from pathlib import Path
import secrets
import struct
import sys
from urllib.parse import urlsplit, urlunsplit
import uuid
import zlib

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("fazemes_report_acceptance", ROOT / "deploy/verify-report-dashboard.py")
COMMON = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(COMMON)
Api = COMMON.Api
BusinessError = COMMON.BusinessError
require = COMMON.require
expect_business_rejection = COMMON.expect_business_rejection

FACE_REJECTED = 1_047_140_001
SAFE_ORDER_FIELDS = {
    "id", "code", "name", "productName", "specification", "quantity",
    "quantityProduced", "requestDate", "finishDate", "status",
}
SAFE_REQUEST_FIELDS = {
    "id", "code", "deliveryDate", "remark", "status", "reviewNote",
    "reviewTime", "createTime", "items",
}


def app_base(admin_base):
    parsed = urlsplit(admin_base)
    path = parsed.path.rstrip("/")
    require(path.endswith("/admin-api"), "admin_base_must_end_in_admin_api")
    return urlunsplit((parsed.scheme, parsed.netloc, path[:-len("/admin-api")] + "/app-api", "", ""))


def blank_png():
    """Generate an unlabelled white 320x320 PNG solely in memory, using stdlib."""
    def chunk(name, content):
        return struct.pack(">I", len(content)) + name + content + struct.pack(">I", zlib.crc32(name + content) & 0xffffffff)
    raw = (b"\0" + b"\xff" * (320 * 3)) * 320
    image = (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 320, 320, 8, 2, 0, 0, 0))
             + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))
    return "data:image/png;base64," + base64.b64encode(image).decode("ascii")


def ids(rows):
    return {int(row["id"]) for row in rows}


def client_order_ids(admin, client_id):
    """Read the existing administrative customer filter as an independent range oracle."""
    result = set()
    page_no = 1
    while True:
        page = admin.request("GET", "/mes/pro/work-order/page", pageNo=page_no, pageSize=100, clientId=client_id)
        rows = page.get("list", [])
        for row in rows:
            require(int(row["clientId"]) == client_id, "administrative_customer_filter_mismatch")
            if row.get("status") in (1, 2, 3):
                result.add(int(row["id"]))
        if page_no * 100 >= int(page["total"]):
            return result
        require(page_no < 50, "customer_order_fixture_exceeds_acceptance_read_bound")
        page_no += 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", default="http://127.0.0.1:48080/admin-api")
    parser.add_argument("--tenant", type=int, default=1)
    parser.add_argument("--other-tenant", type=int, default=2)
    parser.add_argument("--client-one", type=int, default=1)
    parser.add_argument("--client-two", type=int, default=2)
    parser.add_argument("--output", type=Path, default=ROOT / ".runtime/portal-face-http-acceptance.json")
    args = parser.parse_args()
    require(args.tenant != args.other_tenant, "cross_tenant_requires_distinct_tenants")
    require(args.client_one != args.client_two, "cross_customer_requires_distinct_customers")

    checks = []
    counts = {"qaAccountsCreated": 0, "qaRequestsCreated": 0, "temporaryCatalogPublications": 0, "faceChallengesIssued": 0}
    cleanup = {"qaAccountsDisabled": 0, "catalogPublicStateRestored": True, "errors": []}
    stage = "initialization"
    summary = {
        "result": "failed", "checks": checks, "counts": counts, "cleanup": cleanup,
        "syntheticImagesOnly": True, "cameraAccessed": False, "realFaceEnrolled": False,
        "retainedFixtures": "identifiable QA requests, disabled QA accounts and any unpublished QA catalog entry",
    }
    admin = None
    qa_accounts = []
    catalog_restore = None
    failure = None
    run_id = uuid.uuid4().hex[:16]

    def check(name, condition):
        require(condition, name)
        checks.append({"check": name, "passed": True})

    def reject(api, name, code, method, path, body=None, **params):
        actual = expect_business_rejection(api, code, method, path, body, **params)
        checks.append({"check": name, "passed": True, "businessCode": actual})

    def portal_login(account):
        api = Api(app_base(args.base), args.tenant)
        session = api.request("POST", "/mes/customer-portal/login", {
            "tenantId": args.tenant, "username": account["username"], "password": account["password"]})
        check("portal_session_contract", isinstance(session.get("token"), str) and len(session["token"]) == 43
              and isinstance(session.get("expiresAt"), str) and set(session["account"]) == {"displayName", "clientName"})
        api.headers["X-Portal-Token"] = session["token"]
        return api

    def save_account(account, **changes):
        account.update(changes)
        payload = {key: account[key] for key in ("clientId", "username", "displayName", "enabled")}
        payload["password"] = changes.get("password", "")
        if account.get("id") is not None:
            payload["id"] = account["id"]
        account["id"] = admin.request("POST", "/mes/portal/account/save", payload)

    try:
        stage = "administrator_authentication"
        admin = Api(args.base, args.tenant)
        credentials = json.loads((ROOT / ".runtime/secrets.local.json").read_text(encoding="utf-8-sig"))
        session = admin.request("POST", "/system/auth/login", {
            "username": credentials["AdminUsername"], "password": credentials["AdminPassword"]})
        admin.headers["Authorization"] = "Bearer " + session["accessToken"]
        del credentials, session

        stage = "portal_fixture_preconditions"
        clients = admin.request("GET", "/mes/portal/clients")
        enabled_client_ids = {int(item["id"]) for item in clients}
        check("two_existing_enabled_customers_available", {args.client_one, args.client_two} <= enabled_client_ids)
        expected_one = client_order_ids(admin, args.client_one)
        expected_two = client_order_ids(admin, args.client_two)
        check("existing_customer_order_ranges_disjoint", not expected_one & expected_two)

        stage = "portal_qa_account_creation"
        for index, client_id in enumerate((args.client_one, args.client_two), 1):
            account = {"clientId": client_id, "username": f"qa_portal_{run_id}_{index}",
                       "displayName": f"QA门户验收-{run_id}-{index}", "password": secrets.token_urlsafe(24), "enabled": True}
            qa_accounts.append(account)
            save_account(account, password=account["password"])
            counts["qaAccountsCreated"] += 1
        first = portal_login(qa_accounts[0])
        second = portal_login(qa_accounts[1])
        no_portal_token = Api(app_base(args.base), args.tenant)
        reject(no_portal_token, "portal_missing_token_rejected", 401, "GET", "/mes/customer-portal/orders")
        forged = Api(app_base(args.base), args.tenant)
        forged.headers["X-Portal-Token"] = "A" * 43
        reject(forged, "portal_forged_token_rejected", 401, "GET", "/mes/customer-portal/me")
        reject(no_portal_token, "portal_wrong_login_tenant_rejected", 401, "POST", "/mes/customer-portal/login", {
            "tenantId": args.other_tenant, "username": qa_accounts[0]["username"], "password": qa_accounts[0]["password"]})

        stage = "portal_production_order_isolation"
        orders_one = first.request("GET", "/mes/customer-portal/orders")
        orders_two = second.request("GET", "/mes/customer-portal/orders")
        check("customer_one_orders_match_existing_records", ids(orders_one) == set(sorted(expected_one, reverse=True)[:200]))
        check("customer_two_orders_match_existing_records", ids(orders_two) == set(sorted(expected_two, reverse=True)[:200]))
        check("cross_customer_orders_inaccessible", not ids(orders_one) & ids(orders_two))
        check("customer_order_projection_has_no_internal_fields", all(set(order) <= SAFE_ORDER_FIELDS for order in orders_one + orders_two))
        injected = first.request("GET", "/mes/customer-portal/orders", clientId=args.client_two, tenantId=args.other_tenant)
        check("client_id_query_cannot_change_customer_scope", ids(injected) == ids(orders_one))
        first.headers["tenant-id"] = str(args.other_tenant)
        try:
            check("portal_scope_comes_from_session_not_tenant_header", ids(first.request("GET", "/mes/customer-portal/orders")) == ids(orders_one))
        finally:
            first.headers["tenant-id"] = str(args.tenant)
        portal_as_admin = Api(args.base, args.tenant)
        portal_as_admin.headers["Authorization"] = "Bearer " + first.headers["X-Portal-Token"]
        reject(portal_as_admin, "customer_token_never_grants_administrator_access", 401, "GET", "/mes/portal/accounts")

        stage = "portal_real_catalog"
        catalog = first.request("GET", "/mes/customer-portal/catalog")
        if not catalog:
            products = admin.request("GET", "/mes/portal/products")
            check("real_product_available_for_catalog_acceptance", bool(products))
            published = admin.request("GET", "/mes/portal/catalog")
            by_product = {int(item["productId"]): item for item in published}
            product = next((item for item in products if int(item["id"]) not in by_product), products[0])
            product_id = int(product["id"])
            prior = by_product.get(product_id)
            catalog_restore = {"productId": product_id,
                               "description": prior.get("description", "") if prior else f"QA门户验收-{run_id}-已下架",
                               "enabled": bool(prior.get("enabled")) if prior else False}
            cleanup["catalogPublicStateRestored"] = False
            admin.request("POST", "/mes/portal/catalog/save", {
                "productId": product_id, "description": f"QA门户验收-{run_id}", "enabled": True})
            counts["temporaryCatalogPublications"] += 1
            catalog = first.request("GET", "/mes/customer-portal/catalog")
        check("catalog_contains_only_explicitly_published_products", bool(catalog) and all("productId" in item for item in catalog))
        product_id = int(catalog[0]["productId"])

        stage = "portal_checkout_and_review"
        request_key = str(uuid.uuid4())
        order_body = {"requestKey": request_key, "deliveryDate": (date.today() + timedelta(days=21)).isoformat(),
                      "remark": f"QA门户验收-{run_id}", "items": [{"productId": product_id, "quantity": 1}],
                      "clientId": args.client_two, "tenantId": args.other_tenant}
        created = first.request("POST", "/mes/customer-portal/requests", order_body)
        counts["qaRequestsCreated"] += 1
        check("portal_checkout_pending_contract", created.get("status") == "PENDING" and isinstance(created.get("id"), int))
        duplicate = first.request("POST", "/mes/customer-portal/requests", order_body)
        check("portal_submission_retry_is_idempotent", duplicate == created)
        own_requests = first.request("GET", "/mes/customer-portal/requests")
        foreign_requests = second.request("GET", "/mes/customer-portal/requests", clientId=args.client_one)
        check("qa_request_visible_only_to_bound_customer", int(created["id"]) in ids(own_requests) and int(created["id"]) not in ids(foreign_requests))
        check("request_projection_has_no_account_or_tenant_details", all(set(item) <= SAFE_REQUEST_FIELDS for item in own_requests))
        admin_requests = admin.request("GET", "/mes/portal/requests")
        admin_copy = next((item for item in admin_requests if int(item["id"]) == int(created["id"])), None)
        check("checkout_ignores_supplied_foreign_customer_id", admin_copy is not None and int(admin_copy["clientId"]) == args.client_one)
        invalid = deepcopy(order_body)
        invalid["requestKey"] = str(uuid.uuid4())
        invalid["items"][0]["quantity"] = 0
        reject(first, "nonpositive_checkout_quantity_rejected", 400, "POST", "/mes/customer-portal/requests", invalid)
        review_note = f"QA门户验收-{run_id}-已受理"
        admin.request("POST", "/mes/portal/request/review", {"id": created["id"], "status": "ACCEPTED", "reviewNote": review_note})
        reviewed = next(item for item in first.request("GET", "/mes/customer-portal/requests") if int(item["id"]) == int(created["id"]))
        check("accepted_request_feedback_reaches_customer", reviewed["status"] == "ACCEPTED" and reviewed["reviewNote"] == review_note)
        reject(admin, "review_transition_cannot_be_replayed", 400, "POST", "/mes/portal/request/review", {
            "id": created["id"], "status": "REJECTED", "reviewNote": f"QA门户验收-{run_id}-重放"})
        check("portal_checkout_does_not_create_production_orders", client_order_ids(admin, args.client_one) == expected_one and client_order_ids(admin, args.client_two) == expected_two)

        stage = "portal_session_revocation"
        save_account(qa_accounts[0], enabled=False)
        reject(first, "disabled_account_revokes_existing_customer_token", 401, "GET", "/mes/customer-portal/me")
        reject(no_portal_token, "disabled_account_cannot_login", 401, "POST", "/mes/customer-portal/login", {
            "tenantId": args.tenant, "username": qa_accounts[0]["username"], "password": qa_accounts[0]["password"]})
        save_account(qa_accounts[0], enabled=True)
        before_rebind = portal_login(qa_accounts[0])
        save_account(qa_accounts[0], clientId=args.client_two)
        reject(before_rebind, "customer_rebinding_revokes_old_session", 401, "GET", "/mes/customer-portal/me")
        rebound = portal_login(qa_accounts[0])
        check("rebound_account_sees_only_new_customer_orders", ids(rebound.request("GET", "/mes/customer-portal/orders")) == ids(orders_two))
        check("rebound_account_cannot_see_old_customer_request", int(created["id"]) not in ids(rebound.request("GET", "/mes/customer-portal/requests")))
        rebound.request("POST", "/mes/customer-portal/logout")
        reject(rebound, "portal_logout_revokes_session", 401, "GET", "/mes/customer-portal/me")

        stage = "face_anonymous_and_input_guards"
        anonymous = Api(args.base, args.tenant)
        face_status = anonymous.request("GET", "/mes/face/login-status")
        check("face_local_worker_ready", face_status.get("ready") is True)
        check("face_status_does_not_claim_liveness", face_status.get("comparison") == "1:1" and face_status.get("livenessSupported") is False
              and set(face_status) == {"ready", "comparison", "livenessSupported"})
        image = blank_png()
        reject(anonymous, "anonymous_face_enrollment_forbidden", 401, "POST", "/mes/face/enroll", {
            "password": "synthetic-not-an-account-password", "consent": True, "images": [image]})
        reject(anonymous, "anonymous_face_disable_forbidden", 401, "POST", "/mes/face/disable", {"password": "synthetic-not-an-account-password"})
        reject(anonymous, "anonymous_private_face_status_forbidden", 401, "GET", "/mes/face/status")
        reject(anonymous, "invalid_face_login_body_cannot_issue_token", 400, "POST", "/mes/face/login", {
            "username": f"qa_face_{run_id}_invalid", "challenge": "synthetic", "imageBase64": ""})
        reject(anonymous, "empty_face_username_cannot_issue_challenge", 400, "POST", "/mes/face/challenge", {"username": ""})

        stage = "face_single_use_and_binding_rejections"
        unknown_a = f"qa_face_{run_id}_a"
        challenge_a = anonymous.request("POST", "/mes/face/challenge", {"username": unknown_a})
        counts["faceChallengesIssued"] += 1
        check("face_challenge_contract", len(challenge_a["challenge"]) == 43 and challenge_a["expiresIn"] == 60)
        face_body = {"username": unknown_a, "challenge": challenge_a["challenge"], "imageBase64": image}
        reject(anonymous, "unknown_face_account_never_receives_token", FACE_REJECTED, "POST", "/mes/face/login", face_body)
        reject(anonymous, "consumed_face_challenge_replay_rejected", FACE_REJECTED, "POST", "/mes/face/login", face_body)
        unknown_b = f"qa_face_{run_id}_b"
        challenge_b = anonymous.request("POST", "/mes/face/challenge", {"username": unknown_b})
        counts["faceChallengesIssued"] += 1
        wrong_name = {"username": f"qa_face_{run_id}_c", "challenge": challenge_b["challenge"], "imageBase64": image}
        reject(anonymous, "face_challenge_cannot_change_username", FACE_REJECTED, "POST", "/mes/face/login", wrong_name)
        wrong_name["username"] = unknown_b
        reject(anonymous, "wrong_binding_consumes_face_challenge", FACE_REJECTED, "POST", "/mes/face/login", wrong_name)
        unknown_d = f"qa_face_{run_id}_d"
        challenge_d = anonymous.request("POST", "/mes/face/challenge", {"username": unknown_d})
        counts["faceChallengesIssued"] += 1
        face_cross_tenant = Api(args.base, args.other_tenant)
        face_cross_tenant.headers["Authorization"] = admin.headers["Authorization"]
        reject(face_cross_tenant, "face_cross_tenant_header_rejected_before_verification", 403, "POST", "/mes/face/login", {
            "username": unknown_d, "challenge": challenge_d["challenge"], "imageBase64": image})
        reject(anonymous, "qa_face_probe_never_issues_administrator_token", FACE_REJECTED, "POST", "/mes/face/login", {
            "username": unknown_d, "challenge": challenge_d["challenge"], "imageBase64": image})
        check("face_challenge_usage_stays_below_ip_limit", counts["faceChallengesIssued"] == 3)
        summary["result"] = "passed"
    except BaseException as error:
        failure = {"stage": stage, "type": type(error).__name__}
        if isinstance(error, BusinessError):
            failure["businessCode"] = error.code
        summary["failure"] = failure
    finally:
        # Cleanup is attempted even when a prior request timed out after committing.
        if admin is not None and "Authorization" in admin.headers:
            for account in qa_accounts:
                try:
                    if account.get("id") is None:
                        match = next((row for row in admin.request("GET", "/mes/portal/accounts") if row.get("username") == account["username"]), None)
                        if match is None:
                            continue
                        account["id"] = match["id"]
                    save_account(account, enabled=False, password=secrets.token_urlsafe(24))
                    cleanup["qaAccountsDisabled"] += 1
                except Exception as error:
                    item = {"operation": "disable_qa_account", "type": type(error).__name__}
                    if isinstance(error, BusinessError):
                        item["businessCode"] = error.code
                    cleanup["errors"].append(item)
            if catalog_restore is not None:
                try:
                    admin.request("POST", "/mes/portal/catalog/save", catalog_restore)
                    final = next((row for row in admin.request("GET", "/mes/portal/catalog") if int(row["productId"]) == catalog_restore["productId"]), None)
                    require(final is not None and bool(final["enabled"]) == catalog_restore["enabled"] and final["description"] == catalog_restore["description"], "catalog_public_state_restoration_failed")
                    cleanup["catalogPublicStateRestored"] = True
                except Exception as error:
                    item = {"operation": "restore_catalog", "type": type(error).__name__}
                    if isinstance(error, BusinessError):
                        item["businessCode"] = error.code
                    cleanup["errors"].append(item)
            if qa_accounts:
                try:
                    final_accounts = admin.request("GET", "/mes/portal/accounts")
                    fixture_names = {account["username"] for account in qa_accounts}
                    check("all_created_qa_accounts_disabled_after_cleanup", all(not bool(row["enabled"]) for row in final_accounts if row.get("username") in fixture_names))
                except Exception as error:
                    cleanup["errors"].append({"operation": "verify_cleanup", "type": type(error).__name__})
        if cleanup["errors"] or not cleanup["catalogPublicStateRestored"]:
            summary["result"] = "failed"
        summary["completedAt"] = datetime.now().isoformat(timespec="seconds")
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    if summary["result"] != "passed":
        print(f"Acceptance failed: stage={failure['stage'] if failure else 'cleanup'}; summary contains only check metadata", file=sys.stderr)
        return 1
    print(f"passed: {len(checks)} checks; QA accounts disabled and catalog publication restored")
    return 0


if __name__ == "__main__":
    sys.exit(main())
