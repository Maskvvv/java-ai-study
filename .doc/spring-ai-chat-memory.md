# Spring AI Chat Memory 集成指南

## 概述

Chat Memory（对话记忆）是 Spring AI 提供的核心能力之一，它允许 AI 模型"记住"之前的对话内容，实现多轮对话的上下文保持。本文档详细介绍如何在 Spring AI 中集成和使用 Chat Memory 功能。

## 核心组件

### 组件架构

```
┌─────────────────────────────────────────────────────────────────┐
│                         ChatClient                               │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │              MessageChatMemoryAdvisor                     │  │
│  │  1. 请求前：从 ChatMemory 获取历史消息                     │  │
│  │  2. 合并历史消息 + 当前用户消息                            │  │
│  │  3. 发送给 AI 模型                                        │  │
│  │  4. 响应后：将新消息存入 ChatMemory                        │  │
│  └───────────────────────────────────────────────────────────┘  │
│                              ↓                                   │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │              MessageWindowChatMemory                      │  │
│  │  - 维护滑动窗口的消息（默认最多 20 条）                    │  │
│  │  - 保留系统消息，淘汰旧的用户/助手消息                     │  │
│  └───────────────────────────────────────────────────────────┘  │
│                              ↓                                   │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │              ChatMemoryRepository                         │  │
│  │  - InMemoryChatMemoryRepository（内存存储）               │  │
│  │  - JdbcChatMemoryRepository（JDBC 存储）                  │  │
│  │  - MongoChatMemoryRepository（MongoDB 存储）              │  │
│  │  - CassandraChatMemoryRepository（Cassandra 存储）        │  │
│  │  - Neo4jChatMemoryRepository（Neo4j 存储）                │  │
│  │  - CosmosDBChatMemoryRepository（CosmosDB 存储）          │  │
│  └───────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
```

### 组件说明

| 组件 | 说明 |
|------|------|
| `ChatMemory` | 接口，定义消息的增删查操作 |
| `MessageWindowChatMemory` | 滑动窗口实现，保留最近 N 条消息 |
| `ChatMemoryRepository` | 存储抽象接口，支持多种存储后端 |
| `MessageChatMemoryAdvisor` | Advisor 实现，自动管理对话历史 |
| `SimpleLoggerAdvisor` | 日志 Advisor，打印请求/响应详情 |

## 快速开始

### 1. 添加依赖

Spring AI 默认已包含 Chat Memory 支持，无需额外添加依赖。如需持久化存储，可添加对应依赖：

```xml
<!-- JDBC 持久化 -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-chat-memory-repository-jdbc</artifactId>
</dependency>

<!-- MongoDB 持久化 -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-chat-memory-repository-mongodb</artifactId>
</dependency>
```

### 2. 配置 ChatMemory Bean

```java
package com.zhy.ai.ollamastudy.memory;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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

### 3. 配置 ChatClient

```java
@RestController
@RequestMapping("/api/openai")
public class OpenAiChatController {

    private final ChatClient memoryChatClient;

    public OpenAiChatController(OpenAiChatModel openAiChatModel, 
                                 WeatherService weatherService, 
                                 ChatMemory chatMemory) {
        SimpleLoggerAdvisor loggerAdvisor = new SimpleLoggerAdvisor();
        
        this.memoryChatClient = ChatClient.builder(openAiChatModel)
                .defaultTools(weatherService)
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        loggerAdvisor
                )
                .build();
    }
}
```

### 4. 使用 ChatClient

```java
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

    return ResponseEntity.ok(new ChatResult(content));
}
```

## 工作原理

### 请求处理流程

```
用户请求
    │
    ▼
┌─────────────────────────────────────┐
│     MessageChatMemoryAdvisor        │
│  ┌─────────────────────────────┐    │
│  │ 1. 获取 conversationId      │    │
│  │ 2. 从 ChatMemory 读取历史   │    │
│  │ 3. 将历史消息添加到 Prompt  │    │
│  └─────────────────────────────┘    │
└─────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────┐
│          AI Model 调用              │
│  输入：历史消息 + 当前用户消息      │
│  输出：AI 响应                      │
└─────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────┐
│     MessageChatMemoryAdvisor        │
│  ┌─────────────────────────────┐    │
│  │ 1. 将用户消息存入 Memory    │    │
│  │ 2. 将 AI 响应存入 Memory    │    │
│  └─────────────────────────────┘    │
└─────────────────────────────────────┘
    │
    ▼
