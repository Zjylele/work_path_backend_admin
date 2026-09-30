package com.workpath.backend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workpath.backend.common.BusinessException;
import com.workpath.backend.common.ResultCode;
import com.workpath.backend.dto.ModelProviderSaveDTO;
import com.workpath.backend.entity.ModelProviderConfig;
import com.workpath.backend.entity.User;
import com.workpath.backend.interceptor.UserContext;
import com.workpath.backend.mapper.ModelProviderConfigMapper;
import com.workpath.backend.vo.ModelProviderVO;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 模型接入配置服务。
 *
 * <p>三条设计约束：</p>
 * <ol>
 *   <li>密钥脱敏——完整 apiKey 只存在于数据库与内存，任何接口响应只给后 4 位；</li>
 *   <li>空串不改密钥——前端不传完整密钥也能保存其他字段；</li>
 *   <li>操作留痕——配置是可产生费用的敏感资产，变更必须记审计日志。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelProviderService {

    /** 用途常量 */
    public static final String PURPOSE_CHAT = "CHAT";
    public static final String PURPOSE_EMBEDDING = "EMBEDDING";
    public static final String PURPOSE_GEO = "GEO";

    /** 允许配置的用途白名单。大写后必须命中其一，防止把任意字符串写进 purpose 列。 */
    private static final List<String> ALLOWED_PURPOSES = List.of(PURPOSE_CHAT, PURPOSE_EMBEDDING, PURPOSE_GEO);

    private final ModelProviderConfigMapper configMapper;
    private final OperationLogService operationLogService;

    /**
     * 脱敏：只保留后 4 位。长度不足 4 位时全部打码，避免短密钥被整体暴露。
     */
    public static String mask(String apiKey) {
        if (!StringUtils.hasText(apiKey)) {
            return null;
        }
        String k = apiKey.trim();
        if (k.length() <= 4) {
            return "*".repeat(k.length());
        }
        return "****" + k.substring(k.length() - 4);
    }

    /**
     * 查全部配置（脱敏）。仅超级管理员可读。
     *
     * <p>读取也要求超管的理由：配置里的接口地址与模型标识虽不是密钥本身，
     * 但属于内部部署信息。本模块在文档中承诺“仅超级管理员可访问”，
     * 若读取放开而写入收紧，行为与承诺不一致，且会给横向渗透留下线索。</p>
     */
    public List<ModelProviderVO> listAll() {
        currentOperator();
        return configMapper.selectList(null).stream()
                .map(this::toVO)
                .toList();
    }

    /**
     * 新增一条模型配置。某用途的第一条配置自动成为该用途的「当前使用」——
     * 否则添加完还得再点一次切换，多一步无意义的操作。
     *
     * @return 新配置的 id
     */
    public Long create(ModelProviderSaveDTO dto) {
        Operator operator = currentOperator();
        String purpose = normalizePurpose(dto.getPurpose());
        requireText(dto.getProviderName(), "服务商不能为空");
        requireText(dto.getBaseUrl(), "接口地址不能为空");
        requireText(dto.getModelName(), "模型标识不能为空");
        // 新增时密钥必填，否则存进去一条连不上的配置
        requireText(dto.getApiKey(), "密钥不能为空");

        ModelProviderConfig c = new ModelProviderConfig();
        BeanUtils.copyProperties(dto, c);
        c.setPurpose(purpose);
        c.setStatus(0);
        c.setEnabled(dto.getEnabled() == null ? 1 : dto.getEnabled());
        c.setSelected(countByPurpose(purpose) == 0 ? 1 : 0);
        configMapper.insert(c);

        operationLogService.record(
                operator.userId(), operator.username(), "MODEL_PROVIDER_CREATE",
                "model_provider_config", c.getId(),
                purpose, "用途=" + purpose + "，服务商=" + dto.getProviderName()
                        + "，模型=" + dto.getModelName(), true);
        return c.getId();
    }

    /**
     * 更新模型配置（按 id）。模型类型是这条配置的身份，不允许改——
     * 要换类型就新增一条。密钥空串表示保持原值。
     */
    public void update(Long id, ModelProviderSaveDTO dto) {
        Operator operator = currentOperator();
        ModelProviderConfig row = requireRow(id);
        requireText(dto.getProviderName(), "服务商不能为空");
        requireText(dto.getBaseUrl(), "接口地址不能为空");
        requireText(dto.getModelName(), "模型标识不能为空");

        LambdaUpdateWrapper<ModelProviderConfig> uw = new LambdaUpdateWrapper<>();
        uw.set(ModelProviderConfig::getProviderName, dto.getProviderName())
                .set(ModelProviderConfig::getBaseUrl, dto.getBaseUrl())
                .set(ModelProviderConfig::getModelName, dto.getModelName())
                .set(ModelProviderConfig::getExtraConfig, dto.getExtraConfig())
                .set(ModelProviderConfig::getEnabled, dto.getEnabled() == null ? 1 : dto.getEnabled())
                .eq(ModelProviderConfig::getId, id);
        // 只有真给了新密钥才更新密钥列
        if (StringUtils.hasText(dto.getApiKey())) {
            uw.set(ModelProviderConfig::getApiKey, dto.getApiKey().trim());
            // 密钥变了，之前的连通性结论作废
            uw.set(ModelProviderConfig::getStatus, 0);
            uw.set(ModelProviderConfig::getLastError, null);
        }
        configMapper.update(null, uw);

        operationLogService.record(
                operator.userId(), operator.username(), "MODEL_PROVIDER_UPDATE",
                "model_provider_config", id,
                row.getPurpose(), "用途=" + row.getPurpose() + "，服务商=" + dto.getProviderName(), true);
    }

    /**
     * 切换某用途的「当前使用」模型。事务内先清后置：
     * 先把该用途全部行的 selected 清零，再置目标行——
     * 任一时刻每个用途至多一条 selected=1 由这一顺序保证。
     */
    @Transactional
    public void select(Long id) {
        Operator operator = currentOperator();
        ModelProviderConfig row = requireRow(id);
        configMapper.update(null, new LambdaUpdateWrapper<ModelProviderConfig>()
                .eq(ModelProviderConfig::getPurpose, row.getPurpose())
                .set(ModelProviderConfig::getSelected, 0));
        ModelProviderConfig patch = new ModelProviderConfig();
        patch.setId(id);
        patch.setSelected(1);
        configMapper.updateById(patch);

        operationLogService.record(
                operator.userId(), operator.username(), "MODEL_PROVIDER_SELECT",
                "model_provider_config", id,
                row.getPurpose(), "用途=" + row.getPurpose() + "，当前模型=" + row.getModelName(), true);
    }

    /**
     * 删除模型。当前使用中的配置若有同用途替代品，必须先切换再删——
     * 直接删会让该用途失去生效配置，语言标注、条款检索这些链路全部中断；
     * 若是唯一一条则允许删（管理员明确知道自己在停用整条链路）。
     */
    @Transactional
    public void delete(Long id) {
        Operator operator = currentOperator();
        ModelProviderConfig row = requireRow(id);
        if (row.getSelected() != null && row.getSelected() == 1
                && countByPurpose(row.getPurpose()) > 1) {
            throw new BusinessException(ResultCode.CONFLICT.getCode(),
                    "该模型是当前使用中的配置，请先切换到其他模型再删除");
        }
        configMapper.deleteById(id);

        operationLogService.record(
                operator.userId(), operator.username(), "MODEL_PROVIDER_DELETE",
                "model_provider_config", id,
                row.getPurpose(), "用途=" + row.getPurpose() + "，模型=" + row.getModelName(), true);
    }

    /**
     * 连接测试（按配置 id）：以库里这条配置为基准，允许入参覆盖部分字段
     * （编辑表单里测「改了还没存」的值），结果写回该行的 status/last_error。
     *
     * <p>密钥覆盖为空时用库里的密钥：页面出于安全不回传完整密钥，
     * 若直接把空串发给服务商，只会得到一个误导人的 401。</p>
     *
     * @return 失败原因；成功返回 null
     */
    public String testById(Long id, ModelProviderSaveDTO override) {
        Operator operator = currentOperator();
        ModelProviderConfig row = requireRow(id);

        ModelProviderSaveDTO effective = new ModelProviderSaveDTO();
        effective.setPurpose(row.getPurpose());
        if (override != null) {
            effective.setBaseUrl(override.getBaseUrl());
            effective.setModelName(override.getModelName());
            effective.setExtraConfig(override.getExtraConfig());
            effective.setApiKey(override.getApiKey());
        }
        if (!StringUtils.hasText(effective.getBaseUrl())) {
            effective.setBaseUrl(row.getBaseUrl());
        }
        if (!StringUtils.hasText(effective.getModelName())) {
            effective.setModelName(row.getModelName());
        }
        if (!StringUtils.hasText(effective.getExtraConfig())) {
            effective.setExtraConfig(row.getExtraConfig());
        }
        if (!StringUtils.hasText(effective.getApiKey())) {
            effective.setApiKey(row.getApiKey());
        }

        String err;
        try {
            err = doTest(row.getPurpose(), effective);
        } catch (Exception e) {
            // 异常信息可能带上完整 URL，只取类型与简短消息，避免密钥随日志外泄
            err = e.getClass().getSimpleName();
            log.warn("模型连接测试异常 id={} purpose={}", id, row.getPurpose(), e);
        }

        ModelProviderConfig patch = new ModelProviderConfig();
        patch.setId(id);
        patch.setStatus(err == null ? 1 : 2);
        patch.setLastError(err);
        configMapper.updateById(patch);

        operationLogService.record(
                operator.userId(), operator.username(), "MODEL_PROVIDER_TEST",
                "model_provider_config", id,
                row.getPurpose(), "用途=" + row.getPurpose() + "，结果=" + (err == null ? "通过" : "失败"), true);

        return err;
    }

    /**
     * 真正发探测请求的地方。返回 null 表示连通，返回字符串表示失败原因。
     *
     * <p>用 JDK 17 自带的 HttpClient，不引第三方 HTTP 组件——减少依赖面，
     * 也让探测逻辑本身可被单元测试直接驱动。</p>
     *
     * <p>三条设计约束：</p>
     * <ol>
     *   <li>探测请求要尽可能小（max_tokens 给 1、只用一条中文文本），避免产生费用与延迟；</li>
     *   <li>失败原因只做截断，不把完整响应体回给前端——响应体里可能回显请求内容；</li>
     *   <li>超时要短（8 秒），探测不该让用户等。</li>
     * </ol>
     */
    protected String doTest(String purpose, ModelProviderSaveDTO dto) {
        try {
            return switch (purpose) {
                case PURPOSE_CHAT -> testChat(dto);
                case PURPOSE_EMBEDDING -> testEmbedding(dto);
                case PURPOSE_GEO -> testGeo(dto);
                default -> "未知用途：" + purpose;
            };
        } catch (HttpConnectTimeoutException e) {
            // 子类必须排在父类 HttpTimeoutException 之前，否则永远捕不到
            return "无法连接到 " + dto.getBaseUrl();
        } catch (HttpTimeoutException e) {
            return "连接超时（8 秒无响应）";
        } catch (IOException e) {
            return "网络异常：" + e.getClass().getSimpleName();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "探测被中断";
        }
    }

    /** CHAT：发一条最小请求，只看是否返回 200 且有 choices。 */
    private String testChat(ModelProviderSaveDTO dto) throws IOException, InterruptedException, InterruptedException {
        String url = trimSlash(dto.getBaseUrl()) + "/chat/completions";
        String body = "{\"model\":\"" + dto.getModelName() + "\","
                + "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],"
                + "\"max_tokens\":1}";
        HttpResponse<String> resp = post(url, dto.getApiKey(), body, Map.of());
        if (resp.statusCode() / 100 != 2) {
            return "HTTP " + resp.statusCode() + "：" + errorDetail(resp.body());
        }
        JsonNode root = mapper().readTree(resp.body());
        if (root.path("choices").isEmpty()) {
            return "响应缺少 choices 字段，返回的不是对话接口格式";
        }
        return null;
    }

    /**
     * EMBEDDING：校验返回向量的实际维度与 extra_config.dimension 是否一致。
     *
     * <p>这一步不只是探活——向量维度不符会让库里已存的向量全部作废
     * （第1章 1.3.4 节所述），所以在配置阶段就拦住，别等入库后才发现。</p>
     */
    private String testEmbedding(ModelProviderSaveDTO dto) throws IOException, InterruptedException {
        String url = trimSlash(dto.getBaseUrl()) + "/embeddings";
        // 用户可能填了完整端点（含 /embeddings），此时会拼成 /embeddings/embeddings。
        // 实测硅基流动对这种地址恰好能容错，但不该依赖服务商的容错行为。
        if (url.endsWith("/embeddings/embeddings")) {
            url = url.replace("/embeddings/embeddings", "/embeddings");
        }
        // input 必须传纯字符串。硅基流动这个端点不收数组形式（返回 400 code 20015），
        // 虽然它对外声称 OpenAI 兼容。
        // 另外不要传 dimensions 参数——实测同样不被接受。
        // 探测文本刻意用中文：业务数据（制度条款）全是中文，用 ASCII 探测通了不代表
        // 真实场景能通。body 由 post() 以 UTF-8 显式编码后发送。
        String body = "{\"model\":\"" + dto.getModelName() + "\","
                + "\"input\":\"员工在未报备地点长时间停留\"}";
        HttpResponse<String> resp = post(url, dto.getApiKey(), body, Map.of());
        if (resp.statusCode() / 100 != 2) {
            return "HTTP " + resp.statusCode() + "：" + errorDetail(resp.body());
        }
        JsonNode root = mapper().readTree(resp.body());
        JsonNode vec = root.path("data").path(0).path("embedding");
        if (!vec.isArray()) {
            return "响应缺少 embedding 数组，返回的不是向量接口格式";
        }
        int actual = vec.size();
        Integer expect = readExpectedDimension(dto.getExtraConfig());
        if (expect != null && expect != actual) {
            return "向量维度不符：接口返回 " + actual + " 维，配置声明 " + expect
                    + " 维。维度不一致会导致已存向量全部作废，请修正配置或重新向量化";
        }
        return null;
    }

    /** GEO：用固定坐标探测逆地理编码，顺带把解析到的地名写进成功信息里。 */
    private String testGeo(ModelProviderSaveDTO dto) throws IOException, InterruptedException, InterruptedException {
        String url = trimSlash(dto.getBaseUrl()) + "/v3/geocode/regeo?key="
                + dto.getApiKey() + "&location=104.066678,30.657261";
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(PROBE_TIMEOUT).GET().build();
        HttpResponse<String> resp = httpClient().send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            return "HTTP " + resp.statusCode();
        }
        JsonNode root = mapper().readTree(resp.body());
        if (!"1".equals(root.path("status").asText())) {
            return "高德返回 status=" + root.path("status").asText() + "：" + abbreviate(root.path("info").asText());
        }
        return null;
    }

    /** 从 extra_config JSON 里读 dimension，读不到或非法都返回 null（不阻塞探测）。 */
    private Integer readExpectedDimension(String extraConfig) {
        if (!StringUtils.hasText(extraConfig)) {
            return null;
        }
        try {
            JsonNode n = mapper().readTree(extraConfig);
            int v = n.path("dimension").asInt(0);
            return v > 0 ? v : null;
        } catch (Exception e) {
            return null;
        }
    }

    private HttpResponse<String> post(String url, String key, String body, Map<String, String> headers)
            throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(PROBE_TIMEOUT)
                // 显式声明 charset：body 里有中文（制度条款向量化时更是如此），
                // 少了这个头，某些服务端会按自己的默认编码猜，猜错就是乱码。
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (StringUtils.hasText(key)) {
            b.header("Authorization", "Bearer " + key.trim());
        }
        for (Map.Entry<String, String> e : headers.entrySet()) {
            b.header(e.getKey(), e.getValue());
        }
        return httpClient().send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(8);
    private static final int ABBREV_LIMIT = 120;

    /**
     * 响应体可能很长，截断后再给前端；同时避免把请求内容回显出去。
     */
    private String abbreviate(String s) {
        if (!StringUtils.hasText(s)) {
            return "";
        }
        String t = s.trim();
        return t.length() <= ABBREV_LIMIT ? t : t.substring(0, ABBREV_LIMIT) + "…";
    }

    private String trimSlash(String s) {
        return StringUtils.hasText(s) ? s.trim().replaceAll("/+$", "") : "";
    }

    /**
     * 从响应体里挑出服务商真正给出的错误说明。
     *
     * <p>不少服务商（如 DeepSeek）把错误放在 error.message 里，直接整段回给前端
     * 是一大坨 JSON，可读性很差。这里优先取几个常见字段，取不到才退回原文截断。</p>
     */
    private String errorDetail(String body) {
        if (!StringUtils.hasText(body)) {
            return "";
        }
        String trimmed = body.trim();
        // 尝试解析成 JSON，取 error.message / message / msg
        if (trimmed.startsWith("{")) {
            try {
                JsonNode root = mapper().readTree(trimmed);
                for (String path : new String[]{"error.message", "message", "msg", "info"}) {
                    JsonNode n = root;
                    for (String seg : path.split("\\.")) {
                        n = n.path(seg);
                    }
                    if (n.isTextual() && StringUtils.hasText(n.asText())) {
                        return abbreviate(n.asText());
                    }
                }
            } catch (Exception ignore) {
                // 解析不了就走原文截断
            }
        }
        return abbreviate(trimmed);
    }

    private java.net.http.HttpClient httpClient() {
        return java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    private ObjectMapper mapper() {
        return new ObjectMapper();
    }

    /**
     * 取某个用途「当前使用」的完整配置。调用方是服务端内部逻辑（如真正发模型请求），
     * 不走 Controller，因此允许拿到完整密钥。
     *
     * <p>自愈兜底：迁移前的存量库可能没有任何 selected=1 行。此时选中该用途
     * id 最小的启用行并落库，之后正常命中——行为与旧版「每用途一条」无缝衔接，
     * 也经受得起有人手工改库把 selected 清零的折腾。</p>
     */
    public ModelProviderConfig requireConfig(String purpose) {
        String p = normalizePurpose(purpose);
        ModelProviderConfig c = configMapper.selectSelectedByPurpose(p);
        if (c != null) {
            return c;
        }
        List<ModelProviderConfig> candidates = configMapper.selectList(
                new LambdaQueryWrapper<ModelProviderConfig>()
                        .eq(ModelProviderConfig::getPurpose, p)
                        .orderByAsc(ModelProviderConfig::getId));
        if (candidates.isEmpty()) {
            throw new BusinessException(ResultCode.ERROR.getCode(), "尚未配置「" + p + "」的模型接入");
        }
        // 优先启用中的行；全停用时退而求其次，让配置页能看到明确的测试失败，
        // 而不是业务侧报「尚未配置」却查不到原因
        ModelProviderConfig first = candidates.stream()
                .filter(x -> x.getEnabled() == null || x.getEnabled() == 1)
                .findFirst()
                .orElse(candidates.get(0));
        ModelProviderConfig patch = new ModelProviderConfig();
        patch.setId(first.getId());
        patch.setSelected(1);
        configMapper.updateById(patch);
        first.setSelected(1);
        return first;
    }

    /** 按 id 取配置，取不到抛 404。 */
    private ModelProviderConfig requireRow(Long id) {
        ModelProviderConfig row = id == null ? null : configMapper.selectById(id);
        if (row == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "模型配置不存在");
        }
        return row;
    }

    /** 某用途下的配置条数（判空包装，mock 环境可能返回 null）。 */
    private long countByPurpose(String purpose) {
        Long n = configMapper.selectCount(new LambdaQueryWrapper<ModelProviderConfig>()
                .eq(ModelProviderConfig::getPurpose, purpose));
        return n == null ? 0 : n;
    }

    private ModelProviderVO toVO(ModelProviderConfig c) {
        if (c == null) {
            return null;
        }
        ModelProviderVO vo = new ModelProviderVO();
        BeanUtils.copyProperties(c, vo);
        vo.setApiKeyBack(mask(c.getApiKey()));
        return vo;
    }

    private String normalizePurpose(String purpose) {
        if (!StringUtils.hasText(purpose)) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "用途不能为空");
        }
        String p = purpose.trim().toUpperCase();
        if (!ALLOWED_PURPOSES.contains(p)) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "用途仅支持 CHAT/EMBEDDING/GEO");
        }
        return p;
    }

    private void requireText(String v, String msg) {
        if (!StringUtils.hasText(v)) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), msg);
        }
    }

    /**
     * 操作人信息载体。
     */
    public record Operator(Long userId, String username) {
    }

    /**
     * 从鉴权上下文取当前操作人，并强制要求超级管理员。
     *
     * <p>为什么必须超管：这个页面存放的是会产生真实费用的 API 凭据，
     * 一旦被普通管理员改动，可能导致服务中断或产生异常账单。
     * 与 AdminService.changeRole 的超管约束保持一致的严格程度。</p>
     */
    private Operator currentOperator() {
        User user = UserContext.get();
        if (user == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED.getCode(), "未登录或登录已过期");
        }
        if (user.getSuperAdmin() == null || user.getSuperAdmin() != 1) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "仅超级管理员可修改模型接入配置");
        }
        return new Operator(user.getId(), user.getUsername());
    }
}
