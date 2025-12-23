package com.zhy.ai.ollamastudy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 针对 {@link OllamaChatController} 的集成测试，
 * 演示如何通过 MockMvc 调用 REST 接口。
 */
@SpringBootTest
@AutoConfigureMockMvc
class OllamaChatControllerTests {

    private static final Logger logger = LoggerFactory.getLogger(OllamaChatControllerTests.class);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 测试 GET /api/ollama/models 是否可以正常返回 200，
     * 并简单校验返回结果为 JSON 数组。
     */
    @Test
    void listModelsEndpointShouldReturnModelsArray() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/ollama/models"))
                .andExpect(status().isOk())
                .andReturn();

        String json = result.getResponse().getContentAsString();
        logger.info("GET /api/ollama/models response: {}", json);

        JsonNode root = objectMapper.readTree(json);
        Assertions.assertTrue(root.isArray(), "Models response should be a JSON array.");
    }

    /**
     * 使用 /api/ollama/chat（高层 API）进行一次简单对话，
     * 校验 HTTP 200 且 content 字段非空。
     */
    @Test
    void chatWithHighLevelApiShouldReturnContent() throws Exception {
        String body = """
                {"prompt":"Say 'hello' in English using only one word."}
                """;

        MvcResult result = mockMvc.perform(
                        post("/api/ollama/chat")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isOk())
                .andReturn();

        String json = result.getResponse().getContentAsString();
        logger.info("POST /api/ollama/chat response: {}", json);

        JsonNode root = objectMapper.readTree(json);
        String content = safeGetText(root, "content");
        Assumptions.assumeTrue(content != null, "Content is null, possibly due to Ollama not running.");
        Assertions.assertFalse(content.isBlank());
    }

    /**
     * 使用 /api/ollama/chat-low（底层 API）进行一次简单对话，
     * 校验 HTTP 200 且 content 字段非空。
     */
    @Test
    void chatWithLowLevelApiShouldReturnContent() throws Exception {
        String body = """
                {"prompt":"Say 'hello' in English using only one word."}
                """;

        MvcResult result = mockMvc.perform(
                        post("/api/ollama/chat-low")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isOk())
                .andReturn();

        String json = result.getResponse().getContentAsString();
        logger.info("POST /api/ollama/chat-low response: {}", json);

        JsonNode root = objectMapper.readTree(json);
        String content = safeGetText(root, "content");
        Assumptions.assumeTrue(content != null, "Content is null, possibly due to Ollama not running.");
        Assertions.assertFalse(content.isBlank());
    }

    /**
     * 安全读取 JSON 字段的文本值，避免 NPE。
     *
     * @param root JSON 根节点
     * @param name 字段名
     * @return 字段文本值或 null
     */
    private String safeGetText(JsonNode root, String name) {
        if (root == null || !root.hasNonNull(name)) {
            return null;
        }
        return root.get(name).asText();
    }

}

