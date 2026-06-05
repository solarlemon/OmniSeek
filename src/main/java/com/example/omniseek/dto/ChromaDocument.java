package com.example.omniseek.dto;

import lombok.Data;

@Data
public class ChromaDocument {
    private String id;
    private float[] embedding;
    private String fileMd5;
    private Integer chunkId;
    private String textContent;
    private String userId;
    private String orgTag;
    private boolean isPublic;

    public ChromaDocument() {
    }

    public ChromaDocument(String id, float[] embedding, String fileMd5, Integer chunkId, String textContent,
            String userId, String orgTag, boolean isPublic) {
        this.id = id;
        this.embedding = embedding;
        this.fileMd5 = fileMd5;
        this.chunkId = chunkId;
        this.textContent = textContent;
        this.userId = userId;
        this.orgTag = orgTag;
        this.isPublic = isPublic;
    }

}
