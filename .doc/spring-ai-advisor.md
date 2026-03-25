# Spring AI Advisor 使用指南

## 一、什么是 Advisor？

Spring AI Advisor 是连接 AI 模型与业务逻辑的核心中间件，其设计理念与 Spring AOP（切面编程）深度契合。它提供了一种灵活而强大的方法来拦截、修改和增强 Spring 应用程序中的 AI 驱动交互。

### 核心优势

- **封装重复的生成式 AI 模式**：如对话历史管理、敏感词过滤
- **转换与大语言模型（LLM）交互的数据**：如动态附加上下文或格式化输入/输出
- **实现跨模型和用例的可移植性**：通过统一接口适配不同 AI 服务

### 类比理解

如果你熟悉以下概念，Advisor 就是它们的"AI 版本"：

| 概念 | 说明 |
|------|------|
| Servlet Filter | 请求/响应拦截器 |
| Spring AOP | 切面编程 |
| Spring Interceptor | 方法拦截器 |

---

## 二、Advisor 工作原理

### 执行流程图

```
┌─────────────────────────────────────────────────────────────────┐
│                        用户请求                                  │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                    Advisor Chain (请求阶段)                      │
│  ┌──────────────┐   ┌──────────────┐   ┌──────────────┐        │
│  │ Advisor A    │──▶│ Advisor B    │──▶│ Advisor C    │        │
│  │ (Order=0)    │   │ (Order=100)  │   │ (Order=200)  │        │
│  │ before()     │   │ before()     │   │ before()     │        │
│  └──────────────┘   └──────────────┘   └──────────────┘        │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                      Chat Model (LLM)                           │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                    Advisor Chain (响应阶段)                      │
│  ┌──────────────┐   ┌──────────────┐   ┌──────────────┐        │
│  │ Advisor C    │──▶│ Advisor B    │──▶│ Advisor A    │        │
│  │ (Order=200)  │   │ (Order=100)  │   │ (Order=0)    │        │
│  │ after()      │   │ after()      │   │ after()      │        │
│  └──────────────┘   └──────────────┘   └──────────────┘        │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                       返回给用户                                 │
└─────────────────────────────────────────────────────────────────┘
```

### 栈式执行特性

Advisor 链以**栈**的形式运行：

1. **请求阶段**：Order 值越小，越先执行（先进栈）
2. **响应阶段**：Order 值越小，越后执行（后出栈）

```
请求流向：Advisor A → Advisor B → Advisor C → LLM
响应流向：Advisor C → Advisor B → Advisor A → 用户
```

---

## 三、核心接口

### 接口层次结构

```
Advisor (顶层接口)
   │
   ├── CallAroundAdvisor (同步/非流式)
   │
   ├── StreamAroundAdvisor (流式)
   │
   └── BaseAdvisor (提供默认实现)
           │
           ├── MessageChatMemoryAdvisor
           ├── PromptChatMemoryAdvisor
           └── VectorStoreChatMemoryAdvisor
```

### 核心接口定义

```java
public interface Advisor extends Ordered {
    
    int DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER = Ordered.HIGHEST_PRECEDENCE + 1000;
    
    String getName();
}

public interface CallAroundAdvisor extends Advisor {
    
    AdvisedResponse aroundCall(AdvisedRequest advisedRequest, CallAroundAdvisorChain chain);
}

public interface StreamAroundAdvisor extends Advisor {
    
    Flux<AdvisedResponse> aroundStream(AdvisedRequest advisedRequest, StreamAroundAdvisorChain chain);
}
```

### 核心数据结构

| 类 | 说明 |
|-----|------|
| `AdvisedRequest` | 封装的请求对象，包含用户输入、系统提示、上下文等 |
| `AdvisedResponse` | 封装的响应对象，包含 AI 回复、元数据等 |
| `advisorContext` | Map<String, Object>，用于在 Advisor 链中共享状态 |

---

## 四、内置 Advisor

### 4.1 SimpleLoggerAdvisor（日志记录）

用于记录 AI 调用的请求和响应信息。

