package org.example.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {"org.example"})
public class CodeReviewAgentApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(CodeReviewAgentApiApplication.class, args);
    }
}
