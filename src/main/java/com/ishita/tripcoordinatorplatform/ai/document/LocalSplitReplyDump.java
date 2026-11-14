package com.ishita.tripcoordinatorplatform.ai.document;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Writes each split call's reply text, exactly as returned, to one owner-only file. Replies contain document text,
 * so nothing here logs or returns their content. Construction is guarded by the configuration, not by this class.
 */
public final class LocalSplitReplyDump implements SplitReplyRecorder {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss.SSS").withZone(ZoneOffset.UTC);
    private static final boolean POSIX = java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    private final Path directory;
    private final AtomicInteger sequence = new AtomicInteger();

    /** Creates the directory (owner-only) and resolves symlinks, so callers can re-check where it really is. */
    public LocalSplitReplyDump(Path directory) throws IOException {
        if (POSIX) Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        else Files.createDirectories(directory);
        this.directory = directory.toRealPath();
    }

    public Path directory() { return directory; }

    @Override
    public void record(SplitCallTarget target, String responseId, String status, String rawReply) {
        String kind = target.kind() == SplitCallTarget.Kind.SHARED ? "SHARED" : "BOOKING-" + target.ordinal();
        String suffix = "completed".equalsIgnoreCase(status) ? "" : "-" + sanitize(status == null ? "no-status" : status);
        String stem = STAMP.format(Instant.now()) + "-" + kind + "-" + sanitize(responseId) + suffix;
        for (int attempt = 0; attempt < 100; attempt++) {
            try {
                var file = directory.resolve(stem + (attempt == 0 ? "" : "-" + sequence.incrementAndGet()) + ".json");
                if (POSIX) Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                else Files.createFile(file);
                Files.writeString(file, rawReply == null ? "" : rawReply, StandardCharsets.UTF_8);
                return;
            } catch (FileAlreadyExistsException taken) {
                // Same millisecond and response: try the next suffix.
            } catch (IOException failure) {
                throw new java.io.UncheckedIOException(failure);
            }
        }
    }

    private static String sanitize(String text) {
        String clean = text == null ? "none" : text.replaceAll("[^A-Za-z0-9_-]", "_");
        return clean.length() > 60 ? clean.substring(0, 60) : clean;
    }
}
