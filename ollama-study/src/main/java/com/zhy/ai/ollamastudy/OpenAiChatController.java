package com.zhy.ai.ollamastudy;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
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

import com.zhy.ai.ollamastudy.mcp.WeatherService;

@RestController
@RequestMapping("/api/openai")
public class OpenAiChatController {

    private static final Logger logger = LoggerFactory.getLogger(OpenAiChatController.class);

    private final ChatClient chatClient;
    private final OpenAiChatModel openAiChatModel;

    public OpenAiChatController(OpenAiChatModel openAiChatModel, WeatherService weatherService) {
        this.openAiChatModel = openAiChatModel;
        this.chatClient = ChatClient.builder(openAiChatModel)
                .defaultTools(weatherService)
                .build();
    }

    @PostMapping("/chat")
    public ResponseEntity<ChatResult> chat(@RequestBody ChatRequest request) {
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
        result.setModel("SiliconFlow");
        result.setContent(content);
        return ResponseEntity.ok(result);
    }

    @GetMapping(path = "/chat-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestParam("prompt") String prompt) {
        if (!StringUtils.hasText(prompt)) {
            throw new IllegalArgumentException("Prompt must not be empty.");
        }
        String trimmedPrompt = prompt.trim();
        logger.info("Handling /chat-stream request, prompt length={}", trimmedPrompt.length());

        SseEmitter emitter = new SseEmitter(0L);

        chatClient.prompt()
                .user(trimmedPrompt)
                .stream()
                .content()
                .subscribe(
                        chunk -> {
                            if (chunk != null && !chunk.isEmpty()) {
                                try {
                                    emitter.send(SseEmitter.event().data(chunk));
                                } catch (IOException e) {
                                    logger.warn("Failed to send SSE chunk.", e);
                                    emitter.completeWithError(e);
                                }
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

    public static class ChatRequest {
        private String prompt;

        public String getPrompt() {
            return prompt;
        }

        public void setPrompt(String prompt) {
            this.prompt = prompt;
        }
    }

    public static class ChatResult {
        private String model;
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
}
