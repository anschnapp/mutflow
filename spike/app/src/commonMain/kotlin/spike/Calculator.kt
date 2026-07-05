package spike

import io.github.anschnapp.mutflow.MutationTarget

/**
 * TEACHING NOTE: the code under mutation. Deliberately tiny.
 *
 * `x > 0` should produce (per the JVM behavior we want to reproduce):
 *  - RelationalComparisonOperator: 2 variants (>= boundary, < flip)
 *  - ConstantBoundaryOperator on the 0: 2 variants (1, -1)
 *
 * Phase 2: running the test binary with MUTFLOW_DISCOVERY_FILE=<path> makes
 * the real native mutflow-core collect these points (plus touch counts) into
 * the file, and running it with e.g.
 * MUTFLOW_ACTIVE_MUTATION=spike.Calculator_0:0 makes isPositive(0) return
 * true (mutated to x >= 0) and the test suite fail - which is the "mutation
 * killed" signal on the native path (exit-code inversion happens later, in
 * the Phase-3 Gradle task).
 *
 * This file sits in commonMain even though the spike only has a linuxX64
 * target - matching the intended real-world authoring model where production
 * code is common and mutation testing runs per target.
 */
@MutationTarget
class Calculator {
    fun isPositive(x: Int): Boolean {
        return x > 0
    }
}
