package com.zhy.ai.ollamastudy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 基于 Ollama 的 REST 控制器，演示：
 * 1. 使用 {@link ChatClient}（高层 API）进行聊天；
 * 2. 使用 {@link OllamaApi}（底层 API）列出模型、发起聊天请求。
 */
@RestController
@RequestMapping("/api/ollama")
public class OllamaChatController {

    private static final Logger logger = LoggerFactory.getLogger(OllamaChatController.class);

    private final ChatClient chatClient;

    private final OllamaApi ollamaApi;

    /**
     * 通过构造函数注入 OllamaChatModel 和 OllamaApi。
     * 使用 OllamaChatModel 构建 ChatClient，方便调用高层聊天接口。
     *
     * @param ollamaChatModel Spring AI 自动配置的 OllamaChatModel
     * @param ollamaApi       Spring AI 提供的底层 Ollama 客户端
     */
    public OllamaChatController(OllamaChatModel ollamaChatModel, OllamaApi ollamaApi) {
        this.chatClient = ChatClient.builder(ollamaChatModel).build();
        this.ollamaApi = ollamaApi;
    }

    /**
     * 列出当前本机 Ollama 中可用的所有模型。
     *
     * @return 模型信息列表
     */
    @GetMapping("/models")
    public ResponseEntity<List<ModelInfo>> listModels() {
        OllamaApi.ListModelResponse listModelResponse = ollamaApi.listModels();
        List<OllamaApi.Model> models = listModelResponse.models();
        List<ModelInfo> result = new ArrayList<>();
        if (models != null) {
            for (OllamaApi.Model model : models) {
                if (model == null) {
                    continue;
                }
                ModelInfo info = new ModelInfo();
                info.setName(model.name());
                info.setModel(model.model());
                info.setSize(model.size());
                if (model.details() != null) {
                    info.setFamily(model.details().family());
                    info.setParameterSize(model.details().parameterSize());
                    info.setQuantizationLevel(model.details().quantizationLevel());
                }
                result.add(info);
            }
        }
        logger.info("REST /models returning {} models.", result.size());
        return ResponseEntity.ok(result);
    }

    /**
     * 使用 ChatClient（高层 API）进行一次简单对话。
     *
     * @param request 包含用户输入的 prompt
     * @return 使用配置模型（例如 qwen2.5-coder:7b）的回复结果
     */
    @PostMapping("/chat")
    public ResponseEntity<ChatResult> chatWithHighLevelApi(@RequestBody ChatRequest request) {
        if (!StringUtils.hasText(request.getPrompt())) {
            logger.warn("Received empty prompt for /chat.");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }

        String prompt = request.getPrompt().trim();
        logger.info("Handling /chat request, prompt length={}", prompt.length());

        String content = chatClient.prompt()
                .user(prompt)
                .call()
                .content();

        ChatResult result = new ChatResult();
        result.setModel("spring-ai-chat-client-default");
        result.setContent(content);
        return ResponseEntity.ok(result);
    }

    /**
     * 使用底层 OllamaApi 进行对话调用，显式指定模型名称。
     * 便于学习 ChatRequest / ChatResponse 的结构和使用方式。
     *
     * @param request 包含用户输入的 prompt
     * @return 使用底层 API 调用返回的结果
     */
    @PostMapping("/chat-low")
    public ResponseEntity<ChatResult> chatWithLowLevelApi(@RequestBody ChatRequest request) {
        if (!StringUtils.hasText(request.getPrompt())) {
            logger.warn("Received empty prompt for /chat-low.");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }

        String prompt = request.getPrompt().trim();
        logger.info("Handling /chat-low request, prompt length={}", prompt.length());

        String modelName = resolvePreferredModelName();
        if (!StringUtils.hasText(modelName)) {
            logger.warn("No available Ollama model when calling /chat-low.");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }

        OllamaApi.ChatRequest chatRequest = OllamaApi.ChatRequest.builder(modelName)
                .stream(false)
                .messages(List.of(OllamaApi.Message.builder(OllamaApi.Message.Role.USER).content(prompt).build()))
                .build();

        OllamaApi.ChatResponse response = ollamaApi.chat(chatRequest);
        if (response == null || response.message() == null || response.message().content() == null) {
            logger.warn("OllamaApi returned empty response for /chat-low.");
            return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
        }

        ChatResult result = new ChatResult();
        result.setModel(modelName);
        result.setContent(response.message().content());
        return ResponseEntity.ok(result);
    }

