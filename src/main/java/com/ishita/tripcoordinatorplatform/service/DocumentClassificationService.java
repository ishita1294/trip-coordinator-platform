package com.ishita.tripcoordinatorplatform.service;

import com.ishita.tripcoordinatorplatform.ai.document.DocumentAiInput;
import com.ishita.tripcoordinatorplatform.ai.document.DocumentClassification;
import com.ishita.tripcoordinatorplatform.ai.document.DocumentClassificationParser;
import com.ishita.tripcoordinatorplatform.ai.document.DocumentClassifier;
import org.springframework.stereotype.Service;

@Service
public class DocumentClassificationService {

    private final DocumentClassifier documentClassifier;
    private final DocumentClassificationParser classificationParser;

    public DocumentClassificationService(
            DocumentClassifier documentClassifier,
            DocumentClassificationParser classificationParser
    ) {
        this.documentClassifier = documentClassifier;
        this.classificationParser = classificationParser;
    }

    public DocumentClassification classify(DocumentAiInput document) {

        // Treat provider output as untrusted until it passes application validation.
        String rawClassification =
                documentClassifier.classify(document);

        return classificationParser.parse(rawClassification);
    }
}
