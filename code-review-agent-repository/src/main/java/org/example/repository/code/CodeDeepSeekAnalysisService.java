package org.example.repository.code;

import java.util.List;

import lombok.extern.slf4j.Slf4j;
import org.example.domain.code.service.CodeAnalysisService;
import org.example.repository.deepseek.DeepSeekClient;
import org.example.repository.deepseek.dto.DeepSeekChatMessage;
import org.springframework.stereotype.Service;

/**
 * 基于 DeepSeek 的 {@link CodeAnalysisService} 实现。
 */
@Slf4j
@Service
public class CodeDeepSeekAnalysisService implements CodeAnalysisService {

    private static final String SYSTEM_PROMPT =
        "你是一位资深代码分析专家。给定一个方法的完整源代码，请用一句简洁的中文描述该方法的用途。"
            + "只描述高层意图，不要涉及实现细节。不超过50个汉字。";

    private final DeepSeekClient client;

    public CodeDeepSeekAnalysisService(DeepSeekClient client) {
        this.client = client;
    }

    @Override
    public String analyzePurpose(String sourceCode) {
        log.debug("DeepSeek 代码分析: {} 字符", sourceCode.length());

        var response = client.chatWithTools(
            List.of(
                DeepSeekChatMessage.system(SYSTEM_PROMPT),
                DeepSeekChatMessage.user(sourceCode)
            ));

        String purpose = response.getChoices().getFirst()
            .getMessage().getContent().strip();

        log.debug("方法用途: {}", purpose);
        return purpose;
    }
}
