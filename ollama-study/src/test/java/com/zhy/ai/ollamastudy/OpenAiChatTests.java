package com.zhy.ai.ollamastudy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import reactor.core.publisher.Flux;

@SpringBootTest
class OpenAiChatTests {

    private static final Logger logger = LoggerFactory.getLogger(OpenAiChatTests.class);

    @Autowired
    private OpenAiChatModel openAiChatModel;

    @Test
    void contextLoads() {
        logger.info("Spring Boot context loaded successfully.");
    }

    @Test
    void simpleOpenAiChatShouldReturnText() {
        String response = openAiChatModel.call("你好，请用一句话介绍你自己。");
        
        logger.info("OpenAI response: {}", response);
        
        Assertions.assertNotNull(response, "Response should not be null");
        Assertions.assertFalse(response.isBlank(), "Response should not be blank");
    }

    @Test
    void chatClientShouldReturnText() {
        ChatClient chatClient = ChatClient.create(openAiChatModel);
        
        String response = chatClient.prompt()
                .user("Say 'hello' in English using only one word.")
                .call()
                .content();
        
        logger.info("ChatClient response: {}", response);
        
        Assertions.assertNotNull(response, "Response should not be null");
        Assertions.assertFalse(response.isBlank(), "Response should not be blank");
    }

    @Test
    void streamChatShouldReturnChunks() {
        ChatClient chatClient = ChatClient.create(openAiChatModel);
        
        Flux<String> streamFlux = chatClient.prompt()
                .user("请用三句话介绍Spring框架。")
                .stream()
                .content();
        
        List<String> chunks = new ArrayList<>();
        StringBuilder fullResponse = new StringBuilder();
        
        streamFlux.doOnNext(chunk -> {
            logger.info("Received chunk: [{}]", chunk);
            chunks.add(chunk);
            fullResponse.append(chunk);
        }).blockLast();
        
        logger.info("Total chunks received: {}", chunks.size());
        logger.info("Full response: {}", fullResponse.toString());
        
        Assertions.assertTrue(chunks.size() > 1, "Should receive multiple chunks for streaming");
        Assertions.assertFalse(fullResponse.toString().isBlank(), "Full response should not be blank");
    }
}
