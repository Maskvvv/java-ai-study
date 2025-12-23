package com.zhy.ai.ollamastudy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 使用 Spring Boot 上下文的集成测试类，演示如何通过 {@link OllamaApi}
 * 与本地运行的 Ollama 服务交互。
 */
@SpringBootTest
class OllamaStudyApplicationTests {

    /**
     * 在本机上优先尝试使用的模型名称。
     */
    private static final String PREFERRED_MODEL_NAME = "qwen2.5-coder:7b";

    private static final Logger logger = LoggerFactory.getLogger(OllamaStudyApplicationTests.class);

    @Autowired
    private OllamaApi ollamaApi;

    /**
     * 验证 Spring Boot 上下文是否可以正常加载。
     */
    @Test
    void contextLoads() {
        logger.info("Spring Boot context loaded successfully.");
    }

    /**
     * 使用 OllamaApi 进行一次最简单的聊天调用，演示：
     * 1. 获取可用模型列表；
     * 2. 选择优先模型 qwen2.5-coder:7b（若存在）；
     * 3. 构造单轮对话请求并获取响应内容。
     */
    @Test
    void simpleOllamaChatShouldReturnText() {
        List<OllamaApi.Model> models = queryLocalModels();

        Assumptions.assumeFalse(models.isEmpty(), "No local Ollama models found, skipping test.");

        String modelName = resolveModelName(models);
        String prompt = "Say 'hello' in English using only one word.";

        OllamaApi.ChatRequest request = buildSingleTurnChatRequest(modelName, prompt);

        OllamaApi.ChatResponse response = ollamaApi.chat(request);
        String content = extractMessageContent(response);

        Assertions.assertNotNull(content);
        Assertions.assertFalse(content.isBlank());
    }

    /**
     * 使用 OllamaApi 发送包含 System + User 的多轮消息，
     * 演示如何控制模型角色和提示。
     */
    @Test
    void chatWithSystemAndUserMessages() {
        List<OllamaApi.Model> models = queryLocalModels();
        Assumptions.assumeFalse(models.isEmpty(), "No local Ollama models found, skipping test.");

        String modelName = resolveModelName(models);

        List<OllamaApi.Message> messages = new ArrayList<>();
        messages.add(OllamaApi.Message.builder(OllamaApi.Message.Role.SYSTEM)
                .content("You are a concise assistant, answer with at most 10 words.")
                .build());
        messages.add(OllamaApi.Message.builder(OllamaApi.Message.Role.USER)
                .content("Introduce Spring AI in one short sentence.")
                .build());

        OllamaApi.ChatRequest request = OllamaApi.ChatRequest.builder(modelName)
                .stream(false)
                .messages(messages)
                .build();

        OllamaApi.ChatResponse response = ollamaApi.chat(request);
        String content = extractMessageContent(response);

        Assertions.assertNotNull(content);
        Assertions.assertFalse(content.isBlank());
    }

    /**
     * 使用 OllamaApi 进行“伪流式”调用：通过 streamingChat 获取流式响应，
     * 在测试中将所有片段拼接成一个完整字符串，方便观察。
     */
    @Test
    void streamingChatShouldAggregateResponse() {
        List<OllamaApi.Model> models = queryLocalModels();
        Assumptions.assumeFalse(models.isEmpty(), "No local Ollama models found, skipping test.");

        String modelName = resolveModelName(models);
        String prompt = "List three popular Java frameworks in one sentence.";

        OllamaApi.ChatRequest request = OllamaApi.ChatRequest.builder(modelName)
                .stream(true)
                .messages(List.of(OllamaApi.Message.builder(OllamaApi.Message.Role.USER).content(prompt).build()))
                .build();

        StringBuilder aggregated = new StringBuilder();
        ollamaApi.streamingChat(request).doOnNext(chunk -> {
            if (chunk.message() != null && chunk.message().content() != null) {
                aggregated.append(chunk.message().content());
            }
        }).blockLast();

        String content = aggregated.toString();
        logger.info("Streaming aggregated response: {}", content);

        Assertions.assertNotNull(content);
        Assertions.assertFalse(content.isBlank());
    }

    /**
     * 查询本机 Ollama 中可用的模型列表，并记录日志。
     *
     * @return 模型列表，可能为空但不会为 null
     */
    private List<OllamaApi.Model> queryLocalModels() {
        OllamaApi.ListModelResponse listModelResponse = ollamaApi.listModels();
        List<OllamaApi.Model> models = listModelResponse.models();
        if (models == null) {
            logger.warn("Ollama returned null model list, treating as empty.");
            return List.of();
        }
        logger.info("Found {} local Ollama models.", models.size());
        return models;
    }

    /**
     * 在给定的模型列表中优先选择 PREFERRED_MODEL_NAME，
     * 若不存在则回退到列表中的第一个模型。
     *
     * @param models 可用模型列表
     * @return 实际使用的模型名称
     */
    private String resolveModelName(List<OllamaApi.Model> models) {
        for (OllamaApi.Model model : models) {
            if (model != null && PREFERRED_MODEL_NAME.equals(model.name())) {
                logger.info("Using preferred model: {}", PREFERRED_MODEL_NAME);
                return model.name();
            }
        }
        String fallbackName = models.get(0).name();
        logger.info("Preferred model [{}] not found, fallback to first model: {}", PREFERRED_MODEL_NAME, fallbackName);
        return fallbackName;
    }

    /**
     * 构造单轮对话的 ChatRequest，包含一个 USER 消息。
     *
     * @param modelName 要使用的模型名称
     * @param prompt    用户输入的提示词
     * @return ChatRequest 对象
     */
    private OllamaApi.ChatRequest buildSingleTurnChatRequest(String modelName, String prompt) {
        return OllamaApi.ChatRequest.builder(modelName)
                .stream(false)
                .messages(List.of(OllamaApi.Message.builder(OllamaApi.Message.Role.USER).content(prompt).build()))
                .build();
    }

    /**
     * 从 ChatResponse 中提取文本内容，并记录日志。
     *
     * @param response Ollama 返回的响应
     * @return 文本内容，可能为 null
     */
    private String extractMessageContent(OllamaApi.ChatResponse response) {
        Assertions.assertNotNull(response, "Ollama chat response must not be null.");
        if (response.message() == null) {
            logger.warn("Ollama response has no message object.");
            return null;
        }
        String content = response.message().content();
        logger.info("Ollama response: {}", content);
        return content;
    }

}
