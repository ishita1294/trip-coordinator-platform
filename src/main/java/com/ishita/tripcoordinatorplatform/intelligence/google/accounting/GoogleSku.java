package com.ishita.tripcoordinatorplatform.intelligence.google.accounting;

/** Reviewed India gross list prices. Free allowances/credits never reduce admission estimates. */
public enum GoogleSku {
    TEXT_PRO("PLACES_TEXT", "2DB9-E41E-AC2A", 9600),
    DETAILS_PRO("PLACES_DETAILS", "1F70-206B-61DF", 5100),
    NEARBY_ENTERPRISE("PLACES_NEARBY", "B049-A814-B2A8", 10500),
    DETAILS_ENTERPRISE("PLACES_DETAILS", "448A-2967-E6BF", 6000),
    TEXT_ENTERPRISE("PLACES_TEXT", "D112-1B19-2E0B", 10500),
    ROUTES_ESSENTIALS("ROUTES_COMPUTE", "890F-D2B6-C2EA", 1500),
    ROUTES_PRO("ROUTES_COMPUTE", "3CC6-0DC6-1C5F", 3000);

    public final String operationKind;
    public final String skuId;
    public final long unitMicros;
    GoogleSku(String kind, String skuId, long micros) {
        this.operationKind = kind; this.skuId = skuId; this.unitMicros = micros;
    }
}
