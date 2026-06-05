package com.example.omniseek.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.omniseek.entity.OrganizationTag;

import java.util.List;
import java.util.Optional;

public interface OrganizationTagRepository extends JpaRepository<OrganizationTag, String> {
    Optional<OrganizationTag> findByTagId(String tagId);
    List<OrganizationTag> findByParentTag(String parentTag);
    boolean existsByTagId(String tagId);
} 