package eu.kanade.tachiyomi.network.interceptor

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlameComicsJsonRepairInterceptorTest {

    @Test
    fun `adds missing series fields`() {
        val repaired = repairFlameComicsJsonBody("""{"title":"Test","slug":"test","id":1}""")
        assertNotNull(repaired)
        assertTrue(repaired!!.contains("\"altTitles\""))
        assertTrue(repaired.contains("\"tags\""))
        assertTrue(repaired.contains("\"views\""))
    }
}
