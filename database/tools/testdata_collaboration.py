"""Customer portal and production collaboration fixtures via local public APIs.

No model is invoked. Portal tokens stay only in memory and sessions are logged
out before completion; fixtures and reports contain no passwords or tokens.
"""
from __future__ import annotations

from datetime import timedelta
import json
from urllib.error import HTTPError
from urllib.request import Request, urlopen


SCENARIOS = [
    {"code": "PORTAL-ACCOUNTS", "name": "客户门户身份", "coverage": "本客户启用、其他客户启用及停用账户；独立登录、客户隔离、退出及停用拒绝"},
    {"code": "PORTAL-REQUESTS", "name": "客户订购申请", "coverage": "受理、拒绝、待处理；产品快照；提交幂等；客户仅可见本客户申请和工单"},
    {"code": "INSIGHT-INCIDENTS", "name": "异常闭环", "coverage": "6类异常；OPEN、IN_PROGRESS、VERIFYING、CLOSED；原因措施、验证证据、版本及事件历史"},
    {"code": "INSIGHT-RECURRENCE", "name": "异常复发与验证失败", "coverage": "已关闭复发重新处理中；待验证失败退回处理中；保留完整事件历史"},
    {"code": "INSIGHT-DRAFTS", "name": "建议转异常", "coverage": "保留草稿、转异常及重复转换幂等"},
    {"code": "INSIGHT-ASSISTANT", "name": "无模型业务问答", "coverage": "库存月度台账、分组追问、工单成本和风险、澄清流程；版本与证据持久化；不调用模型"},
]


def _stamp(value):
    return value.replace(microsecond=0).isoformat()


class _PortalApi:
    def __init__(self):
        self._token = None
        self.calls = 0

    def call(self, method, path, payload=None, *, expected=0):
        headers = {"Content-Type": "application/json", "tenant-id": "1"}
        if self._token:
            headers["X-Portal-Token"] = self._token
        request = Request("http://127.0.0.1:48080/app-api/mes/customer-portal" + path,
            method=method, headers=headers,
            data=None if payload is None else json.dumps(payload, ensure_ascii=False).encode("utf8"))
        try:
            with urlopen(request, timeout=60) as response:
                answer = json.load(response)
        except HTTPError as error:
            # Framework versions may return business errors as HTTP 200 or 4xx.
            answer = json.loads(error.read())
        self.calls += 1
        if answer.get("code") != expected:
            raise RuntimeError(f"Portal {method} {path}: expected code {expected}, got {answer.get('code')}: {answer.get('msg')}")
        return answer.get("data")

    def login(self, username):
        reply = self.call("POST", "/login", {
            "tenantId": 1, "username": username, "password": "Test@2026!"})
        self._token = reply["token"]
        return reply["account"]

    def logout(self):
        self.call("POST", "/logout")
        self._token = None


