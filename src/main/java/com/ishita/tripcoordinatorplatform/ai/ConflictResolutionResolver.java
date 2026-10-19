package com.ishita.tripcoordinatorplatform.ai;

public interface ConflictResolutionResolver {

    String resolve(String prompt);

    default String resolve(ConflictResolutionContext context, String prompt) { return resolve(prompt); }


}
