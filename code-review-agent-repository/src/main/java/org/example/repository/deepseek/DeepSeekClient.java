package org.example.repository.deepseek;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.collections4.CollectionUtils;
import org.example.common.utils.JsonUtils;
import org.example.repository.deepseek.dto.DeepSeekChatMessage;
import org.example.repository.deepseek.dto.DeepSeekResponse;

import lombok.extern.slf4j.Slf4j;

/**
 * DeepSeek API HTTP 客户端。
 * <p>
 * 封装 OpenAI 兼容的 chat completions 协议，
 * 支持带工具调用（function calling）的 Agent 场景。
 */
@Slf4j
public class DeepSeekClient {
    private final DeepSeekProperties properties;
    private final HttpClient httpClient;

    public DeepSeekClient(DeepSeekProperties properties) {
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(properties.getConnectTimeout())
            .build();
    }

    /**
     * 发送不含工具的 chat 请求。
     */
    public DeepSeekResponse chatWithTools(List<DeepSeekChatMessage> messages) {
        return chatWithTools(messages, null);
    }

    /**
     * 发送带工具定义的 chat 请求（Agent function calling）。
     *
     * @param messages      消息列表
     * @param agentFunction 工具定义列表，为 null 或空时不启用工具调用
     * @return API 响应
     * @throws DeepSeekApiException 调用失败时抛出
     */
    public DeepSeekResponse chatWithTools(
            List<DeepSeekChatMessage> messages,
            List<Map<String, Object>> agentFunction) {

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", properties.getModel());
        requestBody.put("messages", messages);

        if (CollectionUtils.isNotEmpty(agentFunction)) {
            requestBody.put("tools", agentFunction);
            requestBody.put("tool_choice", "auto");
        }

        String jsonPayload = JsonUtils.toJson(requestBody);
        String url = properties.getBaseUrl() + "/v1/chat/completions";

        log.debug("DeepSeek 请求: url={}, model={}, messages={}",
            url, properties.getModel(), messages.size());

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + properties.getApiKey())
            .timeout(properties.getReadTimeout())
            .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
            .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, BodyHandlers.ofString());
        } catch (Exception e) {
            throw new DeepSeekApiException(
                "DeepSeek API 请求发送失败: " + e.getMessage(), e);
        }

        if (response.statusCode() != 200) {
            String bodySnippet = response.body();
            if (bodySnippet != null && bodySnippet.length() > 500) {
                bodySnippet = bodySnippet.substring(0, 500) + "…";
            }
            throw new DeepSeekApiException(
                "DeepSeek API 返回错误: HTTP " + response.statusCode()
                    + ", body: " + bodySnippet);
        }

        log.debug("DeepSeek 响应: HTTP 200");

        return JsonUtils.fromJson(response.body(), DeepSeekResponse.class);
    }
}
