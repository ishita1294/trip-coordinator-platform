package com.ishita.tripcoordinatorplatform.lambda;

import com.ishita.tripcoordinatorplatform.config.RuntimeSecrets;
import java.util.Map;

/** Preserve Lambda cold-start loading and the SDK default region chain. */
final class LambdaRuntimeSecrets {
    static Map<String, Object> loadFromEnvironment() {
        return RuntimeSecrets.load(System.getenv("RDS_SECRET_ARN"), System.getenv("OPENAI_SECRET_ARN"), null);
    }
}
