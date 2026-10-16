package com.workpath.backend;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.workpath.backend.mapper")
public class WorkPathBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(WorkPathBackendApplication.class, args);
    }

}
