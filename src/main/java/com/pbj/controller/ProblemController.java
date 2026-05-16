package com.pbj.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pbj.entity.Problem;
import com.pbj.service.CodeExecutionService;
import com.pbj.service.JobQueueService;
import com.pbj.service.ProblemService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Arrays;
import java.util.Map;

@Controller
@RequiredArgsConstructor
public class ProblemController {

    private final ProblemService problemService;
    private final JobQueueService jobQueueService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ------------------------------------------------------------------
    // Pages
    // ------------------------------------------------------------------

    @GetMapping("/")
    public String index(Model model, @RequestParam(value = "search", required = false) String search) {
        model.addAttribute("problems", problemService.searchProblems(search));
        model.addAttribute("searchQuery", search);
        return "index";
    }

    @GetMapping("/create")
    public String createForm() {
        return "create_problem";
    }

    @GetMapping("/problem/{id}")
    public String viewProblem(@PathVariable("id") Long id,
                              @RequestParam(value = "index", required = false) Integer index,
                              Model model) {
        Problem problem = problemService.getProblem(id);
        if (problem == null) return "redirect:/";

        model.addAttribute("problem", problem);
        model.addAttribute("testcases", problemService.getTestCases(id));
        if (index != null) model.addAttribute("index", index);
        return "problem";
    }

    @PostMapping("/problem/{id}/delete")
    public String deleteProblem(@PathVariable("id") Long id, RedirectAttributes ra) {
        try {
            problemService.deleteProblem(id);
            ra.addFlashAttribute("success", "Đã xoá bài tập thành công.");
        } catch (Exception e) {
            ra.addFlashAttribute("error", "Lỗi khi xoá bài tập: " + e.getMessage());
        }
        return "redirect:/";
    }

    // ------------------------------------------------------------------
    // Async: Generate problem  →  returns jobId  →  frontend polls /api/job/{id}
    // ------------------------------------------------------------------

    /**
     * Accepts the generation request, immediately enqueues it as a background job,
     * and returns a JSON ticket { "jobId": "..." } so the frontend can poll.
     */
    @PostMapping("/api/problem/generate")
    @ResponseBody
    public ResponseEntity<Map<String, String>> generateProblem(
            @RequestParam("title") String title,
            @RequestParam(value = "description", required = false) String description,
            @RequestParam(value = "images", required = false) MultipartFile[] images) {
        try {
            String jobId = problemService.submitGenerateProblem(
                    title, description,
                    images != null ? Arrays.asList(images) : null);
            return ResponseEntity.accepted().body(Map.of("jobId", jobId));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    // ------------------------------------------------------------------
    // Async: Run code submission
    // ------------------------------------------------------------------

    @PostMapping("/api/problem/run")
    @ResponseBody
    public ResponseEntity<Map<String, String>> runCode(
            @RequestParam("problemId") Long problemId,
            @RequestParam("language") String language,
            @RequestParam("expectedStatus") CodeExecutionService.RunResult expectedStatus,
            @RequestParam("sourceCode") String sourceCode) {
        try {
            String jobId = problemService.submitRunCode(problemId, sourceCode, language, expectedStatus);
            return ResponseEntity.accepted().body(Map.of("jobId", jobId));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    // ------------------------------------------------------------------
    // Async: Regenerate testcases
    // ------------------------------------------------------------------

    @PostMapping("/api/problem/regenerate-testcases")
    @ResponseBody
    public ResponseEntity<Map<String, String>> regenerateTestCases(
            @RequestParam("problemId") Long problemId) {
        try {
            String jobId = problemService.submitRegenerateTestCases(problemId);
            return ResponseEntity.accepted().body(Map.of("jobId", jobId));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    // ------------------------------------------------------------------
    // Polling endpoint for all async jobs
    // ------------------------------------------------------------------

    /**
     * Frontend polls GET /api/job/{id} every 2 seconds.
     * Returns { state, result, error } so the UI can update accordingly.
     */
    @GetMapping("/api/job/{id}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getJobStatus(@PathVariable("id") String id) {
        JobQueueService.JobStatus job = jobQueueService.getStatus(id);
        if (job == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of(
                "state",  job.state.name(),
                "type",   job.type,
                "elapsedSeconds", job.elapsedSeconds(),
                "result", job.result  != null ? job.result  : "",
                "error",  job.error   != null ? job.error   : ""
        ));
    }

}
