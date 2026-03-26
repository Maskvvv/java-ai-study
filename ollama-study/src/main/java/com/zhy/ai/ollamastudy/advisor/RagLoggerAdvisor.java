package com.zhy.ai.ollamastudy.advisor;

import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientMessageAggregator;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;

import reactor.core.publisher.Flux;

import java.util.concurrent.atomic.AtomicLong;

@Slf4j
public class RagLoggerAdvisor implements CallAdvisor, StreamAdvisor {

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        logRequest(request);
        
        long startTime = System.currentTimeMillis();
        ChatClientResponse response = chain.nextCall(request);
        long duration = System.currentTimeMillis() - startTime;
        
        logResponse(response, duration);
        return response;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        logRequest(request);
        
        long startTime = System.currentTimeMillis();
        
        Flux<ChatClientResponse> responses = chain.nextStream(request);
        
        return new ChatClientMessageAggregator().aggregateChatClientResponse(responses,
            response -> {
                long duration = System.currentTimeMillis() - startTime;
                logResponse(response, duration);
            });
    }

    @Override
    public String getName() {
        return this.getClass().getSimpleName();
    }

    @Override
    public int getOrder() {
        return 1;
    }

    private void logRequest(ChatClientRequest request) {
        log.info("=== RAG Request ===");
        log.info("Full Input: {}", JSON.toJSONString(request));


        String userText = extractUserText(request);
        log.info("User Input: {}", userText);
        
        if (request != null && request.context() != null) {
            log.info("Request Context Keys: {}", request.context().keySet());
            for (String key : request.context().keySet()) {
                Object value = request.context().get(key);
                log.info("Context [{}]: {}", key, JSON.toJSONString(value, true));
            }
        }
        
        if (request != null && request.prompt() != null) {
            String systemText = extractSystemText(request);
            if (systemText != null && !systemText.isEmpty()) {
                log.info("System Prompt (contains RAG context): \n{}", systemText);
            }
        }
        
        log.info("===================");
    }

    private void logResponse(ChatClientResponse response, long duration) {
        log.info("=== RAG Response ===");
        log.info("Duration: {} ms", duration);
        
        String content = extractContent(response);
        if (content != null) {
            log.info("Response Content (length={}): {}", content.length(), content);
        }
        
        log.info("Full Response: {}", JSON.toJSONString(response));
        log.info("====================");
    }

    private String extractUserText(ChatClientRequest request) {
        if (request != null && request.prompt() != null && request.prompt().getUserMessage() != null) {
            return request.prompt().getUserMessage().getText();
        }
        return "N/A";
    }

    private String extractSystemText(ChatClientRequest request) {
        if (request != null && request.prompt() != null && request.prompt().getSystemMessage() != null) {
            return request.prompt().getSystemMessage().getText();
        }
        return null;
    }

    private String extractRagContext(ChatClientRequest request) {
        if (request != null && request.context() != null) {
            Object documents = request.context().get("documents");
            if (documents != null) {
                return JSON.toJSONString(documents, true);
            }
        }
        return null;
    }

    private String extractContent(ChatClientResponse response) {
        if (response != null && response.chatResponse() != null && !response.chatResponse().getResults().isEmpty()) {
            return response.chatResponse().getResults().get(0).getOutput().getText();
        }
        return null;
    }
}
