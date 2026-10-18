package app.smallthingz.alldoc.api

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.ArrayDeque
import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AllDocClientBindingTest {
    @Test fun failedBindReleasesRegistrationAndSuccessfulBindStillClosesOnce() {
        val context = BindingContext()
        val tasks = ArrayDeque<Runnable>()
        val executor = Executor { tasks.addLast(it) }
        val component = ComponentName(AllDocClient.PACKAGE, AllDocClient.SERVICE)
        var callbacks = 0
        repeat(3) { attempt ->
            val failure = runCatching {
                AllDocClient.connect(context, executor, onDisconnected = { callbacks++ }) { callbacks++ }
            }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertEquals("AllDoc is not installed or its service is unavailable", failure?.message)
            assertTrue("Failed bind retained its registration", context.registrations.isEmpty())
            assertEquals(attempt + 1, context.unbinds)
            // A delayed framework event cannot publish an owner that connect never returned.
            val connection = checkNotNull(context.lastConnection)
            connection.onServiceConnected(component, Binder())
            connection.onServiceDisconnected(component)
            connection.onNullBinding(component)
            connection.onBindingDied(component)
            assertTrue(tasks.isEmpty())
            assertEquals(0, callbacks)
        }

        val denied = SecurityException("Synthetic binding denial")
        context.failure = denied
        val failure = runCatching {
            AllDocClient.connect(context, executor, onDisconnected = { callbacks++ }) { callbacks++ }
        }.exceptionOrNull()
        assertSame("Binding denial must preserve the original failure", denied, failure)
        assertTrue("Denied bind retained its registration", context.registrations.isEmpty())
        assertEquals(4, context.unbinds)
        assertTrue(tasks.isEmpty())
        assertEquals(0, callbacks)

        context.failure = null
        context.accept = true
        val client = AllDocClient.connect(context, executor, onDisconnected = { callbacks++ }) { callbacks++ }
        client.use {
            assertEquals(1, context.registrations.size)
            assertEquals(4, context.unbinds)
            checkNotNull(context.lastConnection).onServiceConnected(component, Binder())
            assertEquals(1, tasks.size)
        }
        client.close()
        assertTrue(context.registrations.isEmpty())
        assertEquals(5, context.unbinds)
        tasks.removeFirst().run()
        assertEquals("Closing must suppress the queued connected callback", 0, callbacks)
    }

    private class BindingContext : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
        var accept = false
        var failure: SecurityException? = null
        var unbinds = 0
        var lastConnection: ServiceConnection? = null
        val registrations = mutableSetOf<ServiceConnection>()
        override fun getApplicationContext(): Context = this
        override fun bindService(service: Intent, conn: ServiceConnection, flags: Int): Boolean {
            assertEquals(ComponentName(AllDocClient.PACKAGE, AllDocClient.SERVICE), service.component)
            assertEquals(Context.BIND_AUTO_CREATE, flags)
            // ContextImpl registers the dispatcher before it knows the bind result.
            check(registrations.add(conn))
            lastConnection = conn
            failure?.let { throw it }
            return accept
        }
        override fun unbindService(conn: ServiceConnection) {
            check(registrations.remove(conn)) { "Connection was not registered" }
            unbinds++
        }
    }
}
