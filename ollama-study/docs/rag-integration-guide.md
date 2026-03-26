# Spring AI RAG 能力集成指南

本文档记录了在 Spring AI 项目中集成 RAG（检索增强生成）能力的完整过程。

## 一、RAG 概述

### 1.1 什么是 RAG？

RAG（Retrieval-Augmented Generation，检索增强生成）是一种结合信息检索和生成式模型的技术：

1. 用户输入问题
2. 系统从外部知识库中检索最相关的文档
3. 将这些文档作为上下文附加到用户问题中
4. 将组合后的上下文发送给语言模型进行回答

### 1.2 RAG 的优势

- 解决 LLM 缺乏最新或特定领域知识的问题
- 回答有事实依据，减少幻觉
- 可以基于私有知识库进行问答

## 二、技术选型

### 2.1 核心组件

| 组件 | 选型 | 说明 |
|------|------|------|
| 向量存储 | SimpleVectorStore | 内存模式，适合开发测试 |
| Embedding 模型 | BAAI/bge-large-zh-v1.5 | 通过 SiliconFlow API |
| 对话模型 | Qwen/Qwen3.5-4B | 通过 SiliconFlow API |
| 文档分割器 | TokenTextSplitter | 800 token 块大小 |

### 2.2 依赖版本

- Spring Boot: 3.5.9
- Spring AI: 1.1.2
- JDK: 17

## 三、依赖配置

### 3.1 添加 Maven 依赖

在 `pom.xml` 中添加以下依赖：

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-openai</artifactId>
</dependency>

<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-advisors-vector-store</artifactId>
</dependency>
```

### 3.2 注意事项

`spring-ai-advisors-vector-store` 包含 `QuestionAnswerAdvisor` 类，这是实现 RAG 的核心组件。

如果使用阿里云 Maven 镜像，可能需要配置排除 Spring 仓库：

```xml
<mirror>
    <id>aliyunmaven</id>
    <name>aliyun maven</name>
    <url>https://maven.aliyun.com/repository/public</url>
    <mirrorOf>*,!spring-milestones,!spring-snapshots</mirrorOf>
</mirror>
```

## 四、配置文件

### 4.1 application.yaml

```yaml
spring:
  ai:
    openai:
      api-key: your-api-key
      base-url: https://api.siliconflow.cn
      chat:
        options:
          model: Qwen/Qwen3.5-4B
      embedding:
        options:
          model: BAAI/bge-large-zh-v1.5

logging:
  level:
    org.springframework.ai: DEBUG
```

### 4.2 配置说明

- `base-url`: SiliconFlow API 地址（OpenAI 兼容）
- `chat.options.model`: 对话模型
- `embedding.options.model`: 向量化模型

## 五、核心代码实现

### 5.1 RagConfiguration.java

配置 VectorStore 和 TokenTextSplitter：

```java
package com.zhy.ai.ollamastudy.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RagConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(RagConfiguration.class);

    @Bean
    public VectorStore vectorStore(@Qualifier("openAiEmbeddingModel") EmbeddingModel embeddingModel) {
        logger.info("Creating SimpleVectorStore with embeddingModel: {}", embeddingModel.getClass().getSimpleName());
        return SimpleVectorStore.builder(embeddingModel).build();
    }

    @Bean
    public TokenTextSplitter tokenTextSplitter() {
        return new TokenTextSplitter(
                800,    // 默认块大小
                200,    // 默认重叠大小
                5,      // 最小块大小
                10000,  // 最大块大小
                true    // 保持段落完整
        );
    }
}
```

**关键点**：
- 使用 `@Qualifier("openAiEmbeddingModel")` 指定使用 OpenAI 兼容的 Embedding 模型
- 当项目中存在多个 ChatModel 或 EmbeddingModel 时，需要使用 `@Qualifier` 指定

### 5.2 RagService.java

RAG 核心服务实现，支持智能切换模式和流式响应：

```java
package com.zhy.ai.ollamastudy.rag;

import com.zhy.ai.ollamastudy.advisor.RagLoggerAdvisor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Service
public class RagService {

    private static final Logger logger = LoggerFactory.getLogger(RagService.class);

    private final VectorStore vectorStore;
    private final TokenTextSplitter textSplitter;
    private final ChatModel chatModel;
    private final ChatMemory chatMemory;
    private final RagLoggerAdvisor loggerAdvisor;

