package com.ishita.tripcoordinatorplatform.config;

import com.ishita.tripcoordinatorplatform.ai.document.LocalSplitReplyDump;
import com.ishita.tripcoordinatorplatform.ai.document.SplitReplyRecorder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Local-only debugging aid: writes raw split-call replies (which contain document text) to a folder under build/.
 * Startup fails rather than running it outside a local development setup.
 */
@Configuration
@ConditionalOnExpression("'${app.document-processing.split-extraction.debug-dump-dir:}' != ''")
public class SplitDebugDumpConfig {
    static final String PROPERTY = "app.document-processing.split-extraction.debug-dump-dir";

    @Bean
    public SplitReplyRecorder splitReplyRecorder(Environment environment) throws IOException {
        requireLocalOnly(environment);
        Path build = Path.of("build").toAbsolutePath().normalize();
        Path requested = Path.of(environment.getProperty(PROPERTY, "")).toAbsolutePath().normalize();
        if (!requested.startsWith(build) || requested.equals(build))
            throw new IllegalStateException(PROPERTY + " must be a folder inside the project's build/ directory");
        var dump = new LocalSplitReplyDump(requested);
        // A symlink inside build/ must not lead out of it.
        if (!dump.directory().startsWith(Files.exists(build) ? build.toRealPath() : build))
            throw new IllegalStateException(PROPERTY + " resolves outside the project's build/ directory");
        return dump;
    }

    private static void requireLocalOnly(Environment environment) {
        if (!"LOCAL".equals(environment.getProperty("app.document-processing.execution-mode", "")))
            throw new IllegalStateException(PROPERTY + " is only allowed with app.document-processing.execution-mode=LOCAL");
        if (environment.containsProperty("AWS_LAMBDA_FUNCTION_NAME") || environment.containsProperty("AWS_EXECUTION_ENV"))
            throw new IllegalStateException(PROPERTY + " must never be used in AWS");
    }
}
