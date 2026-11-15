package com.pleos.explorer.service

import androidx.room.Room
import com.pleos.explorer.data.catalog.RegionCatalogState
import com.pleos.explorer.data.local.recording.*
import com.pleos.explorer.di.ExplorerApplication
import com.pleos.explorer.domain.engine.LocationValidator
import com.pleos.explorer.domain.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = ExplorerApplication::class)
class DriveLifecycleSessionTest {
    private val base = 1_800_000_000_000L
    private val support = VehicleCapabilities(SignalSupport.SUPPORTED,SignalSupport.SUPPORTED,SignalSupport.SUPPORTED)
    private fun location(i: Int): TripInput.Location {
        val sample = LocationSample(base + i * 1000L,i * 1000L,GeoPoint(36.0 + i * 0.0001,127.0),11.1,0.0,5.0)
        return TripInput.Location(LocationValidator().validate(sample,null),sample.elapsedMs,sample.timeMs)
    }
    @Test fun drivePauseResumeParkPersistsSeparatedPathAndIgnoresDuplicateInput() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),RecordingDatabase::class.java).build()
        val store = RoomTrackRecordingStore(db,RecordingCipher(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()))
        val session = DriveLifecycleSession(store,"lifecycle",RecordingInputSource.REPLAY,RegionCatalogState.Missing)
        var seq = 0L
        session.process(seq++,TripInput.AccessChanged(true,true,0,base))
        val start = session.process(seq++,TripInput.Vehicle(TripVehicleSample(base,0,Gear.D,false,11.1),support))
        val trip = start.lifecycle.transitions.filterIsInstance<TripTransition.Started>().single().trip.id
        (0..15).forEach { session.process(seq++,location(it)) }
        session.process(seq++,TripInput.Vehicle(TripVehicleSample(base+16_000,16_000,parkingBrakeEngaged=true)))
        (16..20).forEach { session.process(seq++,location(it)) }
        val pausedDistance = requireNotNull(store.load(trip)).trip.distanceM
        session.process(seq++,TripInput.Vehicle(TripVehicleSample(base+21_000,21_000,Gear.D,false,11.1),support))
        (21..40).forEach { session.process(seq++,location(it)) }
        val park = TripInput.Vehicle(TripVehicleSample(base+41_000,41_000,Gear.P,false,0.0),support)
        session.process(seq,park)
        val finished = requireNotNull(store.load(trip))
        assertEquals(TripStatus.FINISHED,finished.status)
        assertTrue(finished.trip.distanceM > pausedDistance)
        assertEquals(2,finished.trip.simplifiedPath.size)
        assertTrue(session.process(seq,park).recordings.isEmpty())
        assertEquals(finished.revision,store.load(trip)?.revision)
        assertEquals(RecordingState.IDLE,store.lifecycle("lifecycle")?.recordingState)
        assertTrue(store.regionBadges().isEmpty())
        assertEquals(0.0,store.titleStore.load().progress.newRoadDistanceM,0.0)
        db.close()
    }
    @Test fun unsupportedGearFallbackIncludesInitialCandidateAndRevocationEndsTrip() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),RecordingDatabase::class.java).build()
        val store = RoomTrackRecordingStore(db,RecordingCipher(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()))
        val session = DriveLifecycleSession(store,"fallback",RecordingInputSource.REPLAY,RegionCatalogState.Missing)
        var seq=0L
        session.process(seq++,TripInput.AccessChanged(true,true,0,base))
        session.process(seq++,TripInput.Vehicle(TripVehicleSample(base,0),VehicleCapabilities(SignalSupport.UNSUPPORTED,SignalSupport.UNSUPPORTED,SignalSupport.UNSUPPORTED)))
        (0..30).forEach { session.process(seq++,location(it)) }
        val trip = requireNotNull(store.lifecycle("fallback")?.tripId)
        assertEquals(base,store.load(trip)?.trip?.startedAtMs)
        assertTrue(store.loadPoints(trip).size > 1)
        session.process(seq,TripInput.AccessChanged(false,true,31_000,base+31_000))
        assertEquals(TripStatus.FINISHED,store.load(trip)?.status)
        assertFalse(requireNotNull(store.lifecycle("fallback")).recordingConsentGranted)
        db.close()
    }
    @Test fun earlyParkDiscardsTripWithoutLosingLifecycleCommit() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),RecordingDatabase::class.java).build()
        val store = RoomTrackRecordingStore(db,RecordingCipher(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()))
        val session = DriveLifecycleSession(store,"short",RecordingInputSource.REPLAY,RegionCatalogState.Missing)
        session.process(0,TripInput.AccessChanged(true,true,0,base))
        val start = session.process(1,TripInput.Vehicle(TripVehicleSample(base,0,Gear.D,false,11.1),support))
        val trip = start.lifecycle.transitions.filterIsInstance<TripTransition.Started>().single().trip.id
        session.process(2,location(0))
        session.process(3,TripInput.Vehicle(TripVehicleSample(base+1000,1000,Gear.P,false,0.0),support))
        assertEquals(TripStatus.DISCARDED,store.load(trip)?.status)
        assertEquals(RecordingState.IDLE,store.lifecycle("short")?.recordingState)
        assertEquals(3L,store.lifecycle("short")?.lastInputSequence)
        db.close()
    }
    @Test fun parkedDwellContinuesAfterDiscardedTripWithoutRecordingParkedTrack() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),RecordingDatabase::class.java).build()
        val store = RoomTrackRecordingStore(db,RecordingCipher(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()))
        val ring = listOf(GeoPoint(35.9,126.9),GeoPoint(35.9,127.1),GeoPoint(36.1,127.1),GeoPoint(36.1,126.9),GeoPoint(35.9,126.9))
        val regions = listOf(RegionDefinition("DEMO_PARK","합성 정차 영역","DEMO",GeoPoint(36.0,127.0),RegionGeometry(listOf(RegionPolygon(ring)))))
        val catalog = RegionCatalogState.Ready(regions,com.pleos.explorer.domain.geo.IndexedRegionCatalog(regions))
        val session = DriveLifecycleSession(store,"park-dwell",RecordingInputSource.ANDROID_GPS,catalog)
        var sequence = 0L
        session.process(sequence++,TripInput.AccessChanged(true,true,0,base))
        session.process(sequence++,TripInput.Vehicle(TripVehicleSample(base,0,Gear.D,false,11.1),support))
        (0..10).forEach { session.process(sequence++,location(it)) }
        session.process(sequence++,TripInput.Vehicle(TripVehicleSample(base+11_000,11_000,Gear.P,false,0.0),support))
        assertTrue(store.regionBadges().isEmpty())
        var changed = false
        for (i in 11..200) {
            val sample = LocationSample(base+i*1000L,i*1000L,GeoPoint(36.001,127.0),0.0,null,5.0)
            val outcome = session.process(sequence++,TripInput.Location(LocationValidator().validate(sample,null),sample.elapsedMs,sample.timeMs))
            changed = changed || outcome.collectionChanged
            assertTrue(outcome.recordings.isEmpty())
        }
        assertTrue(changed)
        assertNull(store.lifecycle("park-dwell")?.tripId)
        assertTrue(store.listSnapshots().isEmpty())
        val badge = store.regionBadges().single()
        assertEquals(1,badge.visitCount)
        assertNull(badge.firstTripId)
        assertNull(store.pendingRegionRewards().single().tripId)
        val revision = store.regionVisits.load()?.revision
        session.process(sequence++,TripInput.AccessChanged(false,true,201_000,base+201_000))
        session.process(sequence,location(202))
        assertEquals(revision,store.regionVisits.load()?.revision)
        db.close()
    }
    @Test fun regionDwellSurvivesPauseResumeAndParkWhileTrackHasSeparateSegments() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),RecordingDatabase::class.java).build()
        val store = RoomTrackRecordingStore(db,RecordingCipher(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()))
        val ring = listOf(GeoPoint(35.9,126.9),GeoPoint(35.9,127.1),GeoPoint(36.1,127.1),GeoPoint(36.1,126.9),GeoPoint(35.9,126.9))
        val regions = listOf(RegionDefinition("DEMO_PAUSE","합성 영역","DEMO",GeoPoint(36.0,127.0),RegionGeometry(listOf(RegionPolygon(ring)))))
        val session = DriveLifecycleSession(store,"pause-dwell",RecordingInputSource.ANDROID_GPS,
            RegionCatalogState.Ready(regions,com.pleos.explorer.domain.geo.IndexedRegionCatalog(regions)))
        var seq=0L
        session.process(seq++,TripInput.AccessChanged(true,true,0,base))
        val started = session.process(seq++,TripInput.Vehicle(TripVehicleSample(base,0,Gear.D,false,11.1),support))
        val trip = started.lifecycle.transitions.filterIsInstance<TripTransition.Started>().single().trip.id
        (0..10).forEach { session.process(seq++,location(it)) }
        session.process(seq++,TripInput.Vehicle(TripVehicleSample(base+11_000,11_000,parkingBrakeEngaged=true)))
        val count = store.loadPoints(trip).size
        fun stopped(i: Int,latitude: Double): TripInput.Location {
            val sample = LocationSample(base+i*1000L,i*1000L,GeoPoint(latitude,127.0),0.0,null,5.0)
            return TripInput.Location(LocationValidator().validate(sample,null),sample.elapsedMs,sample.timeMs)
        }
        (11..40).forEach { session.process(seq++,stopped(it,36.001)) }
        assertEquals(RecordingState.PAUSED,store.lifecycle("pause-dwell")?.recordingState)
        assertEquals(count,store.loadPoints(trip).size)
        session.process(seq++,TripInput.Vehicle(TripVehicleSample(base+41_000,41_000,Gear.D,false,11.1),support))
        (41..60).forEach { i ->
            val sample = LocationSample(base+i*1000L,i*1000L,GeoPoint(36.001+(i-41)*0.0001,127.0),11.1,0.0,5.0)
            session.process(seq++,TripInput.Location(LocationValidator().validate(sample,null),sample.elapsedMs,sample.timeMs))
        }
        session.process(seq++,TripInput.Vehicle(TripVehicleSample(base+61_000,61_000,Gear.P,false,0.0),support))
        assertEquals(TripStatus.FINISHED,store.load(trip)?.status)
        assertEquals(2,store.load(trip)?.trip?.simplifiedPath?.size)
        (61..179).forEach { session.process(seq++,stopped(it,36.0029)) }
        assertTrue(store.regionBadges().isEmpty())
        val qualified = session.process(seq,stopped(180,36.0029))
        assertTrue(qualified.collectionChanged)
        assertEquals(trip,store.regionBadges().single().firstTripId)
        assertEquals(1,store.regionBadges().single().visitCount)
        db.close()
    }
}
