package com.pbj.service;

import com.pbj.entity.TestCase;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Executes untrusted code in a hardened process sandbox.
 *
 * Sandbox measures (defence-in-depth):
 *  1. Dedicated sandbox directory (auto-deleted after use)
 *  2. Process runs with ulimit -v (virtual memory cap) and ulimit -u (max PIDs)
 *     via a wrapper shell command on Linux — prevents fork bombs & OOM attacks.
 *  3. Hard wall-clock timeout via Process.waitFor(timeLimit) — prevents TLE bypass.
 *  4. destroyForcibly() kills the process AND its whole process group on Linux.
 *  5. stderr is read but capped at 50 lines to prevent log flooding.
 *
 * NOTE: The strongest sandbox would be a separate Docker container per run.
 * The ulimit approach here is a pragmatic improvement for the current architecture.
 */
@Service
public class CodeExecutionService {

    private static final String CHECKER_CLASS_NAME         = "Checker";
    private static final String CHECKER_HARNESS_CLASS_NAME = "CheckerHarness";
    private static final String CHECKER_HARNESS_SOURCE     = """
            import java.nio.file.Files;
            import java.nio.file.Path;

            public class CheckerHarness {
                public static void main(String[] args) throws Exception {
                    String input    = Files.readString(Path.of("checker_input.txt"));
                    String expected = Files.readString(Path.of("checker_expected.txt"));
                    String actual   = Files.readString(Path.of("checker_actual.txt"));
                    System.out.print(Checker.check(input, expected, actual) ? "OK" : "WA");
                }
            }
            """;

    // Maximum virtual memory a sandbox process may use (512 MB in KB units)
    private static final int SANDBOX_MAX_VMEM_KB  = 512 * 1024;
    // Maximum number of processes a sandbox user may spawn (prevents fork bomb)
    private static final int SANDBOX_MAX_NPROC    = 64;
    // Whether we are running on Linux (ulimit only works there)
    private static final boolean IS_LINUX =
            System.getProperty("os.name", "").toLowerCase().contains("linux");

    public enum RunResult { AC, WA, TLE, RE, CE }

    private static class CompileResult {
        private final boolean success;
        private final String  message;
        private CompileResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }

    public static class SubmissionResult {
        public RunResult status;
        public RunResult expectedStatus;
        public String    message;
        public int       passedCases;
        public int       totalCases;
        public boolean   expectationMet;
        public String    actualOutputSnippet;

        public SubmissionResult(RunResult status, String message, int passedCases, int totalCases) {
            this(status, null, message, passedCases, totalCases, status == RunResult.AC);
        }

        public SubmissionResult(RunResult status, RunResult expectedStatus, String message,
                                int passedCases, int totalCases, boolean expectationMet) {
            this.status         = status;
            this.expectedStatus = expectedStatus;
            this.message        = message;
            this.passedCases    = passedCases;
            this.totalCases     = totalCases;
            this.expectationMet = expectationMet;
        }
    }

    private record TestExecutionInfo(RunResult result, String actualOutput) {}

    // ==================================================================
    // PUBLIC: Run a full submission against all testcases
    // ==================================================================

