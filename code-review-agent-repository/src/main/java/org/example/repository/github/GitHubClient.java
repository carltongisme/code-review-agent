package org.example.repository.github;

import com.fasterxml.jackson.core.type.TypeReference;
import lombok.extern.slf4j.Slf4j;
import org.example.common.utils.JsonUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
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

    /**
     * 获取指定 ref 下的文件内容。
     * <p>
     * 通过 GitHub Contents API 获取文件，返回解码后的文本内容。
     *
     * @param owner    仓库所有者
     * @param repo     仓库名
     * @param filePath 文件路径（相对于仓库根目录）
     * @param ref      Git ref（commit SHA / 分支名 / tag）
     * @return 文件内容，若文件不存在（404）或过大（>1MB 无 content）则返回 null
     */
    public String getFileContent(String owner, String repo, String filePath, String ref) {
        String url = properties.getApiUrl() + "/repos/" + owner + "/"
            + repo + "/contents/" + filePath + "?ref=" + ref;

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", "Bearer " + properties.getToken())
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .GET()
            .build();

        try {
            HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 404) {
                log.warn("文件不存在于 ref {}: {}/{} path={}", ref, owner, repo, filePath);
                return null;
            }

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new RuntimeException("GitHub API 错误: HTTP " + response.statusCode()
                    + ", body: " + response.body());
            }

            Map<String, Object> body = JsonUtils.fromJson(response.body(),
                new TypeReference<Map<String, Object>>() {});

            // 文件 > 1MB 时 GitHub 不返回 content 字段，而是返回 302 或空
            String rawContent = (String) body.get("content");
            if (rawContent == null) {
                log.warn("文件过大无内容: {}/{} path={}", owner, repo, filePath);
                return null;
            }

            // GitHub 在 base64 内容中每 60 字符插入换行符，需先去除再解码
            String cleanBase64 = rawContent.replace("\n", "");
            byte[] decoded = Base64.getDecoder().decode(cleanBase64);
            return new String(decoded, StandardCharsets.UTF_8);

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("GitHub API 请求失败: " + e.getMessage(), e);
        }
    }

    /**
     * 获取 PR 的实际代码 diff（unified diff 格式）。
     *
     * @param owner    仓库所有者
     * @param repo     仓库名
     * @param prNumber PR 编号
     * @return 原始 unified diff 字符串，失败返回 null
     */
    public String getPullRequestDiff(String owner, String repo, int prNumber) {
        String url = properties.getApiUrl() + "/repos/" + owner + "/"
            + repo + "/pulls/" + prNumber;

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", "Bearer " + properties.getToken())
            .header("Accept", "application/vnd.github.v3.diff")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .GET()
            .build();

        try {
            HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 404) {
                log.warn("PR 不存在: {}/{} PR#{}", owner, repo, prNumber);
                return null;
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new RuntimeException("GitHub API 错误: HTTP " + response.statusCode()
                    + ", body: " + response.body());
            }
            return response.body();

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("GitHub API 请求失败: " + e.getMessage(), e);
        }
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
