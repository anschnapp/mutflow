package sample

import io.github.anschnapp.mutflow.MutationTarget
import java.util.concurrent.CountDownLatch

/**
 * Code that waits for something a mutation can prevent from ever happening.
 * There is no loop for the loop guard to check: the mutant parks the thread.
 */
@MutationTarget
class WaitingTarget {

    /** Releases the latch when [ready] and then waits for it. Mutating the condition waits forever. */
    fun awaitWhenReady(ready: Boolean): Boolean {
        val latch = CountDownLatch(1)
        if (ready) latch.countDown()
        latch.await()
        return true
    }
}
