# Spring AI Function Calling 使用指南

## 概述

Function Calling（函数调用）是大语言模型的重要能力，允许模型在对话过程中调用外部工具来完成复杂任务。Spring AI 提供了优雅的注解驱动方式来实现这一功能。

## 快速开始

### 1. 定义工具类

使用 `@Service` 和 `@Tool` 注解定义可被模型调用的工具：

```java
package com.zhy.ai.ollamastudy.mcp;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;

@Service
public class WeatherService {

    @Tool(description = "Get weather information by city name")
    public String getWeather(String cityName) {
        return "It's sunny in " + cityName;
    }
}
```

### 2. 注册工具回调

创建配置类，将工具类注册为 `ToolCallbackProvider`：

```java
package com.zhy.ai.ollamastudy.mcp;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class McpConfiguration {

    @Bean
    public ToolCallbackProvider weatherTools(WeatherService weatherService) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(weatherService)
                .build();
    }
}
```

### 3. 在 ChatClient 中集成工具

```java
@RestController
@RequestMapping("/api/openai")
public class OpenAiChatController {

    private final ChatClient chatClient;
    private final ToolCallbackProvider toolCallbackProvider;

    public OpenAiChatController(OpenAiChatModel openAiChatModel, 
                                ToolCallbackProvider toolCallbackProvider) {
        this.toolCallbackProvider = toolCallbackProvider;
        this.chatClient = ChatClient.builder(openAiChatModel)
                .defaultTools(toolCallbackProvider)  // 注册工具
                .build();
    }

    @PostMapping("/chat")
    public ResponseEntity<ChatResult> chat(@RequestBody ChatRequest request) {
        String content = chatClient.prompt()
                .user(request.getPrompt())
                .call()
                .content();
        
        ChatResult result = new ChatResult();
        result.setContent(content);
        return ResponseEntity.ok(result);
    }
}
```

### 4. 测试调用

```bash
curl -X POST http://localhost:8080/api/openai/chat \
  -H "Content-Type: application/json" \
  -d '{"prompt": "北京今天天气怎么样？"}'
```

## 核心组件

### @Tool 注解

用于标记可被模型调用的方法：

```java
@Tool(description = "工具功能描述")
public String myTool(String param1, int param2) {
    // 工具实现
}
```

| 属性 | 说明 | 默认值 |
|-----|------|-------|
| `description` | 工具功能描述（必填） | - |
| `name` | 工具名称 | 方法名 |

### MethodToolCallbackProvider

核心类，负责将 `@Tool` 方法转换成可执行的回调：

```java
MethodToolCallbackProvider.builder()
        .toolObjects(weatherService, anotherService)  // 可注册多个工具对象
        .build();
```

### ToolCallbackProvider

接口，存放所有工具回调的容器，通过 Spring 依赖注入使用。

### ToolCallback

封装单个工具的元数据和执行逻辑：

```java
public interface ToolCallback {
    String getName();           // 工具名称
    String getDescription();    // 工具描述
    String call(String args);   // 执行工具，args 是 JSON 格式的参数
}
```

## 工作原理

### 整体架构

```
┌─────────────────────────────────────────────────────────────────┐
│                        开发者定义                                 │
├─────────────────────────────────────────────────────────────────┤
│  @Service                                                        │
│  public class WeatherService {                                   │
│      @Tool(description = "Get weather by city")                  │
│      public String getWeather(String cityName) { ... }           │
│  }                                                               │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│                   Spring 容器启动时                              │
├─────────────────────────────────────────────────────────────────┤
│  1. @Service → 创建 WeatherService Bean                          │
│  2. McpConfiguration.weatherTools() 方法被调用                    │
│  3. MethodToolCallbackProvider 扫描 @Tool 注解                   │
│  4. 生成 ToolCallback 对象（包含函数签名、描述、执行逻辑）          │
│  5. 封装成 ToolCallbackProvider Bean                             │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│                     运行时调用                                    │
├─────────────────────────────────────────────────────────────────┤
│  ChatClient.prompt().user("北京天气?").call()                    │
│       ↓                                                          │
│  模型返回 tool_call: {name: "getWeather", args: {cityName: "北京"}}│
│       ↓                                                          │
│  ToolCallbackProvider.getToolCallbacks() 获取工具定义             │
│       ↓                                                          │
│  找到匹配的 ToolCallback，通过反射调用 getWeather("北京")          │
│       ↓                                                          │
│  返回结果给模型，模型生成最终回复                                  │
└─────────────────────────────────────────────────────────────────┘
```

