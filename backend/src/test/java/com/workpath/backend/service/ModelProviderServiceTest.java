package com.workpath.backend.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.workpath.backend.common.BusinessException;
import com.workpath.backend.dto.ModelProviderSaveDTO;
import com.workpath.backend.entity.ModelProviderConfig;
import com.workpath.backend.entity.User;
import com.workpath.backend.interceptor.UserContext;
import com.workpath.backend.mapper.ModelProviderConfigMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 一用途多模型的核心行为钉子：新增自动占位、切换先清后置、
 * 删除保护、requireConfig 自愈、超管约束。
 */
class ModelProviderServiceTest {

    private ModelProviderConfigMapper configMapper;
    private OperationLogService operationLogService;
    private ModelProviderService service;

    @BeforeEach
    void setUp() {
        // LambdaUpdateWrapper.set() 在纯单测环境需要实体的 lambda 列缓存，
        // Spring 上下文之外必须手动初始化，否则报 can not find lambda cache
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                ModelProviderConfig.class);
        configMapper = Mockito.mock(ModelProviderConfigMapper.class);
        operationLogService = Mockito.mock(OperationLogService.class);
        service = new ModelProviderService(configMapper, operationLogService);
        UserContext.set(superAdmin(1L, 1));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    // ============================================================
    // 新增
    // ============================================================

    @Test
    @DisplayName("新增：该用途第一条配置自动成为当前使用")
    void createFirstAutoSelected() {
        when(configMapper.selectCount(any())).thenReturn(0L);

        service.create(dto("CHAT", "deepseek", "deepseek-flash", "sk-xxx"));

        ArgumentCaptor<ModelProviderConfig> cap = ArgumentCaptor.forClass(ModelProviderConfig.class);
        verify(configMapper).insert(cap.capture());
        assertThat(cap.getValue().getSelected()).isEqualTo(1);
        assertThat(cap.getValue().getStatus()).isZero();
    }

    @Test
    @DisplayName("新增：用途已有配置时不抢占当前使用")
    void createSecondNotSelected() {
        when(configMapper.selectCount(any())).thenReturn(2L);

        service.create(dto("CHAT", "qwen", "qwen-max", "sk-yyy"));

        ArgumentCaptor<ModelProviderConfig> cap = ArgumentCaptor.forClass(ModelProviderConfig.class);
        verify(configMapper).insert(cap.capture());
        assertThat(cap.getValue().getSelected()).isZero();
    }

