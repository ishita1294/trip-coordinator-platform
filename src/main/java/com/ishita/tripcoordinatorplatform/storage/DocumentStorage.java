package com.ishita.tripcoordinatorplatform.storage;

import java.io.InputStream;

public interface DocumentStorage {


    String store(
            Long tripId,
            String originalFileName,
            String contentType,
            long contentLength,
            InputStream inputStream
    );

    // Opens a previously stored document so processing code can read its contents.
    InputStream open(String storageKey);
}
