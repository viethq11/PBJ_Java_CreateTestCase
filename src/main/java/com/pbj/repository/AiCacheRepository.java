package com.pbj.repository;

import com.pbj.entity.AiCache;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AiCacheRepository extends JpaRepository<AiCache, Long> {
    Optional<AiCache> findByRequestHash(String requestHash);
}