    public SubmissionResult runCode(String sourceCode,
                                    String language,
                                    List<TestCase> testCases,
                                    int timeLimitMs,
                                    String checkerCode,
                                    RunResult expectedStatus) {
        String dirName = "sandbox_" + UUID.randomUUID();
        Path   dirPath = Paths.get("/tmp", dirName);  // always use /tmp for security

        try {
            Files.createDirectories(dirPath);
            File dirFile = dirPath.toFile();

            LanguageInfo langInfo  = LanguageInfo.from(language);
            String       fileName  = langInfo.fileName;
            File         sourceFile = new File(dirFile, fileName);
            Files.writeString(sourceFile.toPath(), sourceCode);

            CompileResult compileResult = compileProgram(langInfo, dirFile, fileName);
            if (!compileResult.success) {
                return buildResult(RunResult.CE, expectedStatus, compileResult.message, 0, testCases.size());
            }

            if (checkerCode != null && !checkerCode.isBlank()) {
                if (!prepareChecker(dirFile, checkerCode)) {
                    return buildResult(RunResult.RE, expectedStatus,
                            "Custom checker compilation failed", 0, testCases.size());
                }
            }

            int passed = 0;
            for (TestCase tc : testCases) {
                String expected   = tc.getOutputData();
                boolean isLazyAi = expected != null &&
                        (expected.contains("...") || expected.contains("(computed") || expected.contains("(etc)"));

                TestExecutionInfo info = executeSingleTest(
                        langInfo, dirFile, fileName, tc.getInputData(), expected, checkerCode, timeLimitMs);

                if (info.result != RunResult.AC) {
                    String actualSnippet = info.actualOutput != null ? info.actualOutput : "";
                    if (actualSnippet.length() > 100) actualSnippet = actualSnippet.substring(0, 100) + "...";

                    String failureMsg;
                    if (isLazyAi) {
                        failureMsg = "Judge Data Error: AI provided truncated output (contains '...'). Please Regenerate Testcases.";
                    } else if (info.result == RunResult.TLE) {
                        failureMsg = "Time Limit Exceeded at Testcase ID " + tc.getId() + ". Limit: " + timeLimitMs + " ms.";
                    } else if (info.result == RunResult.RE) {
                        failureMsg = "Runtime Error at Testcase ID " + tc.getId() + ": " +
                                (info.actualOutput != null ? info.actualOutput : "Unknown error");
                    } else {
                        String expectedSnippet = expected == null ? "<null>" : truncate(expected.trim(), 50);
                        failureMsg = "Failed at Testcase ID " + tc.getId() + ". " +
                                "Expected: " + expectedSnippet + " | Actual: " + actualSnippet;
                    }
                    return buildResult(info.result, expectedStatus, failureMsg, passed, testCases.size());
                }
                passed++;
            }

            return buildResult(RunResult.AC, expectedStatus, "All testcases passed!", passed, testCases.size());

        } catch (Exception e) {
            return buildResult(RunResult.RE, expectedStatus, "Runtime Exception: " + e.getMessage(), 0, testCases.size());
        } finally {
            deleteDir(dirPath.toFile());
        }
    }

    // ==================================================================
    // PUBLIC: Run golden (AC reference) solution to produce expected output
    // ==================================================================

    public String runGoldenSolution(String sourceCode, String language, String input, int timeLimitMs) {
        String dirName = "golden_" + UUID.randomUUID();
        Path   dirPath = Paths.get("/tmp", dirName);
        try {
            Files.createDirectories(dirPath);
            File         dirFile  = dirPath.toFile();
            LanguageInfo langInfo = LanguageInfo.from(language);
            String       fileName = langInfo.fileName;
            Files.writeString(new File(dirFile, fileName).toPath(), sourceCode);

            CompileResult cr = compileProgram(langInfo, dirFile, fileName);
            if (!cr.success) return null;

            TestExecutionInfo info = executeSingleTest(langInfo, dirFile, fileName, input, "", null, timeLimitMs);
            return info.actualOutput;
        } catch (Exception e) {
            return null;
        } finally {
            deleteDir(dirPath.toFile());
        }
    }

    // ==================================================================
    // PUBLIC: Run generator script
    // ==================================================================

