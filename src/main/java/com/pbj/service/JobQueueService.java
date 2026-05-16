package com.pbj.service;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;

@Service
public class JobQueueService {

    public enum JobState { PENDING, RUNNING, DONE, FAILED }

    public static class JobStatus {
        public String id;
        public JobState state;
        public Object result; // For runCode it's SubmissionResult, for regenerate it's "success"
        public String error;
        public String type; // "RUN" or "REGENERATE"
        public Instant createdAt;
        public Instant startedAt;
        public Instant finishedAt;

        public JobStatus(String id, String type) {
            this.id = id;
            this.type = type;
            this.state = JobState.PENDING;
            this.createdAt = Instant.now();
        }

        public long elapsedSeconds() {
            Instant start = startedAt != null ? startedAt : createdAt;
            Instant end = finishedAt != null ? finishedAt : Instant.now();
            return Math.max(0L, end.getEpochSecond() - start.getEpochSecond());
        }
    }

    private final Map<String, JobStatus> jobs = new ConcurrentHashMap<>();

    public String createJob(String type) {
        String id = UUID.randomUUID().toString();
        jobs.put(id, new JobStatus(id, type));
        return id;
    }

    public void updateState(String id, JobState state) {
        JobStatus job = jobs.get(id);
        if (job != null) {
            job.state = state;
            if (state == JobState.RUNNING && job.startedAt == null) {
                job.startedAt = Instant.now();
            }
        }
    }

    public void completeJob(String id, Object result) {
        JobStatus job = jobs.get(id);
        if (job != null) {
            job.state = JobState.DONE;
            job.result = result;
            job.finishedAt = Instant.now();
        }
    }

    public void failJob(String id, String error) {
        JobStatus job = jobs.get(id);
        if (job != null) {
            job.state = JobState.FAILED;
            job.error = error;
            job.finishedAt = Instant.now();
        }
    }

    public JobStatus getStatus(String id) {
        return jobs.get(id);
    }
}
