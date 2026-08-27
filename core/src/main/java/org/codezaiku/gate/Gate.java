package org.codezaiku.gate;

import java.nio.file.Path;

/**
 * The certification authority. A gate runs REAL execution against a project and returns a
 * verdict grounded in observed behavior — never the model's say-so, never the model's own
 * tests (those are feedback). The familiar's {@code task_done} is only accepted if the gate
 * passes; a failure is fed back raw and cause-first.
 */
public interface Gate {
    GateResult certify(Path projectRoot);
}