```java
SimpleLoggerAdvisor loggerAdvisor = new SimpleLoggerAdvisor();

ChatClient chatClient = ChatClient.builder(chatModel)
        .defaultAdvisors(loggerAdvisor)
        .build();
```

**注意**：SimpleLoggerAdvisor 默认使用 DEBUG 级别输出日志。如需在 INFO 级别查看日志，需要配置：

```yaml
logging:
  level:
    org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor: debug
```

### 4.2 MessageChatMemoryAdvisor（消息记忆）

将对话历史以消息列表形式附加到请求中。

```java
ChatMemory chatMemory = MessageWindowChatMemory.builder()
        .chatMemoryRepository(new InMemoryChatMemoryRepository())
        .maxMessages(20)
        .build();

ChatClient chatClient = ChatClient.builder(chatModel)
        .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
        .build();

chatClient.prompt()
        .user("你好")
        .advisors(advisor -> advisor
                .param("chat_memory_conversation_id", "conv-001"))
        .call()
        .content();
```

### 4.3 PromptChatMemoryAdvisor（提示词记忆）

将对话历史转换为文本形式，嵌入到系统提示词中。

```java
ChatClient chatClient = ChatClient.builder(chatModel)
        .defaultAdvisors(new PromptChatMemoryAdvisor(chatMemory))
        .build();
```

**适用场景**：当 LLM 不支持 messages 参数时使用。

### 4.4 VectorStoreChatMemoryAdvisor（向量存储记忆）

从向量数据库中检索相关的历史记忆。

```java
VectorStoreChatMemoryAdvisor advisor = VectorStoreChatMemoryAdvisor.builder()
        .vectorStore(vectorStore)
        .systemTextAdvise("历史记忆：{long_term_memory}")
        .build();
```

**适用场景**：需要语义检索历史对话的场景。

---

## 五、自定义 Advisor

### 5.1 实现步骤

1. **选择接口**：实现 `CallAroundAdvisor`（同步）、`StreamAroundAdvisor`（流式）或两者
2. **实现核心方法**：`aroundCall()` 和/或 `aroundStream()`
3. **设置执行顺序**：通过 `getOrder()` 方法
4. **命名**：通过 `getName()` 方法返回唯一名称

### 5.2 示例：自定义日志 Advisor

```java
@Slf4j
public class MyLoggerAdvisor implements CallAroundAdvisor, StreamAroundAdvisor {

    @Override
    public String getName() {
        return this.getClass().getSimpleName();
    }

    @Override
    public int getOrder() {
        return 0;
    }

    private AdvisedRequest before(AdvisedRequest request) {
        log.info("AI Request: {}", request.userText());
        return request;
    }

    private void observeAfter(AdvisedResponse advisedResponse) {
        log.info("AI Response: {}", 
            advisedResponse.response().getResult().getOutput().getContent());
    }

    @Override
    public AdvisedResponse aroundCall(AdvisedRequest advisedRequest, 
                                       CallAroundAdvisorChain chain) {
        advisedRequest = this.before(advisedRequest);
        AdvisedResponse advisedResponse = chain.nextAroundCall(advisedRequest);
        this.observeAfter(advisedResponse);
        return advisedResponse;
    }

    @Override
    public Flux<AdvisedResponse> aroundStream(AdvisedRequest advisedRequest, 
                                               StreamAroundAdvisorChain chain) {
        advisedRequest = this.before(advisedRequest);
        Flux<AdvisedResponse> advisedResponses = chain.nextAroundStream(advisedRequest);
        return new MessageAggregator().aggregateAdvisedResponse(
                advisedResponses, this::observeAfter);
    }
}
```

### 5.3 示例：敏感词过滤 Advisor