def seed_api(api, ctx):
    ids = ctx.ids
    now = ctx.now.replace(microsecond=0)
    product = ids.get("product", 900103)
    client = ids.get("client", 900001)
    all_clients = api.get("/mes/portal/clients")
    other_clients = [row for row in all_clients if row["id"] != client]
    if not other_clients:
        raise AssertionError("Customer-isolation fixtures require a second active synthetic client")
    other_client = other_clients[0]["id"]
    result = {"accounts": {}, "requests": {}, "incidents": {}, "drafts": {}, "conversations": {}, "checks": []}
    ids["collaboration"] = result

    for key, customer, enabled, label in [
        ("active", client, True, "教学客户采购员"),
        ("partner", other_client, True, "第二客户采购员"),
        ("disabled", client, False, "停用客户账号演练"),
    ]:
        account = api.call("POST", "/mes/portal/account/save", {
            "clientId": customer, "username": "tst.portal." + key, "displayName": label,
            "password": "Test@2026!", "enabled": enabled, "expireTime": _stamp(now + timedelta(days=365))})
        result["accounts"][key] = {"id": account, "username": "tst.portal." + key,
                                    "clientId": customer, "enabled": enabled}
    api.call("POST", "/mes/portal/catalog/save", {"productId": product, "enabled": True,
        "description": "TST 虚构传动组件；教学规格，按客户技术要求复核。参考售价为人民币未税报价，实际交期及价格以正式订单确认为准。"})

    primary, secondary = _PortalApi(), _PortalApi()
    primary.login("tst.portal.active")
    secondary.login("tst.portal.partner")
    public_catalog = primary.call("GET", "/catalog")
    assert any(row["productId"] == product for row in public_catalog), public_catalog
    sensitive = {"purchasePrice", "materialCost", "laborCost", "overheadCost", "totalCost", "unitCost", "vendorId"}
    assert not any(sensitive.intersection(row) for row in public_catalog), "Portal catalog exposes private fields"

    def request_case(portal, key, quantity, review=None):
        payload = {"requestKey": "TST-PORTAL-REQUEST-" + key,
            "deliveryDate": (now + timedelta(days=14 if review != "REJECTED" else 2)).date().isoformat(),
            "remark": "TST 合成客户订购演练；" + key,
            "items": [{"productId": product, "quantity": quantity}]}
        submitted = portal.call("POST", "/requests", payload)
        repeated = portal.call("POST", "/requests", payload)
        assert repeated["id"] == submitted["id"], "Portal request retry created a duplicate"
        if review:
            api.call("POST", "/mes/portal/request/review", {"id": submitted["id"], "status": review,
                "reviewNote": "TST 已受理，正式订单、报价及交期另行确认。" if review == "ACCEPTED"
                    else "TST 现有排程无法满足期望交期，请调整交期后重新申请。"})
        result["requests"][key] = {"id": submitted["id"], "code": submitted["code"],
                                    "quantity": quantity, "status": review or "PENDING"}
        return submitted["id"]

    accepted = request_case(primary, "ACCEPTED", 50, "ACCEPTED")
    rejected = request_case(primary, "REJECTED", 75, "REJECTED")
    pending = request_case(primary, "PENDING", 30)
    partner = request_case(secondary, "PARTNER", 10)
    primary_requests = primary.call("GET", "/requests")
    secondary_requests = secondary.call("GET", "/requests")
    primary_request_ids = {row["id"] for row in primary_requests}
    secondary_request_ids = {row["id"] for row in secondary_requests}
    assert {accepted, rejected, pending}.issubset(primary_request_ids), primary_requests
    assert partner in secondary_request_ids and not primary_request_ids.intersection(secondary_request_ids), "Portal request customer isolation failed"
    own_orders = primary.call("GET", "/orders")
    other_orders = secondary.call("GET", "/orders")
    main_order = ids["finished_order"]
    assert any(row["id"] == main_order for row in own_orders), "Main customer cannot see its completed manufacturing order"
    assert not any(row["id"] == main_order for row in other_orders), "Other customer can see main customer's order"
    result["checks"].extend(["portal_catalog_public_projection", "portal_request_idempotency", "portal_customer_request_isolation", "portal_customer_order_isolation"])
    primary.logout()
    secondary.logout()
    disabled = _PortalApi()
    disabled.call("POST", "/login", {"tenantId": 1, "username": "tst.portal.disabled", "password": "Test@2026!"}, expected=401)
    disabled.call("GET", "/orders", expected=401)
    result["portalApiCalls"] = primary.calls + secondary.calls + disabled.calls
    result["checks"].extend(["portal_disabled_account_login_rejected", "portal_anonymous_orders_rejected", "portal_sessions_logged_out"])

    orders = ids["manufacturing_orders"]
    cases = [
        ("MATERIAL-OPEN", "MATERIAL", "MFG-SHORTAGE", 980001, "OPEN", "原料采购交期尚待确认", "核对库存、采购在途和需求量，按物料逐项跟催", ""),
        ("DEVICE-WORKING", "DEVICE", "MFG-PROGRESS", 980007, "IN_PROGRESS", "工位夹具重复定位存在偏差", "检查夹具磨损并重新校准定位销，复核首件", ""),
        ("QUALITY-VERIFY", "QUALITY", "MFG-PROGRESS", 980005, "VERIFYING", "首件尺寸波动与量具归零操作不一致相关", "统一量具归零及首件复核步骤，复训操作员并确认SOP", "待追加连续三组首件尺寸复核记录"),
        ("DELIVERY-CLOSED", "DELIVERY", "MFG-COMPLETE", 980001, "CLOSED", "批次拆分交接时生产进度更新滞后", "按实际报工源分配核对已完成数量，完成产品入库并复核完工状态", "TST 合成验证：50件报工、产品入库与工单完工数量一致；实际成本已结算"),
        ("PROCESS-REOPEN", "PROCESS", "MFG-READY", 980003, "REOPEN", "工序交接标签填写不完整", "补充来源批次、数量和责任人，按SOP自检后交接", "TST 首轮抽查通过；后续发现同类遗漏，重新打开处理"),
        ("OTHER-REVERIFY", "OTHER", "MFG-APPROVING", 980004, "REVERIFY", "报工提交前复核项目未完整勾选", "补充提交前检查并明确审批责任人", "TST 验证未通过：复核项目仍有遗漏，退回继续处理"),
    ]

    def move(incident, version, target, owner, root, action, evidence, note):
        api.call("PUT", "/mes/insight/incident", {"id": incident, "version": version,
            "status": target, "ownerId": owner, "dueAt": _stamp(now + timedelta(days=3)),
            "rootCause": root, "correctiveAction": action, "evidence": evidence, "note": note})
        return version + 1

    for key, category, order_key, owner, target, root, action, evidence in cases:
        incident = api.call("POST", "/mes/insight/incident", {
            "title": "TST-异常-" + key, "category": category, "workOrderId": orders[order_key],
            "workstationId": 900001 if category != "MATERIAL" else None, "ownerId": owner,
            "dueAt": _stamp(now + timedelta(days=-1 if target == "OPEN" else 3)),
            "description": "TST 合成异常演练；" + root + "。此记录只用于测试，不表示真实设备或质量事件。",
            "departmentId": 980003 if category == "QUALITY" else 980004 if category == "DEVICE" else 980001})
        version = 0
        if target != "OPEN":
            version = move(incident, version, "IN_PROGRESS", owner, root, action, "", "TST 指定责任人并开始排查")
        if target in {"VERIFYING", "CLOSED", "REOPEN", "REVERIFY"}:
            version = move(incident, version, "VERIFYING", owner, root, action, evidence,
                           "TST 原因及措施已确认，提交验证")
        if target in {"CLOSED", "REOPEN"}:
            version = move(incident, version, "CLOSED", owner, root, action, evidence,
                           "TST 验证通过，登记证据并关闭")
        if target in {"REOPEN", "REVERIFY"}:
            version = move(incident, version, "IN_PROGRESS", owner, root, action, evidence,
                           "TST 复发重新处理" if target == "REOPEN" else "TST 验证不通过，退回继续处理")
        result["incidents"][key] = {"id": incident, "version": version,
            "status": "IN_PROGRESS" if target in {"REOPEN", "REVERIFY"} else target}
        history = api.call("GET", "/mes/insight/incident-history", id=incident)
        assert len(history) == version + 1, (key, history)

    for key, title, content, convert in [
        ("MATERIAL-DRAFT", "TST-建议-缺料跟催清单", "TST 建议核对12000件计划需求、原料库存和供应商承诺交期，保留为草稿待计划员复核。", False),
        ("FLOW-CONVERT", "TST-建议-工序交接规范", "TST 建议按报工来源与实物标签复核分拆合并关系，明确交接责任人及每日核对节奏。", True),
    ]:
        draft = api.call("POST", "/mes/insight/draft", {"title": title, "content": content,
            "workOrderId": orders["MFG-SHORTAGE" if not convert else "MFG-READY"]})
        result["drafts"][key] = {"id": draft, "status": "DRAFT"}
        if convert:
            converted = api.call("POST", "/mes/insight/draft-convert", id=draft)
            repeated = api.call("POST", "/mes/insight/draft-convert", id=draft)
            assert converted == repeated, "Insight draft conversion is not idempotent"
            result["drafts"][key].update(status="CONVERTED", incidentId=converted)

    # Stateful business questions persist facts and evidence without using an AI key.
    def conversation(key, title, questions):
        created = api.call("POST", "/mes/insight/conversation/create", {"title": title})
        cid, version = created["id"], created["version"]
        turns = []
        for question in questions:
            answer = api.call("POST", "/mes/insight/assistant", {
                "conversationId": cid, "version": version, "question": question, "useModel": False})
            assert answer["conversationId"] == cid and answer["version"] == version + 1, answer
            assert answer.get("mode") != "MODEL", "Synthetic business Q&A unexpectedly used a model"
            version = answer["version"]
            turns.append({"id": answer["turnId"], "version": version, "status": answer.get("status"),
                          "topic": answer.get("topic"), "mode": answer.get("mode")})
            api.call("GET", "/mes/insight/conversation/evidence", turnId=answer["turnId"], pageNo=1, pageSize=20)
        persisted = api.call("GET", "/mes/insight/conversation/get", id=cid, pageSize=100)
        assert persisted["conversation"]["turnCount"] == len(questions), persisted
        result["conversations"][key] = {"id": cid, "version": version, "turns": turns}
        return cid

    conversation("INVENTORY", "TST·库存台账与分组追问", [f"查询{now.year}年{now.month}月库存台账，按物料", "按仓库"])
    conversation("COST", "TST·工单实际成本核验", ["查询工单 TST-MFG-COMPLETE 的成本", "查询工单 TST-MFG-PROGRESS 的成本"])
    conversation("RISKS", "TST·缺料风险及异常处置", ["查询工单 TST-MFG-SHORTAGE 的缺料风险", "查询异常负责人和处置状态"])
    conversation("CLARIFICATION", "TST·库存查询缺少年份澄清", [f"查询{now.month}月库存台账", f"{now.year}年"])
    result["checks"].extend(["insight_incident_transition_history", "insight_draft_conversion_idempotency", "insight_business_conversation_versions", "insight_rule_only_no_model"])
    ctx.api_evidence.append({"check": "customer_portal_public_api", "calls": result["portalApiCalls"],
                             "sessionsLoggedOut": True, "secretsPersistedInReport": False})
    return result


