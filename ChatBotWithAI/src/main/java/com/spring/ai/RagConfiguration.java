package com.spring.ai;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RagConfiguration {

    @Bean
    public VectorStore simpleVectorStore(EmbeddingModel embeddingModel) {
        // Initializes RAM-based vector storage
        SimpleVectorStore vectorStore = SimpleVectorStore.builder(embeddingModel).build();

        // 1. Read a hardcoded local PDF from resources
        PagePdfDocumentReader pdfReader = new PagePdfDocumentReader("classpath:/manual.pdf");

        // 2. Split the text into manageable chunks
        TokenTextSplitter textSplitter = new TokenTextSplitter();

        // 3. Vectorize and save to RAM
        vectorStore.add(textSplitter.apply(pdfReader.get()));

        return vectorStore;
    }
}
