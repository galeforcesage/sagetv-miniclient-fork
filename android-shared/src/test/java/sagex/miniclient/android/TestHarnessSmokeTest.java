package sagex.miniclient.android;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Smoke test to verify the unit-test harness builds and runs.
 * If this test passes, JUnit + the android-shared test source set are wired
 * up correctly so the trickplay refactor can add real coverage.
 */
public class TestHarnessSmokeTest {
    @Test
    public void harnessRuns() {
        assertEquals(2, 1 + 1);
    }
}