返回响应给用户
```

### 消息窗口机制

`MessageWindowChatMemory` 使用滑动窗口策略管理消息：

```
初始状态：[]
第1轮后：[User: 你好, Assistant: 你好！]
第2轮后：[User: 你好, Assistant: 你好！, User: 我叫小明, Assistant: 你好小明！]
...
第N轮后（超过maxMessages）：
[User: ..., Assistant: ..., User: 最新消息, Assistant: 最新响应]
         ↑ 旧消息被淘汰
```

**特点：**
- 系统消息（System Message）始终保留
- 新增系统消息时，旧的系统消息会被移除
- 超过窗口大小时，按 FIFO 策略淘汰旧消息

## API 端点设计

### 对话接口

```java
// 同步对话（带记忆）
POST /api/openai/chat/memory/{conversationId}
Content-Type: application/json

{
    "prompt": "你好，我叫小明"
}

// 响应
{
    "model": "SiliconFlow",
    "content": "你好小明！很高兴认识你。",
    "conversationId": "conv-xxx"
}
```

### 流式对话（带记忆）

```java
// 流式对话（带记忆）
GET /api/openai/chat-stream/memory/{conversationId}?prompt=你好

// 响应：SSE 流
data: 你
data: 好
data: ！
data: 我
data: 是
...
```

### 记忆管理

```java
// 清除指定会话的记忆
DELETE /api/openai/memory/{conversationId}

// 响应：204 No Content
```

## 持久化存储

### 内存存储（默认）

```java
@Bean
public ChatMemoryRepository chatMemoryRepository() {
    return new InMemoryChatMemoryRepository();
}
```

**适用场景：** 开发测试、单机应用、临时对话

### JDBC 存储

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-chat-memory-repository-jdbc</artifactId>
</dependency>
```

```yaml
spring:
  ai:
    chat:
      memory:
        repository:
          jdbc:
            initialize-schema: always  # always/embedded/never
```

**支持的数据库：** PostgreSQL、MySQL/MariaDB、SQL Server、Oracle、HSQLDB

### MongoDB 存储

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-chat-memory-repository-mongodb</artifactId>
</dependency>
```

```yaml
spring:
  ai:
    chat:
      memory:
        repository:
          mongo:
            create-indices: true
            ttl: 2592000  # 30天（秒）
```

### Cassandra 存储

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-chat-memory-repository-cassandra</artifactId>
</dependency>
```

```yaml
spring:
  ai:
    chat:
      memory:
        cassandra:
          keyspace: springframework
          table: ai_chat_memory
          time-to-live: 94608000  # 3年（秒）
```

### Neo4j 存储

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-chat-memory-repository-neo4j</artifactId>
</dependency>
```

### CosmosDB 存储

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-chat-memory-repository-cosmos-db</artifactId>
</dependency>
```

```yaml
spring:
  ai:
    chat:
      memory:
        repository:
          cosmosdb:
            endpoint: https://your-account.documents.azure.com:443/
            database-name: SpringAIChatMemory
            container-name: ChatMemory
```

## 日志调试

### 启用日志

```yaml
logging:
  level:
    org.springframework.ai: DEBUG
```

### 添加 SimpleLoggerAdvisor

```java
SimpleLoggerAdvisor loggerAdvisor = new SimpleLoggerAdvisor();

ChatClient chatClient = ChatClient.builder(chatModel)
        .defaultAdvisors(
                MessageChatMemoryAdvisor.builder(chatMemory).build(),
                loggerAdvisor
        )
        .build();
```

### 日志输出示例

```
DEBUG o.s.a.c.c.a.SimpleLoggerAdvisor : === AI Request ===
DEBUG o.s.a.c.c.a.SimpleLoggerAdvisor : User Message: 你好，我叫小明
DEBUG o.s.a.c.c.a.SimpleLoggerAdvisor : System Message: You are a helpful assistant.
DEBUG o.s.a.c.c.a.SimpleLoggerAdvisor : History Messages: 2

DEBUG o.s.a.c.c.a.SimpleLoggerAdvisor : === AI Response ===
DEBUG o.s.a.c.c.a.SimpleLoggerAdvisor : Content: 你好小明！有什么我可以帮助你的吗？
DEBUG o.s.a.c.c.a.SimpleLoggerAdvisor : Model: Qwen/Qwen3.5-4B
DEBUG o.s.a.c.c.a.SimpleLoggerAdvisor : Token Usage: {prompt=25, completion=15, total=40}
```