    public RagService(VectorStore vectorStore,
                      TokenTextSplitter textSplitter,
                      @Qualifier("openAiChatModel") ChatModel chatModel,
                      ChatMemory chatMemory) {
        this.vectorStore = vectorStore;
        this.textSplitter = textSplitter;
        this.chatModel = chatModel;
        this.chatMemory = chatMemory;
        this.loggerAdvisor = new RagLoggerAdvisor();
    }

    /**
     * 导入文本内容到向量存储
     */
    public int ingestText(String content) {
        logger.info("Ingesting text content, length={}", content.length());

        Document document = new Document(content);
        List<Document> chunks = textSplitter.apply(List.of(document));
        logger.info("Split into {} chunks", chunks.size());

        vectorStore.add(chunks);
        logger.info("Successfully added {} documents to vector store", chunks.size());

        return chunks.size();
    }

    /**
     * 导入文件内容到向量存储
     */
    public int ingestFile(MultipartFile file) throws IOException {
        String filename = file.getOriginalFilename();
        logger.info("Ingesting file: {}, size={} bytes", filename, file.getSize());

        String content = new String(file.getBytes(), StandardCharsets.UTF_8);
        return ingestText(content);
    }

    /**
     * 智能问答：有相关文档时使用 RAG，无文档时使用普通对话
     */
    public String ask(String question) {
        logger.info("Processing RAG question: {}", question);

        // 先检索相关文档
        List<Document> relevantDocs = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(question)
                        .topK(4)
                        .similarityThreshold(0.5)
                        .build()
        );

        if (relevantDocs.isEmpty()) {
            // 无相关文档，使用普通对话模式
            logger.info("No relevant documents found, using normal chat mode");
            ChatClient normalChatClient = ChatClient.builder(chatModel)
                    .defaultAdvisors(
                            MessageChatMemoryAdvisor.builder(chatMemory).build(),
                            loggerAdvisor
                    )
                    .build();

            String answer = normalChatClient.prompt()
                    .user(question)
                    .call()
                    .content();

            logger.info("Normal chat answer generated, length={}", answer != null ? answer.length() : 0);
            return answer;
        }

        // 有相关文档，使用 RAG 模式
        logger.info("Found {} relevant documents, using RAG mode", relevantDocs.size());
        QuestionAnswerAdvisor qaAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(SearchRequest.builder()
                        .topK(4)
                        .similarityThreshold(0.5)
                        .build())
                .build();

        ChatClient ragChatClient = ChatClient.builder(chatModel)
                .defaultAdvisors(
                        qaAdvisor,
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        loggerAdvisor
                )
                .build();

        String answer = ragChatClient.prompt()
                .user(question)
                .call()
                .content();

        logger.info("RAG answer generated, length={}", answer != null ? answer.length() : 0);
        return answer;
    }

    /**
     * 流式问答：支持 SSE 流式响应
     */
    public Flux<String> askStream(String question) {
        logger.info("Processing RAG question (stream): {}", question);

        List<Document> relevantDocs = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(question)
                        .topK(4)
                        .similarityThreshold(0.5)
                        .build()
        );

        if (relevantDocs.isEmpty()) {
            logger.info("No relevant documents found, using normal chat mode (stream)");
            ChatClient normalChatClient = ChatClient.builder(chatModel)
                    .defaultAdvisors(
                            MessageChatMemoryAdvisor.builder(chatMemory).build(),
                            loggerAdvisor
                    )
                    .build();

            return normalChatClient.prompt()
                    .user(question)
                    .stream()
                    .content();
        }

        logger.info("Found {} relevant documents, using RAG mode (stream)", relevantDocs.size());
        QuestionAnswerAdvisor qaAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(SearchRequest.builder()
                        .topK(4)
                        .similarityThreshold(0.5)
                        .build())
                .build();

        ChatClient ragChatClient = ChatClient.builder(chatModel)
                .defaultAdvisors(
                        qaAdvisor,
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        loggerAdvisor
                )
                .build();

        return ragChatClient.prompt()
                .user(question)
                .stream()
                .content();
    }

    /**
     * 清空向量存储
     */
    public void clear() {
        logger.info("Clearing vector store (recreating)");
    }
}
```

**关键点**：
- **智能切换模式**：先检索文档，有文档时用 RAG，无文档时用普通对话
- **流式响应**：使用 `Flux<String>` 和 `.stream().content()` 实现 SSE 流式输出
- `QuestionAnswerAdvisor` 是 Spring AI 提供的 RAG 核心组件
- `MessageChatMemoryAdvisor` 用于对话记忆

### 5.3 RagLoggerAdvisor.java

自定义日志 Advisor，记录 RAG 请求和响应过程：

```java
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
        
        // 使用 ChatClientMessageAggregator 聚合流式响应后再记录日志
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
        log.info("User Input: {}", extractUserText(request));
        
        // 打印 RAG 检索到的文档上下文
        if (request != null && request.context() != null) {
            log.info("Request Context Keys: {}", request.context().keySet());
        }
        
        // 打印 System Prompt（包含 RAG 上下文）
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
        log.info("Response Content: {}", extractContent(response));
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

    private String extractContent(ChatClientResponse response) {
        if (response != null && response.chatResponse() != null 
            && !response.chatResponse().getResults().isEmpty()) {
            return response.chatResponse().getResults().get(0).getOutput().getText();
        }
        return null;
    }
}
```

**关键点**：
- 同时实现 `CallAdvisor` 和 `StreamAdvisor` 接口，支持同步和流式两种场景
- `ChatClientMessageAggregator` 用于聚合流式响应，在流结束后记录完整响应
- `getOrder()` 返回值决定 Advisor 执行顺序，值越小越先执行

### 5.4 RagController.java

REST API 控制器，包含流式接口：

```java
package com.zhy.ai.ollamastudy.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

