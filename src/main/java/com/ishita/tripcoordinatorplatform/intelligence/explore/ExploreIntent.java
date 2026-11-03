package com.ishita.tripcoordinatorplatform.intelligence.explore;

/** Optional intent within a broad preference; new preferences can add their own values later. */
public enum ExploreIntent {
    MALLS(ExplorePreference.SHOPPING),
    LOCAL_MARKETS(ExplorePreference.SHOPPING),
    FASHION(ExplorePreference.SHOPPING),
    ELECTRONICS(ExplorePreference.SHOPPING),
    BOOKS_STATIONERY(ExplorePreference.SHOPPING);

    private final ExplorePreference preference;
    ExploreIntent(ExplorePreference preference) { this.preference = preference; }
    public ExplorePreference preference() { return preference; }
}
