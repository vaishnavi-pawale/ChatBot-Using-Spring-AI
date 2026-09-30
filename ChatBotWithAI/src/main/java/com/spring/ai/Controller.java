package com.spring.ai;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@CrossOrigin(origins = "*")
public class Controller {

    private final ChatClient chatClient;
    private final DocumentService documentService;

    public Controller(ChatClient.Builder chatClientBuilder, DocumentService documentService) {
        this.chatClient = chatClientBuilder.build();
        this.documentService = documentService;
    }

    @GetMapping("/api/chat")
    public String simpleChat(@RequestParam(value = "message", defaultValue = "Tell me a short joke about Java.") String message) {
        return chatClient.prompt()
                .user(message)
                .call()
                .content();
    }

    @PostMapping("/api/chat")
    public ResponseEntity<Map<String, Object>> chatPost(@RequestBody ChatRequest request) {
        Map<String, Object> response = new HashMap<>();

        String userMessage = (request != null && request.getMessage() != null && !request.getMessage().trim().isEmpty())
                ? request.getMessage()
                : "Hello!";
        
        String mode = (request != null && request.getChatMode() != null)
                ? request.getChatMode().toUpperCase()
                : "NORMAL";

        if ("DOCUMENT".equals(mode)) {
            if (!documentService.hasDocuments()) {
                response.put("reply", "📚 No documents have been uploaded yet. Please upload a PDF or text file first using the Upload button above!");
                response.put("mode", "DOCUMENT");
                response.put("contextUsed", false);
                return ResponseEntity.ok(response);
            }

            List<DocumentService.DocumentChunk> chunks = documentService.searchRelevantChunks(userMessage, 4);

            String contextText = chunks.stream()
                    .map(c -> "[File: " + c.getFileName() + " | Chunk #" + (c.getChunkIndex() + 1) + "]\n" + c.getContent())
                    .collect(Collectors.joining("\n\n---\n\n"));

            String ragSystemPrompt = "You are a helpful AI document assistant. Answer the user's question strictly based on the provided document context below.\n" +
                    "If the answer is found in the context, give a clear and detailed answer. Mention the relevant source filename if applicable.\n" +
                    "If the document context does not contain enough information to answer the question, state politely that the uploaded document does not cover this topic.\n\n" +
                    "--- DOCUMENT CONTEXT ---\n" +
                    contextText + "\n" +
                    "------------------------\n\n" +
                    "User Question: " + userMessage;

            String botReply = chatClient.prompt()
                    .user(ragSystemPrompt)
                    .call()
                    .content();

            response.put("reply", botReply);
            response.put("mode", "DOCUMENT");
            response.put("contextUsed", true);
            response.put("sources", chunks.stream().map(c -> c.getFileName() + " (Chunk #" + (c.getChunkIndex() + 1) + ")").distinct().collect(Collectors.toList()));
            return ResponseEntity.ok(response);
        } else {
            // NORMAL Chat Mode
            String botReply = chatClient.prompt()
                    .user(userMessage)
                    .call()
                    .content();

            response.put("reply", botReply);
            response.put("mode", "NORMAL");
            response.put("contextUsed", false);
            return ResponseEntity.ok(response);
        }
    }

    @PostMapping("/api/documents/upload")
    public ResponseEntity<Map<String, Object>> uploadDocument(@RequestParam("file") MultipartFile file) {
        Map<String, Object> result = new HashMap<>();
        try {
            if (file.isEmpty()) {
                result.put("error", "Uploaded file is empty.");
                return ResponseEntity.badRequest().body(result);
            }

            DocumentService.DocumentSummary summary = documentService.processAndStoreDocument(file);
            result.put("success", true);
            result.put("message", "Document processed successfully!");
            result.put("fileName", summary.getFileName());
            result.put("totalChunks", summary.getTotalChunks());
            result.put("totalCharacters", summary.getTotalCharacters());
            result.put("documents", documentService.getUploadedDocuments());

            return ResponseEntity.ok(result);
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", "Failed to process document: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(result);
        }
    }

    @GetMapping("/api/documents")
    public ResponseEntity<List<DocumentService.DocumentSummary>> getUploadedDocuments() {
        return ResponseEntity.ok(documentService.getUploadedDocuments());
    }

    @DeleteMapping("/api/documents")
    public ResponseEntity<Map<String, Object>> clearDocuments() {
        documentService.clearAllDocuments();
        Map<String, Object> res = new HashMap<>();
        res.put("success", true);
        res.put("message", "All uploaded documents have been cleared.");
        return ResponseEntity.ok(res);
    }

    public static class ChatRequest {
        private String message;
        private String chatMode; // "NORMAL" or "DOCUMENT"

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }

        public String getChatMode() {
            return chatMode;
        }

        public void setChatMode(String chatMode) {
            this.chatMode = chatMode;
        }
    }
}