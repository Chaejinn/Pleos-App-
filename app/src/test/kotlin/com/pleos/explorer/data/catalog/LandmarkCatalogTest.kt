package com.pleos.explorer.data.catalog

import com.pleos.explorer.di.ExplorerApplication
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = ExplorerApplication::class)
class LandmarkCatalogTest {
    @Test fun all897SuppliedPlacesLoadWithStableUniqueIds() {
        val values = LandmarkLoader.load(RuntimeEnvironment.getApplication())
        assertEquals(897, values.size)
        assertEquals(897, values.map { it.id }.toSet().size)
        assertEquals(6, values.map { it.type }.toSet().size)
        assertTrue(values.all { it.lat in 33.0..39.0 && it.lon in 124.0..132.0 && it.radiusM in 150.0..500.0 })
    }
    @Test(expected = IllegalArgumentException::class) fun duplicateIdentityIsNotSilentlyAccepted() {
        val one = """{"id":"a","name":"명소","region":"서울","type":"NATURE","lat":37.5,"lon":127,"radiusM":200}"""
        LandmarkLoader.parse(JSONArray("[$one,$one]"))
    }
    @Test(expected = IllegalArgumentException::class) fun invalidCoordinateFailsExplicitly() {
        LandmarkLoader.parse(JSONArray("""[{"id":"a","name":"명소","region":"서울","type":"LANDMARK","lat":95,"lon":127}]"""))
    }
}
