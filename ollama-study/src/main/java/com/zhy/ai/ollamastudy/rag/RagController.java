package com.zhy.ai.ollamastudy.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
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
            logger.warn("Received empty content for ingestion");
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
            logger.warn("Received empty file for ingestion");
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
            logger.warn("Received empty question");
            return ResponseEntity.badRequest().build();
        }

        String answer = ragService.ask(request.getQuestion());

        AskResult result = new AskResult();
        result.setQuestion(request.getQuestion());
        result.setAnswer(answer);

        return ResponseEntity.ok(result);
    }

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

    public static class IngestRequest {
        private String content;

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }
    }

    public static class IngestResult {
        private boolean success;
        private int chunkCount;
        private String message;

        public boolean isSuccess() {
            return success;
        }

        public void setSuccess(boolean success) {
            this.success = success;
        }

        public int getChunkCount() {
            return chunkCount;
        }

        public void setChunkCount(int chunkCount) {
            this.chunkCount = chunkCount;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }
    }

    public static class AskRequest {
        private String question;

        public String getQuestion() {
            return question;
        }

        public void setQuestion(String question) {
            this.question = question;
        }
    }

    public static class AskResult {
        private String question;
        private String answer;

        public String getQuestion() {
            return question;
        }

        public void setQuestion(String question) {
            this.question = question;
        }

        public String getAnswer() {
            return answer;
        }

        public void setAnswer(String answer) {
            this.answer = answer;
        }
    }
}