    /**
     * Compile and run a generator script with given seed and size.
     * Generator must accept: --seed <int> --size <small|medium|large|stress>
     */
    public String runGenerator(String generatorCode, String generatorLanguage, int seed, String size) {
        String dirName = "gen_" + UUID.randomUUID();
        Path   dirPath = Paths.get("/tmp", dirName);
        try {
            Files.createDirectories(dirPath);
            File         dirFile  = dirPath.toFile();
            LanguageInfo langInfo = LanguageInfo.from(generatorLanguage);
            String       fileName = langInfo.fileName;
            Files.writeString(new File(dirFile, fileName).toPath(), generatorCode);

            CompileResult cr = compileProgram(langInfo, dirFile, fileName);
            if (!cr.success) {
                System.err.println("DEBUG: Generator compilation failed: " + cr.message);
                return null;
            }

            ProcessBuilder pb = buildGeneratorCommand(langInfo, dirFile, fileName, seed, size);
            Process process   = pb.start();

            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                killProcessGroup(process);
                System.err.println("DEBUG: Generator timed out (seed=" + seed + ", size=" + size + ")");
                return null;
            }

            if (process.exitValue() != 0) {
                System.err.println("DEBUG: Generator exited with error: " + readStream(process.getErrorStream()));
                return null;
            }

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line).append("\n");
                return sb.toString().trim();
            }

        } catch (Exception e) {
            System.err.println("DEBUG: Generator run failed: " + e.getMessage());
            return null;
        } finally {
            deleteDir(dirPath.toFile());
        }
    }

    // ==================================================================
    // PUBLIC: Run generated validator against one input
    // ==================================================================

    public boolean runValidator(String validatorCode, String input) {
        if (validatorCode == null || validatorCode.isBlank()) return true;

        String dirName = "validator_" + UUID.randomUUID();
        Path dirPath = Paths.get("/tmp", dirName);
        try {
            Files.createDirectories(dirPath);
            File dirFile = dirPath.toFile();
            File validatorFile = new File(dirFile, "validator.py");
            Files.writeString(validatorFile.toPath(), validatorCode);
            String pythonCmd = isCommandAvailable("python3") ? "python3" : "python";

            ProcessBuilder compile = new ProcessBuilder(pythonCmd, "-m", "py_compile", "validator.py");
            compile.directory(dirFile);
            Process compileProcess = compile.start();
            boolean compiled = compileProcess.waitFor(10, TimeUnit.SECONDS);
            if (!compiled) {
                compileProcess.destroyForcibly();
                System.err.println("DEBUG: Validator compilation timed out.");
                return false;
            }
            if (compileProcess.exitValue() != 0) {
                System.err.println("DEBUG: Validator compilation failed: " + readProcessOutput(compileProcess));
                return false;
            }

            ProcessBuilder run = new ProcessBuilder(pythonCmd, "validator.py");
            run.directory(dirFile);
            Process process = run.start();
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()))) {
                if (input != null) writer.write(input);
            }

            boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            if (!finished) {
                killProcessGroup(process);
                System.err.println("DEBUG: Validator timed out.");
                return false;
            }

            if (process.exitValue() != 0) {
                System.err.println("DEBUG: Validator rejected input: " + readStream(process.getErrorStream()));
                return false;
            }
            return true;
        } catch (Exception e) {
            System.err.println("DEBUG: Validator run failed: " + e.getMessage());
            return false;
        } finally {
            deleteDir(dirPath.toFile());
        }
    }

    // ==================================================================
    // PRIVATE: Language definitions
    // ==================================================================

    private enum LanguageInfo {
        JAVA  ("java",   "Main.java", "javac", "java",    "Main"),
        CPP   ("cpp",    "main.cpp",  "g++",   null,      "main"),
        PYTHON("python", "main.py",   "python3","python3","main.py");

        final String name;
        final String fileName;
        final String compilerCmd;
        final String runCmd;
        final String runArg;

        LanguageInfo(String name, String fileName, String compilerCmd, String runCmd, String runArg) {
            this.name        = name;
            this.fileName    = fileName;
            this.compilerCmd = compilerCmd;
            this.runCmd      = runCmd;
            this.runArg      = runArg;
        }

        static LanguageInfo from(String language) {
            for (LanguageInfo info : values()) {
                if (info.name.equals(language)) return info;
            }
            throw new IllegalArgumentException("Unsupported language: " + language);
        }
    }

    // ==================================================================
    // PRIVATE: Compilation
    // ==================================================================

    private CompileResult compileProgram(LanguageInfo langInfo, File dir, String fileName)
            throws IOException, InterruptedException {
        return switch (langInfo) {
            case PYTHON -> runCompilation(dir, "python3", "-m", "py_compile", fileName);
            case CPP    -> runCompilation(dir, langInfo.compilerCmd, fileName, "-O2",
                                          "-o", dir.getAbsolutePath() + File.separator + langInfo.runArg);
            case JAVA   -> runCompilation(dir, langInfo.compilerCmd, fileName);
        };
    }

    private CompileResult runCompilation(File dir, String... command) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(dir);
        pb.redirectErrorStream(true);
        try {
            Process process = pb.start();
            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new CompileResult(false, "Compilation timed out.");
            }
            String output = readProcessOutput(process).trim();
            return process.exitValue() == 0
                    ? new CompileResult(true,  output.isBlank() ? "Compilation succeeded." : output)
                    : new CompileResult(false, output.isBlank() ? "Compilation Error" : output);
        } catch (IOException ex) {
            return new CompileResult(false, "Compiler/interpreter not available: " + command[0]);
        }
    }

    // ==================================================================
    // PRIVATE: Checker preparation
    // ==================================================================

    private boolean prepareChecker(File dir, String checkerCode) throws IOException, InterruptedException {
        Files.writeString(new File(dir, CHECKER_CLASS_NAME + ".java").toPath(), checkerCode);
        Files.writeString(new File(dir, CHECKER_HARNESS_CLASS_NAME + ".java").toPath(), CHECKER_HARNESS_SOURCE);

        ProcessBuilder pb = new ProcessBuilder(
                "javac", CHECKER_CLASS_NAME + ".java", CHECKER_HARNESS_CLASS_NAME + ".java");
        pb.directory(dir);

        Process process  = pb.start();
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished) { process.destroyForcibly(); return false; }
        return process.exitValue() == 0;
    }

    // ==================================================================
    // PRIVATE: Single test execution — with sandbox hardening
    // ==================================================================

    private TestExecutionInfo executeSingleTest(LanguageInfo langInfo,
                                                File dir,
                                                String fileName,
                                                String input,
                                                String expectedOutput,
                                                String checkerCode,
                                                int timeLimitMs) {
        try {
            ProcessBuilder pb = buildSandboxedCommand(langInfo, dir, fileName);

            Process process = pb.start();

            // Feed input and close stdin immediately (prevents stdin-blocking)
            try (BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(process.getOutputStream()))) {
                if (input != null) writer.write(input);
            }

            boolean finished = process.waitFor(timeLimitMs + 2000L, TimeUnit.MILLISECONDS);
            if (!finished) {
                killProcessGroup(process);
                return new TestExecutionInfo(RunResult.TLE, null);
            }

            if (process.exitValue() != 0) {
                String errorMsg = readStream(process.getErrorStream());
                return new TestExecutionInfo(RunResult.RE, errorMsg);
            }

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                StringBuilder sb = new StringBuilder();
                String line;
                int lines = 0;
                while ((line = reader.readLine()) != null && lines < 100_000) {
                    sb.append(line).append("\n");
                    lines++;
                }
                String outputStr = sb.toString().trim();
                RunResult res = compareOutputs(dir, input, expectedOutput, outputStr, checkerCode)
                        ? RunResult.AC : RunResult.WA;
                return new TestExecutionInfo(res, outputStr);
            }

        } catch (Exception e) {
            return new TestExecutionInfo(RunResult.RE, e.getMessage());
        }
    }

    /**
     * Builds a sandboxed run command.
     *
     * On Linux: wraps the user program in:
     *   bash -c "ulimit -v <vmem> -u <nproc>; exec <program>"
     * This caps virtual memory and number of spawnable child processes,
     * preventing fork bombs and memory hogs.
     *
     * On non-Linux (Windows dev): runs the program directly.
     */
    private ProcessBuilder buildSandboxedCommand(LanguageInfo langInfo, File dir, String fileName) {
        String execPath = dir.getAbsolutePath() + File.separator + langInfo.runArg;

        if (IS_LINUX) {
            String ulimit = String.format(
                    "ulimit -v %d -u %d 2>/dev/null; exec ", SANDBOX_MAX_VMEM_KB, SANDBOX_MAX_NPROC);
            String cmd;
            cmd = switch (langInfo) {
                case CPP    -> ulimit + execPath;
                case PYTHON -> ulimit + (isCommandAvailable("python3") ? "python3" : "python")
                               + " " + fileName;
                case JAVA   -> ulimit + "java " + langInfo.runArg;
            };
            ProcessBuilder pb = new ProcessBuilder("bash", "-c", cmd);
            pb.directory(dir);
            return pb;
        }

        // Fallback — Windows / non-Linux
        ProcessBuilder pb = new ProcessBuilder();
        pb.directory(dir);
        return switch (langInfo) {
            case CPP    -> pb.command(execPath);
            case PYTHON -> pb.command(isCommandAvailable("python3") ? "python3" : "python", fileName);
            case JAVA   -> pb.command("java", langInfo.runArg);
        };
    }

    /**
     * Builds the generator run command (with --seed / --size args).
     */
    private ProcessBuilder buildGeneratorCommand(LanguageInfo langInfo, File dir,
                                                  String fileName, int seed, String size) {
        String execPath = dir.getAbsolutePath() + File.separator + langInfo.runArg;

        if (IS_LINUX) {
            String ulimit = String.format(
                    "ulimit -v %d -u %d 2>/dev/null; exec ", SANDBOX_MAX_VMEM_KB, SANDBOX_MAX_NPROC);
            String cmd = switch (langInfo) {
                case CPP    -> ulimit + execPath + " --seed " + seed + " --size " + size;
                case PYTHON -> ulimit + (isCommandAvailable("python3") ? "python3" : "python")
                               + " " + fileName + " --seed " + seed + " --size " + size;
                case JAVA   -> ulimit + "java " + langInfo.runArg + " --seed " + seed + " --size " + size;
            };
            ProcessBuilder pb = new ProcessBuilder("bash", "-c", cmd);
            pb.directory(dir);
            return pb;
        }

        // Fallback — Windows
        ProcessBuilder pb = new ProcessBuilder();
        pb.directory(dir);
        return switch (langInfo) {
            case CPP    -> pb.command(execPath, "--seed", String.valueOf(seed), "--size", size);
            case PYTHON -> pb.command(
                    isCommandAvailable("python3") ? "python3" : "python",
                    fileName, "--seed", String.valueOf(seed), "--size", size);
            case JAVA   -> pb.command("java", langInfo.runArg,
                    "--seed", String.valueOf(seed), "--size", size);
        };
    }

    // ==================================================================
    // PRIVATE: Kill process and its entire process group (Linux)
    // ==================================================================

    /**
     * Forcibly kills the process and, on Linux, its entire process group.
     * This is the correct way to prevent leftover zombie/orphan processes
     * that could result from fork bombs.
     */
    private void killProcessGroup(Process process) {
        if (IS_LINUX) {
            try {
                // Get PID and kill the whole process group
                long pid = process.pid();
                new ProcessBuilder("bash", "-c", "kill -9 -" + pid)
                        .start()
                        .waitFor(2, TimeUnit.SECONDS);
            } catch (Exception ignore) {}
        }
        process.destroyForcibly();
    }

    // ==================================================================
    // PRIVATE: Output comparison
    // ==================================================================

    private boolean compareOutputs(File dir, String input, String expectedOutput,
                                   String actualOutput, String checkerCode)
            throws IOException, InterruptedException {
        if (checkerCode == null || checkerCode.isBlank()) {
            return compareTokens(expectedOutput, actualOutput);
        }

        Files.writeString(new File(dir, "checker_input.txt").toPath(),    input           == null ? "" : input);
        Files.writeString(new File(dir, "checker_expected.txt").toPath(), normalizeOutput(expectedOutput));
        Files.writeString(new File(dir, "checker_actual.txt").toPath(),   normalizeOutput(actualOutput));

        ProcessBuilder pb = new ProcessBuilder("java", CHECKER_HARNESS_CLASS_NAME);
        pb.directory(dir);
        Process process  = pb.start();
        boolean finished = process.waitFor(10, TimeUnit.SECONDS);
        if (!finished) { process.destroyForcibly(); return false; }
        if (process.exitValue() != 0) return false;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            return "OK".equals(reader.readLine());
        }
    }

    private boolean compareTokens(String expected, String actual) {
        if (expected == null || actual == null) return expected == actual;
        String[] expectedTokens = expected.trim().split("\\s+");
        String[] actualTokens   = actual.trim().split("\\s+");
        if (expectedTokens.length != actualTokens.length) return false;
        for (int i = 0; i < expectedTokens.length; i++) {
            if (!expectedTokens[i].equals(actualTokens[i])) return false;
        }
        return true;
    }

    private String normalizeOutput(String output) {
        return output == null ? "" : output.trim().replace("\r\n", "\n");
    }

    // ==================================================================
    // PRIVATE: Stream / utility helpers
    // ==================================================================

    private String readProcessOutput(Process process) throws IOException {
        String out = readStream(process.getInputStream());
        String err = readStream(process.getErrorStream());
        if (err == null || err.isBlank()) return out;
        return out + "\n[STDERR]\n" + err;
    }

    private String readStream(InputStream is) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
            StringBuilder sb = new StringBuilder();
            String line;
            int linesRead = 0;
            while ((line = reader.readLine()) != null && linesRead < 50) {
                sb.append(line).append("\n");
                linesRead++;
            }
            return sb.toString().trim();
        }
    }

    private String truncate(String s, int n) {
        if (s == null || s.length() <= n) return s;
        return s.substring(0, n) + "...";
    }

    private SubmissionResult buildResult(RunResult actualStatus, RunResult expectedStatus,
                                         String baseMessage, int passedCases, int totalCases) {
        boolean expectationMet = expectedStatus == null || actualStatus == expectedStatus;
        String message = expectedStatus == null
                ? baseMessage
                : baseMessage + System.lineSeparator()
                  + "Expected verdict: " + expectedStatus + " | Actual verdict: " + actualStatus;
        return new SubmissionResult(actualStatus, expectedStatus, message, passedCases, totalCases, expectationMet);
    }

    private boolean isCommandAvailable(String command) {
        try {
            Process p = new ProcessBuilder(IS_LINUX ? "which" : "where", command).start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void deleteDir(java.io.File file) {
        if (file.isDirectory()) {
            java.io.File[] entries = file.listFiles();
            if (entries != null) for (java.io.File e : entries) deleteDir(e);
        }
        file.delete();
    }
}