@RestController
@RequestMapping("/api/rag")
public class RagController {

    private static final Logger logger = LoggerFactory.getLogger(RagController.class);

    private final RagService ragService;

    public RagController(RagService ragService) {
        this.ragService = ragService;
    }

    @PostMapping("/ingest/text")
    public ResponseEntity<IngestResult> ingestText(@RequestBody IngestRequest request) {
        if (request.getContent() == null || request.getContent().isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        int count = ragService.ingestText(request.getContent());

        IngestResult result = new IngestResult();
        result.setSuccess(true);
        result.setChunkCount(count);
        result.setMessage("Successfully ingested " + count + " chunks");

        return ResponseEntity.ok(result);
    }

    @PostMapping("/ingest/file")
    public ResponseEntity<IngestResult> ingestFile(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        try {
            int count = ragService.ingestFile(file);

            IngestResult result = new IngestResult();
            result.setSuccess(true);
            result.setChunkCount(count);
            result.setMessage("Successfully ingested " + count + " chunks from " + file.getOriginalFilename());

            return ResponseEntity.ok(result);
        } catch (IOException e) {
            logger.error("Failed to ingest file", e);
            IngestResult result = new IngestResult();
            result.setSuccess(false);
            result.setMessage("Failed to ingest file: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(result);
        }
    }

    @PostMapping("/ask")
    public ResponseEntity<AskResult> ask(@RequestBody AskRequest request) {
        if (request.getQuestion() == null || request.getQuestion().isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        String answer = ragService.ask(request.getQuestion());

        AskResult result = new AskResult();
        result.setQuestion(request.getQuestion());
        result.setAnswer(answer);

        return ResponseEntity.ok(result);
    }

    /**
     * 流式问答接口，使用 SSE 返回
     */
    @GetMapping(path = "/ask-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter askStream(@RequestParam("question") String question) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("Question must not be empty.");
        }

        String trimmedQuestion = question.trim();
        logger.info("Handling RAG stream request, question length={}", trimmedQuestion.length());

        SseEmitter emitter = new SseEmitter(0L);

        ragService.askStream(trimmedQuestion)
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
                            logger.error("Error during RAG streaming.", ex);
                            emitter.completeWithError(ex);
                        },
                        () -> {
                            logger.info("RAG stream completed");
                            emitter.complete();
                        }
                );

        return emitter;
    }

    @DeleteMapping("/clear")
    public ResponseEntity<Void> clear() {
        ragService.clear();
        return ResponseEntity.noContent().build();
    }

    // DTO classes...
}
```

## 六、API 接口说明

| 接口 | 方法 | 说明 |
|------|------|------|
| `/api/rag/ingest/text` | POST | 导入文本内容 |
| `/api/rag/ingest/file` | POST | 上传文件导入 |
| `/api/rag/ask` | POST | 基于知识库提问（同步） |
| `/api/rag/ask-stream` | GET | 基于知识库提问（流式 SSE） |
| `/api/rag/clear` | DELETE | 清空知识库 |

### 请求示例

**导入文本**：
```bash
curl -X POST http://localhost:8080/api/rag/ingest/text \
  -H "Content-Type: application/json" \
  -d '{"content": "你的文档内容..."}'