## 前端集成示例

### 完整前端代码

参考项目中的 `chat-memory.html` 文件，包含以下功能：

- 对话列表管理（新建、切换、删除）
- 本地存储对话历史
- 同步/流式对话支持
- 美观的聊天界面

### 核心前端逻辑

```javascript
// 创建新对话
function createNewConversation() {
    const conv = {
        id: 'conv-' + Date.now(),
        title: '',
        messages: [],
        createdAt: Date.now(),
        updatedAt: Date.now()
    };
    conversations.push(conv);
    selectConversation(conv.id);
}

// 发送消息（带记忆）
async function sendMessage() {
    const resp = await fetch('/api/openai/chat/memory/' + currentConvId, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ prompt })
    });
    const data = await resp.json();
    // 渲染响应...
}

// 流式消息（带记忆）
function sendStreamMessage() {
    const url = '/api/openai/chat-stream/memory/' + currentConvId + 
                '?prompt=' + encodeURIComponent(prompt);
    const es = new EventSource(url);
    es.onmessage = (event) => {
        // 追加内容...
    };
}
```

## 最佳实践

### 1. conversationId 设计

```java
// 推荐：使用有意义的 ID
String conversationId = "user-" + userId + "-session-" + sessionId;

// 或使用 UUID
String conversationId = UUID.randomUUID().toString();
```

### 2. 窗口大小设置

```java
// 根据模型上下文窗口调整
MessageWindowChatMemory.builder()
        .chatMemoryRepository(repository)
        .maxMessages(20)  // 约 4096 tokens
        .build();
```

### 3. 记忆清理策略

```java
// 定期清理过期对话
@Scheduled(cron = "0 0 3 * * ?")  // 每天凌晨3点
public void cleanupOldConversations() {
    // 清理超过30天的对话
    chatMemoryRepository.deleteByCreatedAtBefore(
        LocalDateTime.now().minusDays(30)
    );
}
```

### 4. 敏感信息处理

```java
// 不要在对话中存储敏感信息
// 或在存储前进行脱敏处理
public String sanitizeMessage(String content) {
    return content.replaceAll("\\d{11}", "***手机号***")
                  .replaceAll("\\d{18}", "***身份证***");
}
```

## 常见问题

### Q: 为什么 AI 不记得之前的对话？

**A:** 检查以下几点：
1. 是否正确配置了 `MessageChatMemoryAdvisor`
2. 每次请求是否使用相同的 `conversationId`
3. 是否正确传递了 `chat_memory_conversation_id` 参数

### Q: 如何限制对话历史长度？

**A:** 使用 `MessageWindowChatMemory` 的 `maxMessages` 参数：

```java
MessageWindowChatMemory.builder()
        .chatMemoryRepository(repository)
        .maxMessages(10)  // 只保留最近10条消息
        .build();
```

### Q: 如何实现跨设备同步？

**A:** 使用持久化存储（如 JDBC、MongoDB），配合用户认证系统：

```java
String conversationId = "user-" + userId + "-" + topicId;
```

### Q: 流式对话时如何保存记忆？

**A:** `MessageChatMemoryAdvisor` 会自动处理，流式结束后保存完整消息。

## 相关文件

| 文件 | 说明 |
|------|------|
| [ChatMemoryConfiguration.java](../ollama-study/src/main/java/com/zhy/ai/ollamastudy/memory/ChatMemoryConfiguration.java) | ChatMemory 配置类 |
| [OpenAiChatController.java](../ollama-study/src/main/java/com/zhy/ai/ollamastudy/OpenAiChatController.java) | REST 控制器 |
| [chat-memory.html](../ollama-study/src/main/resources/static/chat-memory.html) | 前端测试页面 |

## 参考资料

- [Spring AI 官方文档 - Chat Memory](https://docs.spring.io/spring-ai/reference/api/chat-memory.html)
- [Spring AI 官方文档 - ChatClient](https://docs.spring.io/spring-ai/reference/api/chatclient.html)
