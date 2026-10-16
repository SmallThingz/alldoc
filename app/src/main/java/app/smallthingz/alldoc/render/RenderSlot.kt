package app.smallthingz.alldoc.render

import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/** Wake on handoff while retaining bounded cancellation/deadline checks. */
internal fun acquireRenderSlot(slot: Semaphore, check: () -> Unit) {
    while (true) {
        check()
        if (slot.tryAcquire(10, TimeUnit.MILLISECONDS)) return
    }
}
