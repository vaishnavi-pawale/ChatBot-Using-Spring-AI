package com.spring.ai;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.QuestionAnswerAdvisor;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

@RestController
@CrossOrigin(origins = "*")
public class ChatController {

    private final ChatClient chatClientWithAdvisor;
    private final ChatClient generalChatClient;
    private final VectorStore vectorStore;
    private final List<DocumentSummary> uploadedDocuments = new CopyOnWriteArrayList<>();

    public ChatController(ChatClient.Builder builder, VectorStore vectorStore) {
        this.vectorStore = vectorStore;
        // Wired with default RAG advisor as required by Phase 4
        this.chatClientWithAdvisor = builder.clone()
                .defaultAdvisors(new QuestionAnswerAdvisor(vectorStore))
                .build();
        this.generalChatClient = builder.clone().build();

        // Register default manual.pdf
        this.uploadedDocuments.add(new DocumentSummary("manual.pdf", 4, 2500));
    }

    /**
     * V0 Prototype test endpoint (Phase 4 & 5)
     */
    @GetMapping("/ask")
    public String ask(@RequestParam String question) {
        String raw = chatClientWithAdvisor.prompt()
                .user(question + "\n\nFormatting: Output raw unformatted plain text only. Do not use Markdown, bolding, italics, hash symbols, or checklist brackets. Use standard numbering or simple dashes for lists. Do not include unnecessary dashes or divider lines.")
                .call()
                .content();
        return formatToPlainText(raw);
    }

    /**
     * Unified Normal Mode Chat: handles both general conversation and document-based questions seamlessly
     */
    @PostMapping("/api/chat")
    public ResponseEntity<Map<String, Object>> chat(@RequestBody Map<String, String> request) {
        Map<String, Object> response = new HashMap<>();
        String userMessage = request != null && request.get("message") != null
                ? request.get("message").trim()
                : "Hello!";

        if (userMessage.isEmpty()) {
            response.put("reply", "Please enter a message or question.");
            return ResponseEntity.ok(response);
        }

        try {
            // Retrieve top relevant chunks from in-memory vector store
            List<Document> similarDocs = Collections.emptyList();
            try {
                similarDocs = vectorStore.similaritySearch(
                        SearchRequest.builder().query(userMessage).topK(3).similarityThreshold(0.4).build()
                );
            } catch (Exception ex) {
                try {
                    similarDocs = vectorStore.similaritySearch(userMessage);
                    if (similarDocs.size() > 3) {
                        similarDocs = similarDocs.subList(0, 3);
                    }
                } catch (Exception ignored) {}
            }

            if (similarDocs != null && !similarDocs.isEmpty()) {
                String context = similarDocs.stream()
                        .map(Document::getText)
                        .collect(Collectors.joining("\n\n---\n\n"));

                String promptWithContext = """
                        You are a friendly, intelligent assistant in Normal Chat Mode.
                        
                        You have access to the following relevant document knowledge base:
                        --------------------
                        %s
                        --------------------
                        
                        Instructions:
                        1. If the user's question is about topics, procedures, or instructions found in the document context above, use that information to give a clear, accurate, and detailed answer.
                        2. If the user's message is a greeting, casual chat, joke, general knowledge question, or unrelated to the document, chat naturally and answer helpfully using your general knowledge without saying "the document doesn't mention this".
                        3. Response Formatting Rules (STRICT):
                           - Output raw, unformatted plain text only.
                           - Do NOT use Markdown, bolding (**), italics (* or _), hash symbols (#), backticks (`), or checklist brackets ([ ]).
                           - Do NOT use unnecessary dashes or divider lines (such as --- or ===).
                           - Use standard numbering (1., 2.) or simple dashes (-) for lists.
                        
                        User Message: %s
                        """.formatted(context, userMessage);

                String botReply = generalChatClient.prompt()
                        .user(promptWithContext)
                        .call()
                        .content();

                List<String> sources = similarDocs.stream()
                        .map(doc -> {
                            Object src = doc.getMetadata().get("source");
                            if (src == null) src = doc.getMetadata().get("file_name");
                            return src != null ? src.toString() : "Document Context";
                        })
                        .distinct()
                        .collect(Collectors.toList());

                response.put("reply", formatToPlainText(botReply));
                response.put("sources", sources);
            } else {
                // Normal general chat without specific document context
                String promptGeneral = """
                        You are a friendly, helpful AI assistant.
                        
                        Response Formatting Rules (STRICT):
                        - Output raw, unformatted plain text only.
                        - Do NOT use Markdown, bolding (**), italics (* or _), hash symbols (#), backticks (`), or checklist brackets ([ ]).
                        - Do NOT use unnecessary dashes or divider lines (such as --- or ===).
                        - Use standard numbering (1., 2.) or simple dashes (-) for lists.
                        
                        User Message: %s
                        """.formatted(userMessage);

                String botReply = generalChatClient.prompt()
                        .user(promptGeneral)
                        .call()
                        .content();

                response.put("reply", formatToPlainText(botReply));
            }

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("reply", "Error processing request: " + e.getMessage());
            return ResponseEntity.ok(response);
        }
    }

