package com.example.voltahome.local;

/** Test itsme accounts offered by the itsme stub page. All data is fictitious (see data.sql). */
enum ItsmeStubScenario {

    ALEX("Alex Martin — itsme linked to VoltaHome", "stub-sub-alex", false, false),
    SAM("Sam Peeters — VoltaHome customer, itsme not linked", "stub-sub-sam", false, false),
    STRANGER("Someone who is not a VoltaHome customer", "stub-sub-stranger", false, false),
    ROBIN("Robin Dubois — linked, but contract closed", "stub-sub-robin", false, false),
    ONGOING_CALL("Alex Martin — phone call in progress during approval", "stub-sub-alex", true, false),
    FINGERPRINT_ONLY("Alex Martin — fingerprint only (acr basic)", "stub-sub-alex", false, true);

    final String label;
    final String sub;
    final boolean ongoingCall;
    final boolean basicAcrOnly;

    ItsmeStubScenario(String label, String sub, boolean ongoingCall, boolean basicAcrOnly) {
        this.label = label;
        this.sub = sub;
        this.ongoingCall = ongoingCall;
        this.basicAcrOnly = basicAcrOnly;
    }
}
