package com.ishita.tripcoordinatorplatform.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

@Component
@ConditionalOnProperty(
        name = "app.document-storage.provider",
        havingValue = "local",
        matchIfMissing = true
)

public class LocalDocumentStorage implements DocumentStorage{

    private final Path rootDirectory;

    public LocalDocumentStorage(
            @Value("${app.document-storage.local-directory:./data/documents}")
            String rootDirectory
    ) {
        this.rootDirectory = Path.of(rootDirectory);
    }

    @Override
    public String store(
            Long tripId,
            String originalFileName,
            String contentType,
            long contentLength,
            InputStream inputStream
    ) {
        try {
            // Remove any directory information supplied by the original filename.
            String safeFileName = Path.of(originalFileName)
                    .getFileName()
                    .toString();

            String storedFileName =
                    UUID.randomUUID() + "-" + safeFileName;

            Path relativePath = Path.of(
                    "trips",
                    tripId.toString(),
                    "documents",
                    storedFileName
            );

            Path destination = rootDirectory.resolve(relativePath);

            Files.createDirectories(destination.getParent());

            Files.copy(
                    inputStream,
                    destination,
                    StandardCopyOption.REPLACE_EXISTING
            );

            return relativePath.toString();

        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Failed to store document",
                    exception
            );
        }
    }

    @Override
    public InputStream open(String storageKey) {
        try {
            // Resolve the database storage key against the configured local storage directory.
            Path documentPath = rootDirectory.resolve(storageKey);

            return Files.newInputStream(documentPath);

        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Failed to open stored document",
                    exception
            );
        }
    }

}
