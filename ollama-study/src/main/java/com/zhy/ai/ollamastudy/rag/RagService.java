package com.zhy.ai.ollamastudy.rag;

import com.zhy.ai.ollamastudy.advisor.RagLoggerAdvisor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.PromptTemplate;
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

    private static final PromptTemplate DEFAULT_PROMPT_TEMPLATE = new PromptTemplate("""
			{query}

			以下是可以参考的资料, surrounded by ---------------------

			---------------------
			{question_answer_context}
			---------------------

			如果资料为空，或者跟用户的回答没有关系，请忽略。
			""");


    public RagService(VectorStore vectorStore,
                      TokenTextSplitter textSplitter,
                      @Qualifier("openAiChatModel") ChatModel chatModel,
                      ChatMemory chatMemory) {
        this.vectorStore = vectorStore;
        this.textSplitter = textSplitter;
        this.chatModel = chatModel;
        this.chatMemory = chatMemory;
        loggerAdvisor = new RagLoggerAdvisor();
    }

    public int ingestText(String content) {
        logger.info("Ingesting text content, length={}", content.length());

        Document document = new Document(content);
        List<Document> chunks = textSplitter.apply(List.of(document));
        logger.info("Split into {} chunks", chunks.size());

        vectorStore.add(chunks);
        logger.info("Successfully added {} documents to vector store", chunks.size());

        return chunks.size();
    }

    public int ingestFile(MultipartFile file) throws IOException {
        String filename = file.getOriginalFilename();
        logger.info("Ingesting file: {}, size={} bytes", filename, file.getSize());

        String content = new String(file.getBytes(), StandardCharsets.UTF_8);
        return ingestText(content);
    }

    public String ask(String question) {
        logger.info("Processing RAG question: {}", question);

        List<org.springframework.ai.document.Document> relevantDocs = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(question)
                        .topK(4)
                        .similarityThreshold(0.5)
                        .build()
        );

        if (relevantDocs.isEmpty()) {
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

        logger.info("Found {} relevant documents, using RAG mode", relevantDocs.size());
        QuestionAnswerAdvisor qaAdvisor = QuestionAnswerAdvisor.builder(vectorStore).promptTemplate(DEFAULT_PROMPT_TEMPLATE)
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

    public Flux<String> askStream(String question) {
        logger.info("Processing RAG question (stream): {}", question);

        List<org.springframework.ai.document.Document> relevantDocs = vectorStore.similaritySearch(
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

    public void clear() {
        logger.info("Clearing vector store (recreating)");
    }
}
