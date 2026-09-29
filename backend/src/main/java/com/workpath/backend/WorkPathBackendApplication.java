package com.workpath.backend;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@MapperScan("com.workpath.backend.mapper")
// 定时任务开关。缺了它 AnalysisTaskQueue 的 @Scheduled 不会注册——
// 表现是"上传后 analysis_status 永远停在 0"，而代码看起来完全正确
@EnableScheduling
public class WorkPathBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(WorkPathBackendApplication.class, args);
    }

}
