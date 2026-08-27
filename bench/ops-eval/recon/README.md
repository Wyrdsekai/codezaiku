# Recon jail contract test

The `ReconJail` is load-bearing: it is the only thing that keeps recon-derived library knowledge sound
(a blind step cannot leak a grader's answer it was structurally prevented from reading). So its contract is
verified, not assumed.

Run it against the built classes:

    ./gradlew :core:compileJava -q
    CP=core/build/classes/java/main
    javac -cp "$CP" -d /tmp/recon-test bench/ops-eval/recon/JailContractTest.java
    java  -cp "$CP:/tmp/recon-test" JailContractTest

Expected: `=== jail: 25 passed, 0 FAILED ===` (exit 0). It asserts the eval jail DENIES every answer-key
path (record.csv / query.csv / scoring_points / tests/ / solution/ / check.sh / reward.txt) and every
secret, ALLOWS genuine stack files (configs, logs), and REFUSES all mutating/escape commands.
