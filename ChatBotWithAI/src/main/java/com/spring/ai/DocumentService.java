package com.spring.ai;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class DocumentService {

    private final List<DocumentChunk> documentChunks = new ArrayList<>();
    private final List<DocumentSummary> uploadedDocuments = new ArrayList<>();

    public DocumentSummary processAndStoreDocument(MultipartFile file) throws IOException {
        String content = extractText(file);
        String fileName = file.getOriginalFilename();
        if (fileName == null) {
            fileName = "unknown_document";
        }

        List<DocumentChunk> chunks = chunkText(fileName, content, 1000);
        documentChunks.addAll(chunks);

        DocumentSummary summary = new DocumentSummary(fileName, chunks.size(), content.length());
        uploadedDocuments.add(summary);

        return summary;
    }

    private String extractText(MultipartFile file) throws IOException {
        String contentType = file.getContentType();
        if (contentType != null && contentType.equals("application/pdf")) {
            try (PDDocument document = PDDocument.load(file.getInputStream())) {
                PDFTextStripper stripper = new PDFTextStripper();
                return stripper.getText(document);
            }
        }
        return new String(file.getBytes());
    }

    private List<DocumentChunk> chunkText(String fileName, String text, int chunkSize) {
        List<DocumentChunk> chunks = new ArrayList<>();
        String[] words = text.split("\\s+");
        StringBuilder currentChunk = new StringBuilder();
        int chunkIndex = 0;

        for (String word : words) {
            if (currentChunk.length() + word.length() > chunkSize) {
                chunks.add(new DocumentChunk(fileName, currentChunk.toString().trim(), chunkIndex++));
                currentChunk = new StringBuilder();
            }
            currentChunk.append(word).append(" ");
        }

        if (currentChunk.length() > 0) {
            chunks.add(new DocumentChunk(fileName, currentChunk.toString().trim(), chunkIndex));
        }

        return chunks;
    }

    public List<DocumentChunk> searchRelevantChunks(String query, int topK) {
        if (query == null || query.trim().isEmpty()) {
            return Collections.emptyList();
        }

        Set<String> queryWords = Arrays.stream(query.toLowerCase().split("\\s+"))
                .map(word -> word.replaceAll("[^a-z0-9]", ""))
                .filter(word -> word.length() > 2)
                .collect(Collectors.toSet());

        return documentChunks.stream()
                .sorted((c1, c2) -> {
                    int score1 = calculateRelevance(queryWords, c1.getContent());
                    int score2 = calculateRelevance(queryWords, c2.getContent());
                    return Integer.compare(score2, score1);
                })
                .limit(topK)
                .collect(Collectors.toList());
    }

    private int calculateRelevance(Set<String> queryWords, String chunkContent) {
        String lowerContent = chunkContent.toLowerCase();
        int score = 0;
        for (String word : queryWords) {
            if (lowerContent.contains(word)) {
                score++;
            }
        }
        return score;
    }

    public boolean hasDocuments() {
        return !uploadedDocuments.isEmpty();
    }

    public List<DocumentSummary> getUploadedDocuments() {
        return uploadedDocuments;
    }

    public void clearAllDocuments() {
        documentChunks.clear();
        uploadedDocuments.clear();
    }

    public static class DocumentChunk {
        private final String fileName;
        private final String content;
        private final int chunkIndex;

        public DocumentChunk(String fileName, String content, int chunkIndex) {
            this.fileName = fileName;
            this.content = content;
            this.chunkIndex = chunkIndex;
        }

        public String getFileName() {
            return fileName;
        }

        public String getContent() {
            return content;
        }

        public int getChunkIndex() {
            return chunkIndex;
        }
    }

    public static class DocumentSummary {
        private final String fileName;
        private final int totalChunks;
        private final int totalCharacters;

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
