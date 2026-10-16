package app.smallthingz.alldoc.clienttest

import android.app.Service
import android.content.Intent
import android.os.IBinder
import app.smallthingz.alldoc.api.IAllDocSession

/** Test-only second UID. It receives a session but must not gain the creator's capability. */
class IsolatedProbeService : Service() {
    override fun onBind(intent: Intent): IBinder = object : ISessionProbe.Stub() {
        override fun rejects(session: IBinder): Boolean = try { IAllDocSession.Stub.asInterface(session).status(); false }
            catch (_: SecurityException) { true }
    }
}