### 调用时序图

```
用户: "北京今天天气怎么样？"
        │
        ▼
┌───────────────────┐
│   ChatClient      │
│  .prompt()        │
│  .user(...)       │
│  .call()          │
└───────────────────┘
        │
        ▼
┌───────────────────┐     工具定义（JSON Schema）
│   OpenAI/Kimi     │ ◄─────────────────────────
│   模型            │
└───────────────────┘
        │
        │ 模型决定调用工具
        ▼
┌───────────────────────────────────┐
│  tool_calls: [{                   │
│    "id": "call_xxx",              │
│    "function": {                  │
│      "name": "getWeather",        │
│      "arguments": "{\"cityName\":\"北京\"}" │
│    }                              │
│  }]                               │
└───────────────────────────────────┘
        │
        ▼
┌───────────────────┐
│  Spring AI        │
│  ToolExecutor     │
└───────────────────┘
        │
        │ 1. 解析 tool_call
        │ 2. 从 ToolCallbackProvider 找到 getWeather
        │ 3. 反序列化 arguments JSON
        │ 4. 反射调用 weatherService.getWeather("北京")
        ▼
┌───────────────────┐
│  WeatherService   │
│  .getWeather()    │
│  返回: "It's sunny in 北京" │
└───────────────────┘
        │
        ▼
┌───────────────────┐
│  将工具结果发回   │
│  给模型继续生成   │
└───────────────────┘
        │
        ▼
模型最终回复: "根据查询，北京今天天气晴朗。"
```

### 工具定义转换

Spring AI 会将 `@Tool` 方法转换为 JSON Schema 格式发送给模型：

```json
{
  "tools": [
    {
      "type": "function",
      "function": {
        "name": "getWeather",
        "description": "Get weather information by city name",
        "parameters": {
          "type": "object",
          "properties": {
            "cityName": {
              "type": "string"
            }
          },
          "required": ["cityName"]
        }
      }
    }
  ]
}
```

## 高级用法

### 多参数工具

```java
@Tool(description = "Search products by category and price range")
public List<Product> searchProducts(
        String category,
        double minPrice,
        double maxPrice) {
    // 实现搜索逻辑
}
```

### 可选参数

```java
@Tool(description = "Get user information")
public UserInfo getUser(String userId, 
                        @ToolParam(required = false) String fields) {
    // fields 是可选参数
}
```

### 动态注册工具

在每次调用时动态指定工具：

```java
String content = chatClient.prompt()
        .user("北京天气怎么样？")
        .tools(toolCallbackProvider)  // 调用时注册
        .call()
        .content();
```

### 工具执行回调

监听工具执行过程：

```java
chatClient.prompt()
        .user("北京天气怎么样？")
        .advisors(new ToolCallingAdvisor())
        .call();
```

## 配置说明

### application.yaml

```yaml
spring:
  ai:
    openai:
      api-key: ${API_KEY}
      base-url: https://api.moonshot.cn
      chat:
        options:
          model: kimi-k2.5
          temperature: 1.0
```

### 依赖配置

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-openai</artifactId>
</dependency>
```

## 注意事项

1. **模型支持**：确保使用的模型支持 Function Calling（如 GPT-4、Kimi 等）
2. **参数类型**：工具方法参数建议使用基本类型和 String，复杂对象可能需要额外配置
3. **描述清晰**：`@Tool` 的 description 要清晰描述工具功能，帮助模型正确选择
4. **异常处理**：工具方法应妥善处理异常，返回有意义的错误信息
5. **性能考虑**：工具调用会增加响应时间，避免在工具中执行耗时操作

## 常见问题

### Q: 模型没有调用工具？

检查以下几点：
- 工具是否正确注册到 ChatClient
- 工具描述是否清晰
- 用户问题是否需要使用工具
- 模型是否支持 Function Calling

### Q: 工具调用失败？

检查：
- 参数类型是否匹配
- 方法是否有访问权限
- 是否有异常抛出

### Q: 如何调试工具调用？

开启日志级别：

```yaml
logging:
  level:
    org.springframework.ai: DEBUG
```

## 参考资料

- [Spring AI 官方文档](https://docs.spring.io/spring-ai/reference/)
- [OpenAI Function Calling 文档](https://platform.openai.com/docs/guides/function-calling)
- [Moonshot API 文档](https://platform.moonshot.cn/docs)
