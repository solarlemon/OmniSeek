package com.example.omniseek.repository;

import com.example.omniseek.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);

    List<User> findTop10ByOrderByCreatedAtDesc();
}
