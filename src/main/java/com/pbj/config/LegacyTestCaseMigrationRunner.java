package com.pbj.config;

import com.pbj.service.LegacyTestCaseMigrationService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LegacyTestCaseMigrationRunner implements ApplicationRunner {

    private final LegacyTestCaseMigrationService legacyTestCaseMigrationService;

    @Value("${testcase.legacy-migration.enabled:true}")
    private boolean enabled;

    @Value("${testcase.legacy-migration.batch-size:500}")
    private int batchSize;

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) return;

        LegacyTestCaseMigrationService.MigrationReport report =
                legacyTestCaseMigrationService.migrateLegacyPayloads(batchSize);
        if (report.scanned() > 0) {
            System.out.println("INFO: Legacy testcase migration scanned=" + report.scanned()
                    + ", migrated=" + report.migrated()
                    + ", alreadyExternalized=" + report.alreadyExternalized()
                    + ", skipped=" + report.skipped());
        }
    }
}
