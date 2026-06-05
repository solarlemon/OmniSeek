package com.example.omniseek.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.omniseek.entity.User;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);
}
