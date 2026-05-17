package com.pbj.service;

import com.pbj.dto.GenerationFeedbackDTO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JobQueueServiceTest {

    @Test
    void requestInputStoresStructuredFeedbackInsteadOfFailingJob() {
        JobQueueService service = new JobQueueService();
        String jobId = service.createJob("GENERATE");
        GenerationFeedbackDTO feedback = new GenerationFeedbackDTO(
                "formal_spec",
                "Need more detail",
                "Add input/output constraints",
                List.of("Read statement"),
                List.of("Input format")
        );

        service.requestInput(jobId, feedback);

        JobQueueService.JobStatus status = service.getStatus(jobId);
        assertThat(status.state).isEqualTo(JobQueueService.JobState.NEEDS_INPUT);
        assertThat(status.feedback).isSameAs(feedback);
        assertThat(status.error).isNull();
        assertThat(status.finishedAt).isNotNull();
    }
}
