package com.example.omniseek.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.omniseek.entity.ChunkInfo;

import java.util.List;

public interface ChunkInfoRepository extends JpaRepository<ChunkInfo, Long> {
    List<ChunkInfo> findByFileMd5OrderByChunkIndexAsc(String fileMd5);
}
