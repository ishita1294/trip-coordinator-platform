package com.ishita.tripcoordinatorplatform;

import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import com.ishita.tripcoordinatorplatform.config.RuntimeSecrets;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class TripCoordinatorPlatformApplication {

    public static void main(String[] args) {
        var application = new SpringApplication(TripCoordinatorPlatformApplication.class);
        application.addInitializers(context -> {
            var environment = context.getEnvironment();
            if (environment.getProperty("app.runtime-secrets.enabled", Boolean.class, false)) {
                var secrets = RuntimeSecrets.load(
                        environment.getProperty("app.runtime-secrets.rds-secret-arn"),
                        environment.getProperty("app.runtime-secrets.openai-secret-arn"),
                        environment.getProperty("app.runtime-secrets.region"),
                        environment.getProperty("app.explore.google.enabled", Boolean.class, false)
                                ? environment.getRequiredProperty("app.runtime-secrets.google-secret-arn") : null);
                environment.getPropertySources().addFirst(new MapPropertySource("runtimeSecrets", secrets));
            }
        });
        application.run(args);
    }

}