    @GetMapping(path = "/chat-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestParam("prompt") String prompt) {
        if (!StringUtils.hasText(prompt)) {
            throw new IllegalArgumentException("Prompt must not be empty.");
        }
        String trimmedPrompt = prompt.trim();
        logger.info("Handling /chat-stream request, prompt length={}", trimmedPrompt.length());

        String modelName = resolvePreferredModelName();
        if (!StringUtils.hasText(modelName)) {
            throw new IllegalStateException("No available Ollama model for streaming.");
        }

        SseEmitter emitter = new SseEmitter(0L);

        OllamaApi.ChatRequest chatRequest = OllamaApi.ChatRequest.builder(modelName)
                .stream(true)
                .messages(List.of(OllamaApi.Message.builder(OllamaApi.Message.Role.USER).content(trimmedPrompt).build()))
                .build();

        ollamaApi.streamingChat(chatRequest).subscribe(
                chunk -> {
                    if (chunk.message() == null || chunk.message().content() == null) {
                        return;
                    }
                    try {
                        emitter.send(SseEmitter.event().data(chunk.message().content()));
                    }
                    catch (IOException e) {
                        logger.warn("Failed to send SSE chunk.", e);
                        emitter.completeWithError(e);
                    }
                },
                ex -> {
                    logger.error("Error during streaming chat.", ex);
                    emitter.completeWithError(ex);
                },
                emitter::complete
        );

        return emitter;
    }

    /**
     * 从本地模型列表中解析首选模型名称，如果没有找到则返回第一个模型名称。
     *
     * @return 可用的模型名称，若完全没有模型则返回 null
     */
    private String resolvePreferredModelName() {
        OllamaApi.ListModelResponse listModelResponse = ollamaApi.listModels();
        List<OllamaApi.Model> models = listModelResponse.models();
        if (models == null || models.isEmpty()) {
            return null;
        }
        for (OllamaApi.Model model : models) {
            if (model != null && Objects.equals("qwen2.5-coder:7b", model.name())) {
                return model.name();
            }
        }
        return models.get(0).name();
    }

    /**
     * REST 请求体对象，仅包含一个 prompt 字段，代表用户输入。
     */
    public static class ChatRequest {

        /**
         * 用户输入的自然语言提示词。
         */
        private String prompt;

        public String getPrompt() {
            return prompt;
        }

        public void setPrompt(String prompt) {
            this.prompt = prompt;
        }
    }

    /**
     * REST 响应体对象，包含模型名称和生成的文本内容。
     */
    public static class ChatResult {

        /**
         * 实际使用的模型名称。
         */
        private String model;

        /**
         * 模型返回的文本内容。
         */
        private String content;

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }
    }

    /**
     * 模型基本信息，用于 REST 输出。
     */
    public static class ModelInfo {

        /**
         * 模型在 Ollama 中的完整名称，例如 qwen2.5-coder:7b。
         */
        private String name;

        /**
         * 模型标识字段，一般与 name 类似。
         */
        private String model;

        /**
         * 模型文件大小（字节数）。
         */
        private long size;

        /**
         * 模型家族名称，例如 llama、qwen 等。
         */
        private String family;

        /**
         * 模型参数规模，例如 7B、13B。
         */
        private String parameterSize;

        /**
         * 量化等级，例如 Q4_K_M。
         */
        private String quantizationLevel;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public long getSize() {
            return size;
        }

        public void setSize(long size) {
            this.size = size;
        }

        public String getFamily() {
            return family;
        }

        public void setFamily(String family) {
            this.family = family;
        }

        public String getParameterSize() {
            return parameterSize;
        }

        public void setParameterSize(String parameterSize) {
            this.parameterSize = parameterSize;
        }

        public String getQuantizationLevel() {
            return quantizationLevel;
        }

        public void setQuantizationLevel(String quantizationLevel) {
            this.quantizationLevel = quantizationLevel;
        }
    }

}
