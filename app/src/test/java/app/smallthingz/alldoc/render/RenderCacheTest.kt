package app.smallthingz.alldoc.render

import java.io.File
import java.io.IOException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RenderCacheTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun scratchReusesOneSlotAndRejectsAnUnusableRoot() {
        val root = temporary.newFolder()
        val old = prepareRenderScratch(root)
        File(old, "receipt").writeText("stale page")
        File(old, "nested/partial").apply { requireNotNull(parentFile).mkdirs(); writeText("partial") }
        val fresh = prepareRenderScratch(root)
        assertEquals(old, fresh)
        assertTrue(fresh.isDirectory)
        assertTrue(requireNotNull(fresh.list()).isEmpty())
        assertArrayEquals(arrayOf("visual"), root.list())
        val unavailable = temporary.newFile()
        assertThrows(IOException::class.java) { prepareRenderScratch(unavailable) }
        assertTrue(unavailable.isFile)
    }

    @Test fun crashCleanupPreservesLiveProcessesAndDoesNotDeleteActiveScratch() {
        val root = temporary.root
        fun receipt(name: String): File = File(root, "$name/visual-test/receipt").apply {
            requireNotNull(parentFile).mkdirs(); writeText("owned scratch")
        }
        val legacy = receipt("render")
        val current = receipt("render-42")
        val dead = receipt("render-91")
        val live = receipt("render-90")
        val unrelated = receipt("documents-91")
        val cache = RenderCache()
        val directory = cache.prepare(root, 42) { it == 90 }
        assertEquals(File(root, "render-42"), directory)
        assertTrue(directory.isDirectory)
        assertFalse(legacy.exists()); assertFalse(current.exists()); assertFalse(dead.exists())
        assertTrue(live.exists()); assertTrue(unrelated.exists())
        val active = receipt("render-42")
        assertEquals(directory, cache.prepare(root, 42) { error("Must initialize only once") })
        assertTrue(active.exists())
        RenderCache().prepare(root, 43) { it == 42 || it == 90 }
        assertTrue(active.exists()); assertTrue(live.exists())
    }

    @Test fun removedCacheParentIsRecreatedForNewAndExistingReaders() {
        val root = temporary.newFolder()
        val cache = RenderCache()
        val directory = cache.prepare(root, 42) { false }
        assertTrue(directory.delete())
        assertEquals(directory, cache.prepare(root, 42) { error("Must not repeat crash cleanup") })
        assertTrue(directory.isDirectory)
        val active = File(directory, "active").apply { writeText("keep") }
        cache.prepare(root, 42) { error("Must not repeat crash cleanup") }
        assertEquals("keep", active.readText())
        assertTrue(active.delete())
        assertTrue(directory.delete())
        val scratch = prepareRenderScratch(directory)
        assertTrue(scratch.isDirectory)
        assertEquals(directory, scratch.parentFile)
    }

}