```java
public class SensitiveWordFilterAdvisor implements CallAroundAdvisor, StreamAroundAdvisor {

    private final List<String> sensitiveWords = List.of("敏感词1", "敏感词2");

    @Override
    public String getName() {
        return "SensitiveWordFilterAdvisor";
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public AdvisedResponse aroundCall(AdvisedRequest advisedRequest, 
                                       CallAroundAdvisorChain chain) {
        String userText = advisedRequest.userText();
        String filteredText = filterSensitiveWords(userText);
        
        AdvisedRequest filteredRequest = AdvisedRequest.from(advisedRequest)
                .userText(filteredText)
                .build();
        
        AdvisedResponse response = chain.nextAroundCall(filteredRequest);
        
        String responseText = response.response().getResult().getOutput().getContent();
        String filteredResponse = filterSensitiveWords(responseText);
        
        return response;
    }

    @Override
    public Flux<AdvisedResponse> aroundStream(AdvisedRequest advisedRequest, 
                                               StreamAroundAdvisorChain chain) {
        String userText = advisedRequest.userText();
        String filteredText = filterSensitiveWords(userText);
        
        AdvisedRequest filteredRequest = AdvisedRequest.from(advisedRequest)
                .userText(filteredText)
                .build();
        
        return chain.nextAroundStream(filteredRequest)
                .map(response -> {
                    String content = response.response().getResult().getOutput().getContent();
                    String filtered = filterSensitiveWords(content);
                    return response;
                });
    }

    private String filterSensitiveWords(String text) {
        for (String word : sensitiveWords) {
            text = text.replace(word, "***");
        }
        return text;
    }
}
```

### 5.4 示例：请求增强 Advisor

```java
public class ContextEnrichmentAdvisor implements CallAroundAdvisor, StreamAroundAdvisor {

    @Override
    public String getName() {
        return "ContextEnrichmentAdvisor";
    }

    @Override
    public int getOrder() {
        return 100;
    }

    @Override
    public AdvisedResponse aroundCall(AdvisedRequest advisedRequest, 
                                       CallAroundAdvisorChain chain) {
        String enrichedSystemText = advisedRequest.systemText() + 
                "\n\n当前时间：" + LocalDateTime.now() +
                "\n用户语言：中文";

        AdvisedRequest enrichedRequest = AdvisedRequest.from(advisedRequest)
                .systemText(enrichedSystemText)
                .build();

        return chain.nextAroundCall(enrichedRequest);
    }

    @Override
    public Flux<AdvisedResponse> aroundStream(AdvisedRequest advisedRequest, 
                                               StreamAroundAdvisorChain chain) {
        String enrichedSystemText = advisedRequest.systemText() + 
                "\n\n当前时间：" + LocalDateTime.now();

        AdvisedRequest enrichedRequest = AdvisedRequest.from(advisedRequest)
                .systemText(enrichedSystemText)
                .build();

        return chain.nextAroundStream(enrichedRequest);
    }
}
```

---

## 六、使用场景

### 6.1 场景分类

| 场景 | 说明 | 推荐 Advisor |
|------|------|-------------|
| 对话记忆 | 保持多轮对话上下文 | MessageChatMemoryAdvisor |
| 日志记录 | 记录请求/响应用于调试 | SimpleLoggerAdvisor / 自定义 |
| 内容安全 | 过滤敏感词、违规内容 | 自定义敏感词过滤 Advisor |
| 上下文增强 | 添加时间、用户信息等 | 自定义上下文增强 Advisor |
| 权限控制 | 检查用户是否有权限调用 | 自定义权限 Advisor |
| 计费统计 | 统计 token 使用量 | 自定义计费 Advisor |
| RAG 增强 | 检索相关文档增强提示词 | VectorStoreChatMemoryAdvisor |
| 响应转换 | 格式化、翻译响应内容 | 自定义转换 Advisor |

### 6.2 场景详解

#### 对话记忆场景

```java
@Configuration
public class ChatMemoryConfiguration {

    @Bean
    public ChatMemoryRepository chatMemoryRepository() {
        return new InMemoryChatMemoryRepository();
    }

    @Bean
    public ChatMemory chatMemory(ChatMemoryRepository chatMemoryRepository) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(20)
                .build();
    }
}

@RestController
public class ChatController {

    private final ChatClient memoryChatClient;

    public ChatController(ChatModel chatModel, ChatMemory chatMemory) {
        this.memoryChatClient = ChatClient.builder(chatModel)
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        new SimpleLoggerAdvisor()
                )
                .build();
    }

    @PostMapping("/chat/{conversationId}")
    public String chat(@PathVariable String conversationId, @RequestBody String prompt) {
        return memoryChatClient.prompt()
                .user(prompt)
                .advisors(advisor -> advisor
                        .param("chat_memory_conversation_id", conversationId))
                .call()
                .content();
    }
}
```

