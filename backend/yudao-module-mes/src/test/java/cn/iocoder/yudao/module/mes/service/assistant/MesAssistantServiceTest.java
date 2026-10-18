package cn.iocoder.yudao.module.mes.service.assistant;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class MesAssistantServiceTest extends MesAssistantTestDatabase {
    @Test void realUploadDraftPublicationSearchAndDownloadPreserveExactSource() {
        long base = service.saveBase(null, "设备规范", "经审核的点检与维护要求", 10);
        String text = "# 点检异常\n发现设备异常必须停机，通知维修人员并填写点检记录。\n# 安全操作\n维修前确认断电挂牌。";
        long document = upload(base, "点检规范", text);
        assertTrue(items(service.search("点检异常", List.of(base), 5)).isEmpty());
        assertTrue(service.documents(base, false).isEmpty());
        assertThrows(ServiceException.class, () -> service.document(document, false));
        assertEquals(2, service.document(document, true).get("chunkCount"));
        service.publish(document, true);
        var result = service.search("设备点检异常", List.of(base), 5);
        assertEquals("lexical_bm25", result.get("mode"));
        var hits = items(result); assertFalse(hits.isEmpty());
        assertEquals(document, ((Number) hits.get(0).get("documentId")).longValue());
        assertEquals("点检异常", hits.get(0).get("heading"));
        assertTrue(hits.get(0).get("content").toString().contains("必须停机"));
        assertArrayEquals(text.getBytes(StandardCharsets.UTF_8), service.download(document, false).bytes());
        var detail = service.document(document, false);
        for (var hit : hits) {
            assertEquals(hit.get("content"), detail.get("contentText").toString().substring(((Number) hit.get("startOffset")).intValue(), ((Number) hit.get("endOffset")).intValue()));
            assertEquals(MesAssistantDocumentParser.sha256(hit.get("content").toString().getBytes(StandardCharsets.UTF_8)), hit.get("contentHash"));
        }
        assertTrue(items(service.search("zebraunrelatedtoken", List.of(base), 5)).isEmpty());
        service.publish(document, false);
        assertTrue(items(service.search("点检异常", List.of(base), 5)).isEmpty());
    }

    @Test void tenantAndDeletionConstraintsApplyToEveryJoinedKnowledgeTable() {
        long localBase = service.saveBase(null, "本租户", "", 10);
        long localDoc = upload(localBase, "本地规则", "设备点检异常必须记录"); service.publish(localDoc, true);
        TenantContextHolder.setTenantId(2L);
        long foreignBase = service.saveBase(null, "其他租户", "", 20);
        long foreignDoc = upload(foreignBase, "其他租户秘密", "设备点检异常不得外泄"); service.publish(foreignDoc, true);
        TenantContextHolder.setTenantId(1L);
        assertEquals(1, items(service.search("点检异常", List.of(), 5)).size());
        assertThrows(ServiceException.class, () -> service.document(foreignDoc, true));
        assertThrows(ServiceException.class, () -> service.download(foreignDoc, true));
        assertThrows(ServiceException.class, () -> service.documents(foreignBase, true));
        assertThrows(ServiceException.class, () -> service.search("点检", List.of(foreignBase), 5));
        assertThrows(ServiceException.class, () -> service.publish(foreignDoc, true));
        assertThrows(ServiceException.class, () -> service.deleteDocument(foreignDoc));
        jdbc.update("INSERT INTO mes_assistant_chunk(document_id,chunk_index,heading,content_text,content_sha256,tenant_id) VALUES(?,99,'伪造关联','点检异常','fake',2)", localDoc);
        assertEquals(1, items(service.search("点检异常", List.of(), 5)).size());
        jdbc.update("UPDATE mes_assistant_chunk SET deleted=1 WHERE tenant_id=1");
        assertTrue(items(service.search("点检异常", List.of(), 5)).isEmpty());
        jdbc.update("UPDATE mes_assistant_chunk SET deleted=0 WHERE tenant_id=1");
        jdbc.update("UPDATE mes_assistant_document SET deleted=1 WHERE tenant_id=1");
        assertTrue(items(service.search("点检异常", List.of(), 5)).isEmpty());
        jdbc.update("UPDATE mes_assistant_document SET deleted=0 WHERE tenant_id=1");
        jdbc.update("UPDATE mes_assistant_knowledge_base SET deleted=1 WHERE tenant_id=1");
        assertTrue(items(service.search("点检异常", List.of(), 5)).isEmpty());
    }

    @Test void userOwnedSessionsNeverInventAnAssistantReplyWhenNoModelIsConfigured() {
        long base = service.saveBase(null, "质量", "", 10);
        long document = upload(base, "首检", "首件检验合格后方可放行生产。"); service.publish(document, true);
        long session = service.createSession(null, 10);
        var response = service.send(session, "首件检验放行要求？", List.of(base), 10);
        assertEquals("MODEL_NOT_CONFIGURED", response.get("status"));
        assertNull(response.get("answer")); assertNull(response.get("assistantMessageId"));
        assertFalse(itemsFromCitations(response).isEmpty());
        var history = messages(service.session(session, 10));
        assertEquals(1, history.size()); assertEquals("user", history.get(0).get("role"));
        assertEquals("MODEL_NOT_CONFIGURED", history.get(0).get("status"));
        assertTrue(service.sessions(20).isEmpty());
        assertThrows(ServiceException.class, () -> service.session(session, 20));
        assertThrows(ServiceException.class, () -> service.send(session, "首件", List.of(base), 20));
        assertThrows(ServiceException.class, () -> service.deleteSession(session, 20));
        TenantContextHolder.setTenantId(2L);
        assertThrows(ServiceException.class, () -> service.session(session, 10));
        TenantContextHolder.setTenantId(1L);
        service.renameSession(session, "质量依据", 10); assertEquals("质量依据", service.session(session, 10).get("title"));
        service.deleteDocument(document);
        assertThrows(ServiceException.class, () -> service.document(document, true));
        assertFalse(messages(service.session(session, 10)).get(0).get("citations").toString().isBlank());
        service.deleteSession(session, 10); assertThrows(ServiceException.class, () -> service.session(session, 10));
    }

    @Test void inputValidationAndParameterizedSearchDoNotMutateKnowledge() {
        long base = service.saveBase(null, "工艺", "", 10);
        long document = upload(base, "确认", "工艺确认后生产"); service.publish(document, true);
        assertThrows(ServiceException.class, () -> service.deleteBase(base));
        assertThrows(ServiceException.class, () -> service.upload(base, "异常", "bad.exe", new byte[]{1,2}, 10));
        assertThrows(ServiceException.class, () -> service.search("", List.of(), 5));
        assertThrows(ServiceException.class, () -> service.search("工艺", List.of(), 11));
        assertThrows(ServiceException.class, () -> service.search("工艺", java.util.Arrays.asList((Long) null), 5));
        var before = jdbc.queryForList("SELECT id,file_sha256,status FROM mes_assistant_document");
        assertTrue(items(service.search("' OR 1=1 --", List.of(base), 5)).isEmpty());
        assertEquals(before, jdbc.queryForList("SELECT id,file_sha256,status FROM mes_assistant_document"));
        TenantContextHolder.clear();
        assertThrows(NullPointerException.class, () -> service.search("工艺", List.of(), 5));
        assertThrows(NullPointerException.class, models::status);
    }

    private long upload(long base, String title, String text) { return ((Number) service.upload(base, title, "规范.md", text.getBytes(StandardCharsets.UTF_8), 10).get("id")).longValue(); }
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> items(Map<String,Object> response) { return (List<Map<String,Object>>) response.get("items"); }
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> itemsFromCitations(Map<String,Object> response) { return (List<Map<String,Object>>) response.get("citations"); }
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> messages(Map<String,Object> response) { return (List<Map<String,Object>>) response.get("messages"); }
}
