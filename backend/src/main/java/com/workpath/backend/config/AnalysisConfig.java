package com.workpath.backend.config;

import com.workpath.backend.entity.ModelProviderConfig;
import com.workpath.backend.mapper.ModelProviderConfigMapper;
import com.workpath.backend.service.analyze.StayPointDetector;
import com.workpath.backend.service.modelProvider.GeoClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * M4 轨迹分析相关 bean。
 *
 * <p>停留点检测器是纯算法，只依赖阈值；阈值做成可注入的 bean 而不是
 * 硬编码在类里，是为了演示时能现场调参——第5章要展示「调大阈值后
 * 路口等待不再被误判」，改一个数就得重启的话那场演示会很尴尬。</p>
 *
 * <p>GeoClient 的凭据来自 model_provider_config 的 GEO 用途（W0 建的配置），
 * 在启动时读一次。之所以不做成每次请求都查库：POI 解析有频率限制，
 * 每次都查库既不必要，也会让配置的即时修改难以观察。</p>
 */
@Configuration
public class AnalysisConfig {

    /** 停留判定的最短持续秒数。实施指南 W4 建议 300 秒起。 */
    private static final int MIN_DWELL_SECONDS = 300;

    @Bean
    public StayPointDetector stayPointDetector() {
        return new StayPointDetector(MIN_DWELL_SECONDS);
    }

    @Bean
    public GeoClient geoClient(ModelProviderConfigMapper configMapper) {
        ModelProviderConfig cfg = configMapper.selectByPurpose(
                com.workpath.backend.service.ModelProviderService.PURPOSE_GEO);
        if (cfg == null) {
            // 没配 GEO 不阻塞启动：POI 是可选增强，poi_name 留空即可。
            // 返回一个未配置的 GeoClient，它的所有调用都会返回 empty
            return new GeoClient("", "");
        }
        return new GeoClient(cfg.getBaseUrl(), cfg.getApiKey());
    }
}