#### RAG 场景

```java
public class RagEnhancementAdvisor implements CallAroundAdvisor {

    private final VectorStore vectorStore;

    @Override
    public String getName() {
        return "RagEnhancementAdvisor";
    }

    @Override
    public int getOrder() {
        return 50;
    }

    @Override
    public AdvisedResponse aroundCall(AdvisedRequest advisedRequest, 
                                       CallAroundAdvisorChain chain) {
        String userQuery = advisedRequest.userText();
        
        List<Document> relevantDocs = vectorStore.similaritySearch(userQuery);
        String context = relevantDocs.stream()
                .map(Document::getContent)
                .collect(Collectors.joining("\n\n"));

        String enhancedSystemText = advisedRequest.systemText() +
                "\n\n参考以下资料回答问题：\n" + context;

        AdvisedRequest enhancedRequest = AdvisedRequest.from(advisedRequest)
                .systemText(enhancedSystemText)
                .build();

        return chain.nextAroundCall(enhancedRequest);
    }
}
```

---

## 七、Advisor 顺序控制

### 7.1 顺序规则

```java
public interface Ordered {
    int HIGHEST_PRECEDENCE = Integer.MIN_VALUE;  // 最高优先级
    int LOWEST_PRECEDENCE = Integer.MAX_VALUE;   // 最低优先级
    
    int getOrder();  // 值越低优先级越高
}
```

### 7.2 执行顺序示例

```java
Advisor A: order = 0      // 最先处理请求，最后处理响应
Advisor B: order = 100    // 第二处理请求，倒数第二处理响应
Advisor C: order = 200    // 最后处理请求，最先处理响应
```

### 7.3 推荐顺序值

| Advisor 类型 | 推荐 Order 值 | 说明 |
|-------------|--------------|------|
| 权限校验 | 0-99 | 最先执行，阻止未授权请求 |
| 内容安全 | 100-199 | 早期过滤敏感内容 |
| 上下文增强 | 200-299 | 添加额外上下文信息 |
| 对话记忆 | 1000+ | 默认值，确保记忆最后添加 |
| 日志记录 | 0 或 MAX | 最先或最后记录完整信息 |

---

## 八、最佳实践

### 8.1 同时实现两个接口

建议同时实现 `CallAroundAdvisor` 和 `StreamAroundAdvisor`，以支持同步和流式两种调用方式。

### 8.2 使用 BaseAdvisor 简化开发

如果只需要简单的 before/after 处理，可以继承 `BaseAdvisor`：

```java
public class SimpleAdvisor implements BaseAdvisor {

    @Override
    public String getName() {
        return "SimpleAdvisor";
    }

    @Override
    public int getOrder() {
        return 0;
    }

    @Override
    public AdvisedRequest before(AdvisedRequest request) {
        return request;
    }

    @Override
    public AdvisedResponse after(AdvisedResponse response) {
        return response;
    }
}
```

### 8.3 流式响应聚合

处理流式响应时，使用 `MessageAggregator` 聚合完整响应：

```java
@Override
public Flux<AdvisedResponse> aroundStream(AdvisedRequest advisedRequest, 
                                           StreamAroundAdvisorChain chain) {
    Flux<AdvisedResponse> responses = chain.nextAroundStream(advisedRequest);
    
    return new MessageAggregator().aggregateAdvisedResponse(
            responses, 
            this::observeAfter
    );
}
```

### 8.4 阻塞操作保护

当 Advisor 中涉及阻塞 IO（如 JDBC）时，使用 `Schedulers.boundedElastic()` 保护：

```java
@Override
public Flux<AdvisedResponse> aroundStream(AdvisedRequest advisedRequest, 
                                           StreamAroundAdvisorChain chain) {
    return Mono.just(advisedRequest)
            .publishOn(Schedulers.boundedElastic())
            .map(this::blockingOperation)
            .flatMapMany(chain::nextAroundStream);
}
```

