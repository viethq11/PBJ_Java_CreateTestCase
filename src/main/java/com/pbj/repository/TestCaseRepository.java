package com.pbj.repository;

import com.pbj.entity.TestCase;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TestCaseRepository extends JpaRepository<TestCase, Long> {
    List<TestCase> findByProblemId(Long problemId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from TestCase tc where tc.problem.id = :problemId")
    void deleteByProblemId(Long problemId);
}
