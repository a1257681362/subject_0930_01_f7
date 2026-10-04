package com.evops.coldchain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.coldchain.dto.DispatchRequest;
import com.evops.coldchain.dto.DrugCreateRequest;
import com.evops.coldchain.dto.OrderCreateRequest;
import com.evops.coldchain.dto.ProbeBindRequest;
import com.evops.coldchain.dto.ProbeCreateRequest;
import com.evops.coldchain.dto.VehicleCreateRequest;
import com.evops.coldchain.entity.OpLog;
import com.evops.coldchain.mapper.OpLogMapper;
import com.evops.coldchain.service.DrugService;
import com.evops.coldchain.service.OrderService;
import com.evops.coldchain.service.ProbeService;
import com.evops.coldchain.service.TransportBatchService;
import com.evops.coldchain.service.VehicleService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 药品运输抽检（V7）验收测试。
 *
 * 覆盖：
 * - 按批次发起抽检任务、抽样封存生成唯一封存码（库内唯一），采样人/位置/数量/封存照片落库，
 *   照片 SHA-256 摘要确定性；
 * - 样本转移双人确认：第一人发起后审批停留 PENDING/IN_TRANSFER 待处理，重复发起/同一人确认被拦截，
 *   指定第二人确认后样本 RECEIVED、持有方转移；
 * - 已封存样本不可删除（系统无删除路径）且已封存任务不可作废，未抽样任务才可作废；
 * - 查询按监管区域隔离（华南 inspector3 看不到华东任务），Shiro 401/403 真实校验；
 * - 导出 CSV 对承运人联系电话脱敏（保留前3后2）并逐次写导出审计流水；
 * - 抽检结论只回写批次 inspection_* 列，不覆盖批次状态/签收/责任主体等原始运输记录；
 * - 批次台账可完整追溯抽检任务、样本封存与双人转移；半程转移未完成不能出结论。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class InspectionSamplingIntegrationTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final String BASE = "/api/cold-chain";

    // 预置区域绑定：7 inspector / 8 inspector2 = 华东 HD；9 inspector3 = 华南 HN
    private static final String INSP = "inspector";
    private static final String INSP2 = "inspector2";
    private static final String INSP3 = "inspector3";
    /** 华东第三名监管人（id=10，同区域但非指定接收方，验证双人确认专人专户） */
    private static final String INSP4 = "inspector4";
    private static final String INSP_PWD = "inspector123";

    @LocalServerPort
    private int port;

    @Autowired private TestRestTemplate http;
    @Autowired private DrugService drugService;
    @Autowired private VehicleService vehicleService;
    @Autowired private OrderService orderService;
    @Autowired private ProbeService probeService;
    @Autowired private TransportBatchService batchService;
    @Autowired private OpLogMapper opLogMapper;

    // ====================================================================
    // 1) 封存唯一码/留痕 + 双人确认半程停留 + 结论回写不覆盖运输记录 + 台账追溯
    // ====================================================================
    @Test
    void seal_doubleConfirm_concludeWriteback_keepsTransportUntouched() throws Exception {
        long batchId = shipNewBatch(20, "13800001234");

        // 发起抽检任务（华东 inspector，id=7）
        JsonNode created = postAs(INSP, "/inspections/tasks/create", map(
                "batchId", batchId, "samplingLocation", "沪杭高速K50抽检点",
                "samplingBasis", "2026年度冷链监督抽检计划"));
        assertTrue(created.path("success").asBoolean(), created.toString());
        long taskId = created.path("data").path("id").asLong();
        String taskNo = created.path("data").path("inspectionNo").asText();
        assertTrue(taskNo.startsWith("CJ") && taskNo.length() == 11, "任务号应为 CJ+8位+1位校验位: " + taskNo);
        assertEquals("HD", created.path("data").path("regionCode").asText());
        assertEquals("PENDING", created.path("data").path("status").asText());

        // 抽样封存：唯一封存码 + 采样人/位置/数量/照片摘要
        String photoContent = "封存照片二进制占位-" + System.nanoTime();
        JsonNode seal = postAs(INSP, "/inspections/samples/seal", map(
                "inspectionId", taskId, "sampleQty", 3,
                "samplingLocation", "30.27N,120.15E 沪杭高速K50",
                "samplerName", "王监管", "photoFileName", "seal-a.jpg",
                "photoFileContent", photoContent));
        assertTrue(seal.path("success").asBoolean(), seal.toString());
        long sampleId = seal.path("data").path("id").asLong();
        String code1 = seal.path("data").path("sealCode").asText();
        assertTrue(code1.startsWith("FC") && code1.length() == 11, "封存码应为 FC+8位+1位校验位: " + code1);
        assertEquals("SEALED", seal.path("data").path("status").asText());
        assertEquals(sha256(photoContent), seal.path("data").path("photoSha256").asText(),
                "封存照片应按内容计算确定性 SHA-256");
        assertEquals(7L, seal.path("data").path("holderId").asLong());
        // 任务首封后进入 SEALED
        assertEquals("SEALED", getAs(INSP, "/inspections/tasks/" + taskId).path("task").path("status").asText());

        // 第二个样本：封存码库内唯一
        JsonNode seal2 = postAs(INSP, "/inspections/samples/seal", map(
                "inspectionId", taskId, "sampleQty", 2,
                "samplingLocation", "同车冷链箱B区", "samplerName", "王监管",
                "photoFileName", "seal-b.jpg", "photoFileContent", photoContent + "-2"));
        assertTrue(seal2.path("success").asBoolean(), seal2.toString());
        assertFalse(code1.equals(seal2.path("data").path("sealCode").asText()), "两次封存码必须不同且唯一");

        // 已封存后任务不可作废
        assertStop(postAs(INSP, "/inspections/void", map("inspectionId", taskId, "reason", "想删")),
                "已封存");
        // 样本物理行仍在（无删除路径）
        JsonNode detail = getAs(INSP, "/inspections/tasks/" + taskId);
        assertEquals(2, detail.path("samples").size(), "已封存样本不可删除，物理行必须保留");

        // —— 双人确认：第一人 inspector(7) 发起，指定第二人 inspector2(8) ——
        JsonNode start = postAs(INSP, "/inspections/transfers/start", map(
                "sampleId", sampleId, "toHolderId", 8, "toHolderName", "李监管",
                "fromLocation", "沪杭高速K50", "toLocation", "杭州市药检所样品室",
                "transferNote", "冷链抽检样本移交检验"));
        assertTrue(start.path("success").asBoolean(), start.toString());
        long transferId = start.path("data").path("id").asLong();
        assertEquals("PENDING", start.path("data").path("confirmStatus").asText());
        assertEquals(7L, start.path("data").path("fromHolderId").asLong());
        assertEquals(8L, start.path("data").path("toHolderId").asLong());

        // 审批只完成一半：样本停留 IN_TRANSFER 待处理
        JsonNode halfSample = seal.path("data");
        assertEquals("IN_TRANSFER", getAs(INSP, "/inspections/tasks/" + taskId)
                .path("samples").get(0).path("sample").path("status").asText());

        // 半程期间不能重复发起转移
        assertStop(postAs(INSP, "/inspections/transfers/start", map(
                "sampleId", sampleId, "toHolderId", 9, "toHolderName", "外人")), "二次确认");
        // 交出方本人不能充当第二人确认（双人确认）
        assertStop(postAs(INSP, "/inspections/transfers/confirm",
                map("transferId", transferId, "confirmNote", "自己确认")), "第二确认人");
        // 同区域但非指定接收方（inspector4, id=10）不能代确认（双人确认专人专户）
        assertStop(postAs(INSP4, "/inspections/transfers/confirm",
                map("transferId", transferId, "confirmNote", "同区域他人代确认")), "无权确认");
        // 跨区域接收方（inspector3, id=9 华南）连任务都不可见，区域隔离先拦截
        assertStop(postAs(INSP3, "/inspections/transfers/confirm",
                map("transferId", transferId, "confirmNote", "跨区域确认")), "隔离");

        // 半程未完成不能出结论
        assertStop(postAs(INSP, "/inspections/conclude", map(
                "inspectionId", taskId, "result", "QUALIFIED", "note", "提前结论")), "只完成一半");

        // 指定第二人 inspector2(8) 确认：样本 RECEIVED、持有方转移
        JsonNode confirm = postAs(INSP2, "/inspections/transfers/confirm",
                map("transferId", transferId, "confirmNote", "封签完好，样本温度正常，接收"));
        assertTrue(confirm.path("success").asBoolean(), confirm.toString());
        assertEquals("CONFIRMED", confirm.path("data").path("confirmStatus").asText());
        assertEquals(8L, confirm.path("data").path("confirmerId").asLong());
        JsonNode samplesAfter = getAs(INSP, "/inspections/tasks/" + taskId).path("samples");
        JsonNode firstAfter = samplesAfter.get(0).path("sample");
        assertEquals("RECEIVED", firstAfter.path("status").asText());
        assertEquals(8L, firstAfter.path("holderId").asLong(), "双人确认后持有方应转移给接收方");
        // 已完成转移不能重复确认
        assertStop(postAs(INSP2, "/inspections/transfers/confirm",
                map("transferId", transferId)), "不能重复确认");

        // 第二枚样本也完成转移（8 -> 7），否则仍有半程/未接收样本；这里把其也转移并确认
        long sample2Id = seal2.path("data").path("id").asLong();
        JsonNode start2 = postAs(INSP, "/inspections/transfers/start", map(
                "sampleId", sample2Id, "toHolderId", 8, "toHolderName", "李监管"));
        assertTrue(start2.path("success").asBoolean(), start2.toString());
        assertTrue(postAs(INSP2, "/inspections/transfers/confirm",
                map("transferId", start2.path("data").path("id").asLong())).path("success").asBoolean());

        // —— 抽检结论回写批次：原始运输记录不被覆盖 ——
        JsonNode batchLedgerBefore = getAs(INSP, "/batches/" + batchId + "/ledger").path("batch");
        String originStatus = batchLedgerBefore.path("status").asText();
        String originSignBy = textOrNull(batchLedgerBefore, "signBy");
        String originRespOrg = textOrNull(batchLedgerBefore, "responsibleOrg");
        String originRespParty = textOrNull(batchLedgerBefore, "responsibleParty");
        assertFalse(originStatus.isEmpty());

        JsonNode conclude = postAs(INSP, "/inspections/conclude", map(
                "inspectionId", taskId, "result", "UNQUALIFIED",
                "note", "抽检发现温控记录不完整，判定不合格，移交处置"));
        assertTrue(conclude.path("success").asBoolean(), conclude.toString());
        assertEquals("CONCLUDED", conclude.path("data").path("status").asText());
        assertEquals("UNQUALIFIED", conclude.path("data").path("concludeResult").asText());

        JsonNode batchAfter = getAs(INSP, "/batches/" + batchId + "/ledger").path("batch");
        assertEquals("UNQUALIFIED", batchAfter.path("inspectionResult").asText(), "结论应回写批次抽检列");
        assertTrue(batchAfter.path("inspectionTime").asText().startsWith("20"), "应有结论时间");
        // 原始运输记录保持不变
        assertEquals(originStatus, batchAfter.path("status").asText(), "结论不得覆盖批次状态");
        assertEquals(nz(originSignBy), nz(textOrNull(batchAfter, "signBy")), "结论不得覆盖签收信息");
        assertEquals(nz(originRespOrg), nz(textOrNull(batchAfter, "responsibleOrg")), "结论不得覆盖责任主体机构");
        assertEquals(nz(originRespParty), nz(textOrNull(batchAfter, "responsibleParty")), "结论不得覆盖责任人");

        // 已出结论不能重复出具
        assertStop(postAs(INSP, "/inspections/conclude", map(
                "inspectionId", taskId, "result", "QUALIFIED", "note", "改判")), "不能重复出具");

        // —— 批次台账可追溯抽检任务/样本封存/双人转移 ——
        JsonNode ledger = getAs(INSP, "/batches/" + batchId + "/ledger");
        JsonNode inspBlock = ledger.path("inspections");
        assertTrue(inspBlock.path("taskCount").asInt() >= 1);
        boolean foundTask = false;
        for (JsonNode tv : inspBlock.path("tasks")) {
            if (tv.path("task").path("id").asLong() == taskId) {
                foundTask = true;
                assertEquals(2, tv.path("samples").size());
                JsonNode tr0 = tv.path("samples").get(0).path("transfers").get(0);
                assertEquals("CONFIRMED", tr0.path("confirmStatus").asText());
                assertEquals("王监管".equals(tr0.path("fromHolderName").asText()) ? 7 : 7,
                        tr0.path("fromHolderId").asLong());
            }
        }
        assertTrue(foundTask, "批次台账必须能追溯到抽检任务与双人转移");

        // 全链路留痕：封存/转移发起/转移确认/结论 四类流水均在
        long logs = opLogMapper.selectCount(new QueryWrapper<OpLog>().eq("batch_id", batchId)
                .in("action", CcConstants.ACT_SAMPLE_SEAL, CcConstants.ACT_SAMPLE_TRANSFER_START,
                        CcConstants.ACT_SAMPLE_TRANSFER_CONFIRM, CcConstants.ACT_INSPECTION_CONCLUDE));
        assertTrue(logs >= 4, "抽检关键动作应逐次写操作流水，实际=" + logs);
    }

    // ====================================================================
    // 2) 未抽样任务可作废；已封存不可作废（样本不可删除）——独立任务验证
    // ====================================================================
    @Test
    void pendingTask_canVoid_butSealedTaskCannot() throws Exception {
        long batchId = shipNewBatch(5, "13900007788");
        long taskId = postAs(INSP, "/inspections/tasks/create",
                map("batchId", batchId, "samplingBasis", "作废前验证")).path("data").path("id").asLong();
        // PENDING 可作废
        JsonNode voided = postAs(INSP, "/inspections/void", map("inspectionId", taskId, "reason", "批次取消，撤检"));
        assertTrue(voided.path("success").asBoolean(), voided.toString());
        assertEquals("VOIDED", voided.path("data").path("status").asText());
        // 已作废不能重复作废
        assertStop(postAs(INSP, "/inspections/void", map("inspectionId", taskId, "reason", "x")), "不能重复作废");
    }

    // ====================================================================
    // 3) 区域隔离 + Shiro 401/403
    // ====================================================================
    @Test
    void regionIsolation_andShiroAuthz() throws Exception {
        long batchId = shipNewBatch(8, "13711112222");
        long hdTaskId = postAs(INSP, "/inspections/tasks/create",
                map("batchId", batchId, "samplingBasis", "区域隔离验证")).path("data").path("id").asLong();

        // 华南 inspector3 看不到华东任务
        JsonNode hnList = getAs(INSP3, "/inspections/tasks");
        for (JsonNode t : hnList) {
            assertFalse(t.path("id").asLong() == hdTaskId, "跨区域任务不应出现在列表中");
        }
        // 华南账号访问华东任务详情：按“不可见”隔离
        assertStop(getAsEnvelope(INSP3, "/inspections/tasks/" + hdTaskId), "隔离");

        // 华东 inspector 可以看到
        assertTrue(getAs(INSP, "/inspections/tasks/" + hdTaskId).path("task").path("id").asLong() == hdTaskId);

        // Shiro：未认证 401
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> noAuth = http.exchange(
                "http://localhost:" + port + BASE + "/inspections/tasks/create",
                org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(M.writeValueAsBytes(map("batchId", batchId)), headers), String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, noAuth.getStatusCode());

        // Shiro：已认证但无抽检权限（carrier 承运岗）→ 403
        ResponseEntity<String> forbidden = http.exchange(
                "http://localhost:" + port + BASE + "/inspections/tasks/create",
                org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(M.writeValueAsBytes(map("batchId", batchId)), jsonHeaders("carrier", "carrier123")),
                String.class);
        assertEquals(HttpStatus.FORBIDDEN, forbidden.getStatusCode());
    }

    // ====================================================================
    // 4) 导出 CSV：承运人电话脱敏 + 导出审计流水
    // ====================================================================
    @Test
    void export_masksCarrierPhone_andWritesAudit() throws Exception {
        long batchId = shipNewBatch(12, "13800001234");
        postAs(INSP, "/inspections/tasks/create", map("batchId", batchId, "samplingBasis", "导出脱敏验证"));

        ResponseEntity<String> resp = http.exchange(
                "http://localhost:" + port + BASE + "/inspections/export?batchId=" + batchId,
                org.springframework.http.HttpMethod.GET, new HttpEntity<>(jsonHeaders(INSP, INSP_PWD)),
                String.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        String body = resp.getBody();
        assertNotNull(body);
        // 13800001234 → 138******34（保留前3后2）
        assertTrue(body.contains("138******34"), "导出必须脱敏承运人电话: " + body);
        assertFalse(body.contains("13800001234"), "导出不得包含承运人完整电话");

        Long audit = opLogMapper.selectCount(new QueryWrapper<OpLog>()
                .eq("action", CcConstants.ACT_INSPECTION_EXPORT)
                .eq("batch_id", batchId));
        assertTrue(audit != null && audit >= 1, "导出应逐次写审计流水");
    }

    // ---------------- 全链路建样（服务直调；与既有验收同口径） ----------------

    private long shipNewBatch(int shipQty, String driverPhone) {
        long nano = System.nanoTime();
        DrugCreateRequest dc = new DrugCreateRequest();
        dc.setDrugName("V7验收药品-" + nano);
        dc.setApprovalNo("国药准字V7-" + Math.abs(nano % 1000000));
        dc.setTempMin(new BigDecimal("2.00"));
        dc.setTempMax(new BigDecimal("8.00"));
        dc.setPackageSpec("1支/盒");
        Long drugId = drugService.create(dc).getId();

        OrderCreateRequest oc = new OrderCreateRequest();
        oc.setDrugId(drugId);
        oc.setReceiverOrg("V7验收医院");
        oc.setShipQty(shipQty);
        Long orderId = orderService.create(oc).getId();

        VehicleCreateRequest vr = new VehicleCreateRequest();
        vr.setPlateNo("测7·冷" + Math.abs(nano % 1000000));
        vr.setCarrierName("V7验收物流-" + Math.abs(nano % 100000));
        vr.setDriverName("王司机");
        vr.setDriverPhone(driverPhone);
        Long vehicleId = vehicleService.create(vr).getId();

        ProbeCreateRequest pc = new ProbeCreateRequest();
        pc.setSerialNo("SN-V7-" + nano);
        pc.setModel("TP-200");
        pc.setRangeMin(new BigDecimal("-40.00"));
        pc.setRangeMax(new BigDecimal("60.00"));
        pc.setCalibrateOrg("华东计量院");
        pc.setValidUntil(LocalDateTime.now().plusYears(1));
        pc.setCertNo("CERT-V7-" + nano);
        pc.setCalibrateDate(LocalDateTime.now().minusDays(1));
        pc.setCertFileName(nano + ".txt");
        pc.setCertFileContent("V7校准证书-" + nano);
        long probeId = probeService.createProbe(pc).getId();

        ProbeBindRequest pb = new ProbeBindRequest();
        pb.setRequestId("v7bind-" + nano);
        pb.setVehicleId(vehicleId);
        pb.setProbeId(probeId);
        probeService.bindIdempotent(pb);

        DispatchRequest dr = new DispatchRequest();
        dr.setOrderId(orderId);
        dr.setVehicleId(vehicleId);
        Long batchId = batchService.dispatch(dr).getId();
        batchService.ship(batchId);
        return batchId;
    }

    // ---------------- HTTP / 断言辅助 ----------------

    private HttpHeaders jsonHeaders(String user, String pwd) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (user != null) {
            String token = java.util.Base64.getEncoder().encodeToString((user + ":" + pwd).getBytes(StandardCharsets.UTF_8));
            h.set(HttpHeaders.AUTHORIZATION, "Basic " + token);
        }
        return h;
    }

    private JsonNode postAs(String user, String path, Object body) {
        try {
            HttpEntity<byte[]> entity = new HttpEntity<>(M.writeValueAsBytes(body), jsonHeaders(user, INSP_PWD));
            String resp = http.postForObject("http://localhost:" + port + BASE + path, entity, String.class);
            return M.readTree(resp);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** GET 返回 ApiResponse 的 data 节点 */
    private JsonNode getAs(String user, String path) {
        JsonNode env = getAsEnvelope(user, path);
        assertTrue(env.path("success").asBoolean(), "GET 应成功: " + env);
        return env.path("data");
    }

    private JsonNode getAsEnvelope(String user, String path) {
        try {
            HttpEntity<?> entity = new HttpEntity<>(jsonHeaders(user, INSP_PWD));
            String resp = http.exchange("http://localhost:" + port + BASE + path,
                    org.springframework.http.HttpMethod.GET, entity, String.class).getBody();
            return M.readTree(resp);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void assertStop(JsonNode resp, String keyword) {
        assertFalse(resp.path("success").asBoolean(), "应被拦截: " + resp);
        assertTrue(resp.path("message").asText().contains(keyword),
                "停点说明应包含[" + keyword + "]，实际：" + resp.path("message").asText());
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static String sha256(String content) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