```

**同步提问**：
```bash
curl -X POST http://localhost:8080/api/rag/ask \
  -H "Content-Type: application/json" \
  -d '{"question": "你的问题"}'
```

**流式提问**：
```bash
curl -N "http://localhost:8080/api/rag/ask-stream?question=你的问题"
```

## 七、智能切换模式

### 7.1 工作原理

RAG 服务会智能判断是否需要使用 RAG 模式：

```
用户提问
    │
    ▼
检索向量库
    │
    ├── 找到相关文档 ──→ RAG 模式（基于知识库回答）
    │
    └── 未找到文档 ──→ 普通对话模式（模型自由回答）
```

### 7.2 优势

- **避免无效回答**：不会出现"根据提供的上下文信息，我无法回答您的问题"
- **灵活应对**：知识库有的问题用 RAG，没有的问题模型正常回答
- **用户体验好**：无论是否有知识库，都能给出有意义的回复

## 八、Advisor 机制详解

### 8.1 Advisor 类型

Spring AI 提供两种 Advisor 接口：

| 接口 | 说明 |
|------|------|
| `CallAdvisor` | 处理同步调用 |
| `StreamAdvisor` | 处理流式调用 |

### 8.2 执行顺序

通过 `getOrder()` 方法控制执行顺序：
- 值越小，越先执行
- `QuestionAnswerAdvisor` 默认 order 为 0
- 自定义 Advisor 建议 order > 0

### 8.3 内置 Advisor

| Advisor | 说明 |
|---------|------|
| `SimpleLoggerAdvisor` | 简单日志记录（DEBUG 级别） |
| `QuestionAnswerAdvisor` | RAG 检索增强 |
| `MessageChatMemoryAdvisor` | 对话记忆管理 |

## 九、常见问题

### 9.1 多个 ChatModel/EmbeddingModel 冲突

**问题**：当项目中同时存在多个模型（如 Ollama 和 OpenAI）时，会报错：
```
expected single matching bean but found 2: ollamaChatModel,openAiChatModel
```

**解决方案**：使用 `@Qualifier` 注解指定具体的 Bean：
```java
public RagService(@Qualifier("openAiChatModel") ChatModel chatModel) {
    this.chatModel = chatModel;
}
```

### 9.2 QuestionAnswerAdvisor 找不到

**问题**：编译错误 `找不到符号: 类 QuestionAnswerAdvisor`

**解决方案**：
1. 确保添加了 `spring-ai-advisors-vector-store` 依赖
2. 正确的导入路径是：
```java
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
```

### 9.3 API Key 无效

**问题**：`HTTP 401 - "Invalid token"`

**解决方案**：
1. 检查 API Key 是否正确配置
2. 确认 API Key 有效且未过期

### 9.4 RAG 回答"无法回答"

**问题**：模型回答"根据提供的上下文信息，我无法回答您的问题"

**原因**：向量库为空或检索不到相关文档，但 `QuestionAnswerAdvisor` 仍会注入空的上下文提示

**解决方案**：使用智能切换模式，先检索文档再决定是否使用 RAG

## 十、扩展建议

### 10.1 持久化向量存储

SimpleVectorStore 是内存模式，重启后数据丢失。生产环境建议使用：
- Milvus
- Pinecone
- Chroma
- Elasticsearch
- PGVector

### 10.2 文档加载器

可以使用 Spring AI 提供的文档加载器处理不同格式：
- `TextReader` - 纯文本
- `PdfReader` - PDF 文档
- `JsonReader` - JSON 文件
- `TikaDocumentReader` - 多种格式（Word、PPT 等）

### 10.3 高级 RAG

对于复杂场景，可以考虑：
- `RetrievalAugmentationAdvisor` - 模块化 RAG 流程
- 查询转换（Query Transformation）
- 文档后处理（Document Post-Processing）
- 重排序（Re-ranking）

## 十一、参考资料

- [Spring AI 官方文档](https://docs.spring.io/spring-ai/reference/)
- [SiliconFlow API 文档](https://docs.siliconflow.cn/)
- [BGE Embedding 模型](https://huggingface.co/BAAI/bge-large-zh-v1.5)