    /**
     * Sanitizes output to guarantee 100% raw plain text without Markdown, bolding,
     * italics, hash headers, checklist brackets, or unnecessary dashes.
     */
    private String formatToPlainText(String text) {
        if (text == null) return "";
        String clean = text;

        // Replace checklist brackets [ ] or [x] with standard list dash
        clean = clean.replaceAll("(?m)^\\s*\\[[ xX]\\]\\s*", "- ");

        // Remove markdown heading markers (# Header -> Header)
        clean = clean.replaceAll("(?m)^\\s*#{1,6}\\s*", "");

        // Remove bolding and italics: **text** -> text, *text* -> text, __text__ -> text, _text_ -> text
        clean = clean.replaceAll("\\*\\*(.*?)\\*\\*", "$1");
        clean = clean.replaceAll("\\*(.*?)\\*", "$1");
        clean = clean.replaceAll("__(.*?)__", "$1");
        clean = clean.replaceAll("(?<![a-zA-Z0-9])_(.*?)_(?![a-zA-Z0-9])", "$1");

        // Remove backticks and code fences
        clean = clean.replaceAll("```[a-zA-Z]*\\n?", "");
        clean = clean.replaceAll("`([^`]+)`", "$1");

        // Remove divider lines made of 3 or more dashes, equals, or underscores
        clean = clean.replaceAll("(?m)^\\s*[-_=]{3,}\\s*$", "");

        // Remove standalone unnecessary dashed lines or separator bars
        clean = clean.replaceAll("(?m)^\\s*---+\\s*", "");

        // Clean up excessive blank lines
        clean = clean.replaceAll("\\n{3,}", "\n\n");

        return clean.trim();
    }

    /**
     * Upload document (PDF or Text) directly in Normal Mode
     */
    @PostMapping("/api/documents/upload")
    public ResponseEntity<Map<String, Object>> uploadDocument(@RequestParam("file") MultipartFile file) {
        Map<String, Object> result = new HashMap<>();
        try {
            if (file == null || file.isEmpty()) {
                result.put("success", false);
                result.put("error", "Uploaded file is empty.");
                return ResponseEntity.badRequest().body(result);
            }

            String filename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "document.txt";
            List<Document> documents;

            if (filename.toLowerCase().endsWith(".pdf")) {
                ByteArrayResource resource = new ByteArrayResource(file.getBytes()) {
                    @Override
                    public String getFilename() {
                        return filename;
                    }
                };
                PagePdfDocumentReader pdfReader = new PagePdfDocumentReader(resource);
                documents = pdfReader.get();
            } else {
                String text;
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
                    text = reader.lines().collect(Collectors.joining("\n"));
                }
                documents = List.of(new Document(text, Map.of("source", filename)));
            }

            TokenTextSplitter textSplitter = new TokenTextSplitter();
            List<Document> chunks = textSplitter.apply(documents);

            for (Document chunk : chunks) {
                chunk.getMetadata().put("source", filename);
            }

            vectorStore.add(chunks);

            int totalCharacters = documents.stream().mapToInt(d -> d.getText() != null ? d.getText().length() : 0).sum();
            DocumentSummary summary = new DocumentSummary(filename, chunks.size(), totalCharacters);
            uploadedDocuments.add(0, summary);

            result.put("success", true);
            result.put("fileName", filename);
            result.put("totalChunks", chunks.size());
            result.put("totalCharacters", totalCharacters);
            result.put("documents", uploadedDocuments);

            return ResponseEntity.ok(result);
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", "Failed to process document: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(result);
        }
    }

    @GetMapping("/api/documents")
    public ResponseEntity<List<DocumentSummary>> getDocuments() {
        return ResponseEntity.ok(uploadedDocuments);
    }

    @DeleteMapping("/api/documents")
    public ResponseEntity<Map<String, Object>> clearDocuments() {
        uploadedDocuments.clear();
        Map<String, Object> res = new HashMap<>();
        res.put("success", true);
        res.put("message", "Uploaded documents cleared.");
        return ResponseEntity.ok(res);
    }

    @GetMapping("/summarize-document")
    public ResponseEntity<Map<String, Object>> summarizeDocument() {
        Map<String, Object> res = new HashMap<>();
        try {
            if (uploadedDocuments.isEmpty()) {
                res.put("error", "No documents uploaded yet.");
                return ResponseEntity.badRequest().body(res);
            }

            String prompt = "Please provide a clear and well-structured summary of the user manual / uploaded document.";
            String summary = ask(prompt);
            res.put("summary", summary);
            return ResponseEntity.ok(res);
        } catch (Exception e) {
            res.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(res);
        }
    }

    public static class DocumentSummary {
        private String fileName;
        private int totalChunks;
        private int totalCharacters;

        public DocumentSummary(String fileName, int totalChunks, int totalCharacters) {
            this.fileName = fileName;
            this.totalChunks = totalChunks;
            this.totalCharacters = totalCharacters;
        }

        public String getFileName() {
            return fileName;
        }

        public int getTotalChunks() {
            return totalChunks;
        }

        public int getTotalCharacters() {
            return totalCharacters;
        }
    }
}
