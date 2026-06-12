package org.example.repository.github;

import lombok.extern.slf4j.Slf4j;
import org.example.common.utils.JsonUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;

/**
 * GitHub REST API 客户端。
 * <p>
 * 用于提交 PR Review 和行级 Comment。
 */
@Slf4j
public class GitHubClient {

    private final GitHubProperties properties;
    private final HttpClient httpClient;

    public GitHubClient(GitHubProperties properties) {
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder().build();
    }

    /**
     * 提交 Pull Request Review（整体结论）。
     *
     * @param owner    仓库所有者
     * @param repo     仓库名
     * @param prNumber PR 编号
     * @param body     审查意见正文
     * @param event    事件类型: "APPROVE" 通过, "REQUEST_CHANGES" 拒绝
     */
    public void submitReview(String owner, String repo, int prNumber,
                             String body, String event) {
        // 请求体: { body, event }
        Map<String, String> requestBody = new HashMap<>();
        requestBody.put("body", body);
        requestBody.put("event", event);

        String url = properties.getApiUrl() + "/repos/" + owner + "/"
            + repo + "/pulls/" + prNumber + "/reviews";

        executePost(url, requestBody);
        log.info("PR Review 提交完成: {}/{} PR#{} event={}", owner, repo, prNumber, event);
    }

    /**
     * 提交行级 Comment（绑定到具体代码行）。
     *
     * @param owner     仓库所有者
     * @param repo      仓库名
     * @param prNumber  PR 编号
     * @param commitId  commit SHA
     * @param filePath  文件路径
     * @param line      行号
     * @param body      评论内容
     */
    public void submitLineComment(String owner, String repo, int prNumber,
                                  String commitId, String filePath, int line, String body) {
        // 请求体: { body, commit_id, path, line, side }
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("body", body);
        requestBody.put("commit_id", commitId);
        requestBody.put("path", filePath);
        requestBody.put("line", line);
        requestBody.put("side", "RIGHT");  // diff 右侧（新代码）

        String url = properties.getApiUrl() + "/repos/" + owner + "/"
            + repo + "/pulls/" + prNumber + "/comments";

        executePost(url, requestBody);
        log.debug("行级 Comment 提交: {}:{}", filePath, line);
    }

    /** 封装 POST 请求 */
    private void executePost(String url, Object body) {
        String json = JsonUtils.toJson(body);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + properties.getToken())
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build();

        try {
            HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new RuntimeException("GitHub API 错误: HTTP " + response.statusCode()
                    + ", body: " + response.body());
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("GitHub API 请求失败: " + e.getMessage(), e);
        }
    }
}
