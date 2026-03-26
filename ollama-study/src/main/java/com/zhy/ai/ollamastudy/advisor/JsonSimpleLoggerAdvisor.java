package com.zhy.ai.ollamastudy.advisor;

import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;

@Slf4j
public class JsonSimpleLoggerAdvisor extends SimpleLoggerAdvisor {

    @Override
    protected void logRequest(ChatClientRequest request) {

        log.info("Request: {}", JSON.toJSONString(request));
    }

    @Override
    protected void logResponse(ChatClientResponse chatClientResponse) {
        log.info("response: {}", JSON.toJSONString(chatClientResponse));

    }
}