### 8.5 Advisor 上下文共享

通过 `advisorContext` 在 Advisor 链中共享状态：

```java
@Override
public AdvisedRequest before(AdvisedRequest request) {
    String userId = request.adviseContext().get("userId");
    request.adviseContext().put("startTime", System.currentTimeMillis());
    return request;
}

@Override
public AdvisedResponse after(AdvisedResponse response) {
    Long startTime = response.adviseContext().get("startTime");
    long duration = System.currentTimeMillis() - startTime;
    log.info("Request duration: {}ms", duration);
    return response;
}
```

---

## 九、项目中的实际应用

### 当前项目配置

```java
@Configuration
public class ChatMemoryConfiguration {

    @Bean
    public ChatMemoryRepository chatMemoryRepository() {
        return new InMemoryChatMemoryRepository();
    }

    @Bean
    public ChatMemory chatMemory(ChatMemoryRepository chatMemoryRepository) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(20)
                .build();
    }
}
```

### Controller 中使用

```java
@RestController
@RequestMapping("/api/openai")
public class OpenAiChatController {

    private final ChatClient chatClient;
    private final ChatClient memoryChatClient;

    public OpenAiChatController(OpenAiChatModel openAiChatModel, 
                                WeatherService weatherService, 
                                ChatMemory chatMemory) {
        SimpleLoggerAdvisor loggerAdvisor = new SimpleLoggerAdvisor();
        
        this.chatClient = ChatClient.builder(openAiChatModel)
                .defaultTools(weatherService)
                .defaultAdvisors(loggerAdvisor)
                .build();
        
        this.memoryChatClient = ChatClient.builder(openAiChatModel)
                .defaultTools(weatherService)
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        loggerAdvisor
                )
                .build();
    }

    @PostMapping("/chat/memory/{conversationId}")
    public ResponseEntity<ChatResult> chatWithMemory(
            @PathVariable String conversationId,
            @RequestBody ChatRequest request) {
        
        String content = memoryChatClient.prompt()
                .user(request.getPrompt())
                .advisors(advisor -> advisor
                        .param("chat_memory_conversation_id", conversationId))
                .call()
                .content();

        return ResponseEntity.ok(new ChatResult(content, conversationId));
    }
}
```

---

## 十、常见问题

### Q1: 为什么日志看不到 SimpleLoggerAdvisor 的输出？

**原因**：SimpleLoggerAdvisor 使用 DEBUG 级别输出日志。

**解决方案**：
1. 配置日志级别为 DEBUG
2. 或自定义 INFO 级别的日志 Advisor

### Q2: 多个 Advisor 的执行顺序如何确定？

**答案**：通过 `getOrder()` 方法的返回值确定，值越小优先级越高。请求阶段按升序执行，响应阶段按降序执行。

### Q3: 如何在 Advisor 中修改用户输入？

**答案**：通过 `AdvisedRequest.from()` 构建新的请求对象：

```java
AdvisedRequest modifiedRequest = AdvisedRequest.from(advisedRequest)
        .userText("修改后的内容")
        .build();
```

### Q4: 流式响应如何获取完整内容？

**答案**：使用 `MessageAggregator` 聚合流式响应：

```java
new MessageAggregator().aggregateAdvisedResponse(flux, this::handleComplete);
```

### Q5: Advisor 中如何访问用户信息？

**答案**：通过 `advisorContext` 传递：

```java
chatClient.prompt()
        .user("你好")
        .advisors(advisor -> advisor
                .param("userId", "user-001"))
        .call()
        .content();

String userId = advisedRequest.adviseContext().get("userId");
```

---

## 十一、总结

Spring AI Advisor 是一个强大的扩展机制，通过它可以实现：

1. **对话记忆**：自动管理多轮对话上下文
2. **日志记录**：记录请求/响应用于调试和审计
3. **内容安全**：过滤敏感词和违规内容
4. **上下文增强**：动态添加系统提示
5. **权限控制**：验证用户权限
6. **RAG 增强**：检索相关文档增强提示词

掌握 Advisor 机制，可以让你的 AI 应用更加灵活、可维护和可扩展！