    @Test
    @DisplayName("新增缺密钥被拒，不落库")
    void createRequiresApiKey() {
        assertThatThrownBy(() -> service.create(dto("CHAT", "deepseek", "deepseek-flash", "")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("密钥");
        verify(configMapper, never()).insert(any(ModelProviderConfig.class));
    }

    // ============================================================
    // 切换
    // ============================================================

    @Test
    @DisplayName("切换：先清该用途全部选中，再置目标行")
    void selectClearsThenSets() {
        when(configMapper.selectById(2L)).thenReturn(row(2L, "CHAT", "qwen-max", 0, 1));

        service.select(2L);

        verify(configMapper).update(Mockito.isNull(), any(LambdaUpdateWrapper.class));
        ArgumentCaptor<ModelProviderConfig> cap = ArgumentCaptor.forClass(ModelProviderConfig.class);
        verify(configMapper).updateById(cap.capture());
        assertThat(cap.getValue().getId()).isEqualTo(2L);
        assertThat(cap.getValue().getSelected()).isEqualTo(1);
    }

    // ============================================================
    // 删除
    // ============================================================

    @Test
    @DisplayName("删除：当前使用中且有同用途替代品时被拒")
    void deleteSelectedWithSiblingsRejected() {
        when(configMapper.selectById(1L)).thenReturn(row(1L, "CHAT", "deepseek-flash", 1, 1));
        when(configMapper.selectCount(any())).thenReturn(2L);

        assertThatThrownBy(() -> service.delete(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("先切换");
        verify(configMapper, never()).deleteById(Mockito.anyLong());
    }

    @Test
    @DisplayName("删除：非当前使用直接删")
    void deleteNonSelectedOk() {
        when(configMapper.selectById(3L)).thenReturn(row(3L, "CHAT", "qwen-max", 0, 1));

        service.delete(3L);

        verify(configMapper).deleteById(3L);
    }

    @Test
    @DisplayName("删除：用途唯一一条即使在使用中也可删（明确停用整条链路）")
    void deleteLastOneOk() {
        when(configMapper.selectById(6L)).thenReturn(row(6L, "GEO", "v3/geocode/regeo", 1, 1));
        when(configMapper.selectCount(any())).thenReturn(1L);

        service.delete(6L);

        verify(configMapper).deleteById(6L);
    }

    // ============================================================
    // 更新
    // ============================================================

    @Test
    @DisplayName("更新：密钥留空时不覆盖原密钥")
    void updateKeepsApiKeyWhenBlank() {
        when(configMapper.selectById(1L)).thenReturn(row(1L, "CHAT", "deepseek-flash", 1, 1));

        ModelProviderSaveDTO d = dto("CHAT", "deepseek", "deepseek-chat", "");
        service.update(1L, d);

        ArgumentCaptor<LambdaUpdateWrapper<ModelProviderConfig>> cap =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(configMapper).update(Mockito.isNull(), cap.capture());
        String sqlSet = cap.getValue().getSqlSet();
        assertThat(sqlSet).contains("model_name").doesNotContain("api_key");
    }

    // ============================================================
    // requireConfig 自愈
    // ============================================================

    @Test
    @DisplayName("requireConfig：无选中行时自愈选中 id 最小的启用行")
    void requireConfigSelfHeals() {
        when(configMapper.selectSelectedByPurpose("CHAT")).thenReturn(null);
        when(configMapper.selectList(any())).thenReturn(java.util.List.of(
                row(5L, "CHAT", "qwen-max", 0, 0),
                row(2L, "CHAT", "deepseek-flash", 0, 1)));

        ModelProviderConfig c = service.requireConfig("CHAT");

        assertThat(c.getId()).isEqualTo(2L);
        ArgumentCaptor<ModelProviderConfig> cap = ArgumentCaptor.forClass(ModelProviderConfig.class);
        verify(configMapper).updateById(cap.capture());
        assertThat(cap.getValue().getId()).isEqualTo(2L);
        assertThat(cap.getValue().getSelected()).isEqualTo(1);
    }

    @Test
    @DisplayName("requireConfig：用途完全无配置时报错")
    void requireConfigThrowsWhenEmpty() {
        when(configMapper.selectSelectedByPurpose("GEO")).thenReturn(null);
        when(configMapper.selectList(any())).thenReturn(java.util.List.of());

        assertThatThrownBy(() -> service.requireConfig("GEO"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("尚未配置");
    }

    // ============================================================
    // 权限
    // ============================================================

    @Test
    @DisplayName("非超级管理员操作被拒")
    void nonSuperAdminRejected() {
        UserContext.set(superAdmin(9L, 0));

        assertThatThrownBy(() -> service.create(dto("CHAT", "x", "y", "k")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("超级管理员");
    }

    // ============================================================
    // 造数
    // ============================================================

    private User superAdmin(Long id, int flag) {
        User u = new User();
        u.setId(id);
        u.setUsername("u" + id);
        u.setRole("ADMIN");
        u.setSuperAdmin(flag);
        u.setStatus(1);
        return u;
    }

    private ModelProviderSaveDTO dto(String purpose, String provider, String model, String key) {
        ModelProviderSaveDTO d = new ModelProviderSaveDTO();
        d.setPurpose(purpose);
        d.setProviderName(provider);
        d.setBaseUrl("https://api.example.com");
        d.setModelName(model);
        d.setApiKey(key);
        d.setEnabled(1);
        return d;
    }

    private ModelProviderConfig row(Long id, String purpose, String model, int selected, int enabled) {
        ModelProviderConfig c = new ModelProviderConfig();
        c.setId(id);
        c.setPurpose(purpose);
        c.setProviderName("p" + id);
        c.setBaseUrl("https://api.example.com");
        c.setModelName(model);
        c.setApiKey("sk-saved-key");
        c.setEnabled(enabled);
        c.setSelected(selected);
        c.setStatus(0);
        return c;
    }
}
