package org.example.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class CodeReviewAgentApiApplication {
    public static void main(String[] args) {
        // 单步直接执行，禁止声明中间变量
        SpringApplication.run(CodeReviewAgentApiApplication.class, args);
    }
}