def validation(ctx):
    return [
        ("portal:account_portfolio", """SELECT ABS(COUNT(*)-3)+ABS(SUM(enabled=1)-2)+ABS(SUM(enabled=0)-1)
            FROM mes_portal_account WHERE deleted=0 AND username LIKE 'tst.portal.%'"""),
        ("portal:password_hash_only", """SELECT COUNT(*) FROM mes_portal_account
            WHERE deleted=0 AND username LIKE 'tst.portal.%' AND
                (LEFT(password_hash,4) NOT IN('$2a$','$2b$','$2y$') OR LENGTH(password_hash)<>60)"""),
        ("portal:session_hashes_revoked", """SELECT COUNT(*) FROM mes_portal_session s
            JOIN mes_portal_account a ON a.id=s.account_id AND a.tenant_id=s.tenant_id
            WHERE a.username LIKE 'tst.portal.%' AND (s.revoked<>1 OR s.token_hash NOT REGEXP '^[0-9a-f]{64}$')"""),
        ("portal:request_states", """SELECT ABS(COUNT(*)-4)+ABS(SUM(status='ACCEPTED')-1)
            +ABS(SUM(status='REJECTED')-1)+ABS(SUM(status='PENDING')-2)
            FROM mes_portal_request WHERE request_key LIKE 'TST-PORTAL-REQUEST-%'"""),
        ("portal:request_account_customer", """SELECT COUNT(*) FROM mes_portal_request r
            LEFT JOIN mes_portal_account a ON a.id=r.account_id AND a.tenant_id=r.tenant_id AND a.deleted=0
            WHERE r.request_key LIKE 'TST-PORTAL-REQUEST-%' AND (a.id IS NULL OR a.client_id<>r.client_id)"""),
        ("portal:review_evidence", """SELECT COUNT(*) FROM mes_portal_request
            WHERE request_key LIKE 'TST-PORTAL-REQUEST-%' AND status IN('ACCEPTED','REJECTED')
                AND (review_time IS NULL OR reviewer_id IS NULL OR review_note='')"""),
        ("portal:request_line_snapshots", """SELECT COUNT(*) FROM mes_portal_request_line l
            LEFT JOIN mes_portal_request r ON r.id=l.request_id AND r.tenant_id=l.tenant_id
            LEFT JOIN mes_md_item i ON i.id=l.product_id AND i.tenant_id=l.tenant_id AND i.deleted=0
            WHERE r.id IS NULL OR i.id IS NULL OR l.quantity<=0 OR l.product_code='' OR l.product_name=''"""),
        ("insight:scenario_statuses", """SELECT ABS(COUNT(*)-6)+ABS(SUM(status='OPEN')-1)
            +ABS(SUM(status='IN_PROGRESS')-3)+ABS(SUM(status='VERIFYING')-1)+ABS(SUM(status='CLOSED')-1)
            FROM mes_insight_incident WHERE deleted=0 AND title LIKE 'TST-异常-%'"""),
        ("insight:version_event_count", """SELECT COUNT(*) FROM mes_insight_incident i
            WHERE i.deleted=0 AND i.title LIKE 'TST-%' AND i.version+1<>(
                SELECT COUNT(*) FROM mes_insight_incident_event e WHERE e.incident_id=i.id AND e.tenant_id=i.tenant_id)"""),
        ("insight:closure_evidence", """SELECT COUNT(*) FROM mes_insight_incident WHERE deleted=0
            AND title LIKE 'TST-%' AND ((status='CLOSED' AND (closed_at IS NULL OR COALESCE(evidence,'')=''))
                OR (status IN('VERIFYING','CLOSED') AND (COALESCE(root_cause,'')='' OR COALESCE(corrective_action,'')=''))
                OR (status<>'CLOSED' AND closed_at IS NOT NULL))"""),
        ("insight:event_ownership", """SELECT COUNT(*) FROM mes_insight_incident_event e
            LEFT JOIN mes_insight_incident i ON i.id=e.incident_id AND i.tenant_id=e.tenant_id AND i.deleted=0
            LEFT JOIN system_users u ON u.id=e.actor_id AND u.tenant_id=e.tenant_id AND u.deleted=0
            WHERE i.id IS NULL OR u.id IS NULL OR e.note=''"""),
        ("insight:draft_conversion", """SELECT COUNT(*) FROM mes_insight_action_draft d
            LEFT JOIN mes_insight_incident i ON i.id=d.incident_id AND i.tenant_id=d.tenant_id AND i.deleted=0
            WHERE d.title LIKE 'TST-建议-%' AND ((d.status='CONVERTED' AND i.id IS NULL)
                OR (d.status='DRAFT' AND d.incident_id IS NOT NULL))"""),
        ("insight:conversation_portfolio", """SELECT ABS(COUNT(*)-4)+ABS(SUM(turn_count)-8)
            FROM mes_insight_conversation WHERE title LIKE 'TST·%'"""),
        ("insight:conversation_version_count", """SELECT COUNT(*) FROM mes_insight_conversation c
            WHERE c.title LIKE 'TST·%' AND (c.version<>c.turn_count OR c.processing_token IS NOT NULL
                OR c.turn_count<>(SELECT COUNT(*) FROM mes_insight_turn t WHERE t.conversation_id=c.id
                    AND t.tenant_id=c.tenant_id AND t.user_id=c.user_id))"""),
        ("insight:turn_evidence_ownership", """SELECT COUNT(*) FROM mes_insight_turn t
            LEFT JOIN mes_insight_conversation c ON c.id=t.conversation_id AND c.tenant_id=t.tenant_id AND c.user_id=t.user_id
            LEFT JOIN mes_insight_evidence e ON e.turn_id=t.id AND e.conversation_id=t.conversation_id
                AND e.tenant_id=t.tenant_id AND e.user_id=t.user_id
            WHERE c.id IS NULL OR e.turn_id IS NULL OR JSON_VALID(t.answer_json)<>1 OR JSON_VALID(e.evidence_json)<>1"""),
        ("insight:no_model_calls", """SELECT COUNT(*) FROM mes_insight_turn t JOIN mes_insight_conversation c
            ON c.id=t.conversation_id AND c.tenant_id=t.tenant_id AND c.user_id=t.user_id
            WHERE c.title LIKE 'TST·%' AND JSON_UNQUOTE(JSON_EXTRACT(t.answer_json,'$.mode'))='MODEL'"""),
    ]
