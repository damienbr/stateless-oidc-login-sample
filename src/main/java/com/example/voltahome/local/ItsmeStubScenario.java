package com.example.voltahome.local;

/** LOCAL DEVELOPMENT ONLY. Test itsme accounts offered by the itsme stub page. All data is fictitious (see data.sql). */
enum ItsmeStubScenario {

    ALEX("Alex Martin — itsme linked to VoltaHome", "stub-sub-alex", false),
    SAM("Sam Peeters — VoltaHome customer, itsme not linked", "stub-sub-sam", false),
    STRANGER("Someone who is not a VoltaHome customer", "stub-sub-stranger", false),
    ROBIN("Robin Dubois — linked, but contract closed", "stub-sub-robin", false),
    FINGERPRINT_ONLY("Alex Martin — fingerprint only (acr basic)", "stub-sub-alex", true);

    final String label;
    final String sub;
    final boolean basicAcrOnly;

    ItsmeStubScenario(String label, String sub, boolean basicAcrOnly) {
        this.label = label;
        this.sub = sub;
        this.basicAcrOnly = basicAcrOnly;
    }
}
