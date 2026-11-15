package com.pleos.explorer.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.SystemClock
import android.Manifest
import androidx.core.content.ContextCompat
import com.pleos.explorer.BuildConfig
import com.pleos.explorer.R
import com.pleos.explorer.di.AppGraph
import com.pleos.explorer.platform.speech.GatedSpeaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import com.pleos.explorer.platform.location.AndroidGpsSubscription
import com.pleos.explorer.data.local.recording.RecordingInputSource
import com.pleos.explorer.domain.model.GeoPoint
import com.pleos.explorer.domain.model.LocationSample
import com.pleos.explorer.domain.model.TripStatus
import com.pleos.explorer.data.local.FileDexStore
import com.pleos.explorer.data.catalog.LandmarkLoader
import com.pleos.explorer.data.local.TripStore
import com.pleos.explorer.platform.speech.AndroidSpeaker
import com.pleos.explorer.platform.speech.Speaker
import com.pleos.explorer.platform.speech.PleosSpeaker
import com.pleos.explorer.platform.vehicle.DemoSignalSource
import com.pleos.explorer.platform.vehicle.PleosVehicleSource
import com.pleos.explorer.platform.vehicle.VehicleSignalSource
import com.pleos.explorer.domain.model.legacy.GearState
import com.pleos.explorer.domain.engine.legacy.GhostComparator
import com.pleos.explorer.domain.engine.legacy.RoadDex
import com.pleos.explorer.domain.engine.legacy.RouteMatcher
import com.pleos.explorer.domain.engine.legacy.SpeechScheduler
import com.pleos.explorer.domain.engine.legacy.TripRecorder
import com.pleos.explorer.domain.model.legacy.VehicleSignal
import com.pleos.explorer.domain.geo.legacy.RouteFingerprint
import com.pleos.explorer.presentation.ReportActivity

/**
 * 주행 동안 신호를 받아 기록·도감·고스트 중계를 돌리는 서비스.
 * 흐름: 신호 -> (도감 판정, 주행 기록, 말하기 타이밍) -> TTS
 */
class RoadLogService : Service() {

    private val graph by lazy { AppGraph.from(this) }
    private val driveStatus get() = graph.driveStatus
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var speaker: Speaker
    private lateinit var scheduler: SpeechScheduler
    private lateinit var dex: RoadDex
    private lateinit var tripStore: TripStore
    private var source: VehicleSignalSource? = null
    private var recorder: TripRecorder? = null
    private var ghost: GhostComparator? = null
    private var ghostChecked = false
    private var departure = DepartureGate()
    private var replayActive = false
    private var replayStopRequested = false
    private var locationActive = false
    private var locationStopRequested = false
    private var gpsSubscription: AndroidGpsSubscription? = null
    private var locationInputs: Channel<LocationSample>? = null

    override fun onCreate() {
        super.onCreate()
        if (driveStatus.deleting || !graph.settingsRepository.settings.value.recordingConsentGranted || !hasLocationPermission()) {
            stopSelf()
            return
        }
        startForeground(1, notification())
        speaker = GatedSpeaker(
            allowed = {
                val settings = graph.settingsRepository.settings.value
                graph.speechGate.allow(SystemClock.elapsedRealtime(), settings.recordingConsentGranted,
                    settings.ttsEnabled, driveStatus.running && driveStatus.hasDeparted)
            },
            createSpeaker = { createPlatformSpeaker() },
        )
        serviceScope.launch {
            graph.settingsRepository.settings.collect { settings ->
                if (!settings.ttsEnabled || !settings.recordingConsentGranted) {
                    speaker.shutdown()
                    scheduler = SpeechScheduler(speaker::speak)
                }
                if (!settings.recordingConsentGranted && recorder != null) finishTrip()
                if (!settings.recordingConsentGranted) replayStopRequested = true
                if (!settings.recordingConsentGranted) stopLocationInput()
            }
        }
        scheduler = SpeechScheduler(speaker::speak)
        dex = RoadDex(LandmarkLoader.load(this), FileDexStore(this))
        tripStore = TripStore(this)
    }

    private fun createPlatformSpeaker(): Speaker =
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            PleosSpeaker(this) { message ->
                driveStatus.updateChannel("음성", message)
                notifyReport()
            }
        } else AndroidSpeaker(this)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (driveStatus.deleting || !graph.settingsRepository.settings.value.recordingConsentGranted || !hasLocationPermission()) {
            driveStatus.message = "위치 권한과 기록 저장 동의가 필요해요"
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP) {
            if (locationActive) { stopLocationInput(); return START_NOT_STICKY }
            if (replayActive) { replayStopRequested = true; return START_NOT_STICKY }
            if (recorder != null) finishTrip() else stopSelf()
            return START_NOT_STICKY
        }
        if (driveStatus.running || driveStatus.monitoring || driveStatus.finishing) return START_NOT_STICKY
        val mode = intent?.getStringExtra(EXTRA_MODE) ?: MODE_VEHICLE
        if (mode !in setOf(MODE_VEHICLE, MODE_DEMO_ROUGH, MODE_DEMO_SMOOTH, MODE_TRACK_REPLAY, MODE_GPS_DEBUG, MODE_LIFECYCLE_REPLAY,MODE_EXPLORATION) ||
            (!BuildConfig.DEBUG && mode !in setOf(MODE_VEHICLE,MODE_EXPLORATION))) {
            stopSelf()
            return START_NOT_STICKY
        }
        driveStatus.running = mode != MODE_VEHICLE
        driveStatus.hasDeparted = false
        driveStatus.mode = mode
        driveStatus.clearChannels()
        driveStatus.clearTrack()
        if (mode == MODE_VEHICLE) {
            if (!packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
                driveStatus.running = false
                driveStatus.message = "차량 환경에서 사용할 수 있어요"
                stopSelf()
            } else startVehicleRecording()
            return START_NOT_STICKY
        }
        if (mode == MODE_LIFECYCLE_REPLAY) {
            startLifecycleReplay()
            return START_NOT_STICKY
        }
        if (mode == MODE_GPS_DEBUG || mode==MODE_EXPLORATION) {
            startGpsRecording()
            return START_NOT_STICKY
        }
        if (mode == MODE_TRACK_REPLAY) {
            startTrackReplay()
            return START_NOT_STICKY
        }
        driveStatus.message = if (mode == MODE_VEHICLE) "차량 연결 중 · 출발 신호를 기다려요" else "데모 주행 중"
        departure = DepartureGate()
        scheduler = SpeechScheduler(speaker::speak)
        startTrip()
        source = when (mode) {
            MODE_DEMO_ROUGH -> DemoSignalSource(rough = true)
            MODE_DEMO_SMOOTH -> DemoSignalSource(rough = false)
            else -> PleosVehicleSource(this, onStatus = { channel, message ->
                driveStatus.updateChannel(channel, message)
                notifyReport()
            })
        }
        source?.start(::onSignal)
        notifyReport()
        return START_NOT_STICKY
    }

    private fun startTrip() {
        ghost = null
        ghostChecked = false
        recorder = TripRecorder(id = java.util.UUID.randomUUID().toString())
    }

    private sealed interface VehicleDriveEvent {
        data class Location(val sample: LocationSample) : VehicleDriveEvent
        data class Vehicle(val sample: com.pleos.explorer.domain.model.TripVehicleSample) : VehicleDriveEvent
    }

    private fun startVehicleRecording() {
        locationActive = true
        driveStatus.monitoring = true
        startForeground(1,notification())
        locationStopRequested = false
        driveStatus.message = "차량 연결 중 · 기어와 정확한 위치 신호를 기다려요"
        serviceScope.launch {
            val input = Channel<VehicleDriveEvent>(64)
            val gps = AndroidGpsSubscription(this@RoadLogService)
            gpsSubscription = gps
            var inputFailure: Exception? = null
            fun offer(event: VehicleDriveEvent) {
                if (input.trySend(event).isFailure) {
                    inputFailure = IllegalStateException("Vehicle input queue full")
                    input.close(inputFailure)
                    gps.stop()
                }
            }
            try {
                check(hasPreciseLocationPermission())
                val session = withContext(Dispatchers.IO) {
                    DriveLifecycleSession(graph.trackRecordingStore, java.util.UUID.randomUUID().toString(),
                        RecordingInputSource.ANDROID_GPS, graph.regionCatalog, graph.trackRecordingStore.gpsHistory())
                }
                var sequence = 0L
                var previous: com.pleos.explorer.domain.model.ValidatedLocation? = null
                val speechPolicy = com.pleos.explorer.platform.speech.RewardSpeechPolicy()
                var vehicleAllowsSpeech = false
                var gearObservedAt: Long? = null
                fun drivingForSpeech(now: Long) = vehicleAllowsSpeech && gearObservedAt?.let { now - it in 0..3_000 } == true
                val validator = com.pleos.explorer.domain.engine.LocationValidator()
                session.process(sequence++, com.pleos.explorer.domain.model.TripInput.AccessChanged(true,true,
                    SystemClock.elapsedRealtime(),System.currentTimeMillis()))
                gps.start(onSample = { offer(VehicleDriveEvent.Location(it)) }, onFailure = {
                    inputFailure = it; input.close(it); gps.stop()
                })
                source = PleosVehicleSource(this@RoadLogService,
                    onStatus = { channel, message -> driveStatus.updateChannel(channel,message) },
                    onTripVehicle = { sample ->
                        if (sample.parkingBrakeEngaged == true) { vehicleAllowsSpeech = false; speaker.shutdown() }
                        sample.gear?.let { gear ->
                            vehicleAllowsSpeech = gear == com.pleos.explorer.domain.model.Gear.D
                            gearObservedAt = sample.elapsedMs
                            if (!vehicleAllowsSpeech) speaker.shutdown()
                        }
                        offer(VehicleDriveEvent.Vehicle(sample))
                    }).also { it.start(speechPolicy::observe) }
                while (!locationStopRequested && graph.settingsRepository.settings.value.recordingConsentGranted && hasPreciseLocationPermission()) {
                    inputFailure?.let { throw it }
                    val event = withTimeoutOrNull(1000) { input.receiveCatching() }
                    if (event != null && event.isClosed) throw (event.exceptionOrNull() ?: IllegalStateException("Vehicle input closed"))
                    if (locationStopRequested || !graph.settingsRepository.settings.value.recordingConsentGranted || !hasPreciseLocationPermission()) break
                    val driveInput = when (val value = event?.getOrNull()) {
                        is VehicleDriveEvent.Location -> com.pleos.explorer.domain.model.TripInput.Location(
                            validator.validate(value.sample,previous),value.sample.elapsedMs,value.sample.timeMs)
                        is VehicleDriveEvent.Vehicle -> com.pleos.explorer.domain.model.TripInput.Vehicle(value.sample)
                        null -> com.pleos.explorer.domain.model.TripInput.Tick(SystemClock.elapsedRealtime(),System.currentTimeMillis())
                    }
                    val outcome = session.process(sequence++,driveInput)
                    if (driveInput is com.pleos.explorer.domain.model.TripInput.Location)
                        previous = (driveInput.validation as? com.pleos.explorer.domain.model.LocationValidation.Accepted)?.location ?: previous
                    if (outcome.lifecycle.transitions.any { it is com.pleos.explorer.domain.model.TripTransition.Started }) driveStatus.clearTrack()
                    outcome.recordings.forEach(driveStatus::updateTrack)
                    driveStatus.hasDeparted = outcome.lifecycle.snapshot.tripId != null
                    driveStatus.running = outcome.lifecycle.snapshot.recordingState == com.pleos.explorer.domain.model.RecordingState.RECORDING
                    if (outcome.lifecycle.transitions.isNotEmpty()) startForeground(1,notification())
                    if (outcome.collectionChanged) driveStatus.recordsChanged()
                    val settings = graph.settingsRepository.settings.value
                    val elapsed = SystemClock.elapsedRealtime()
                    if (speechPolicy.canAnnounce(elapsed,settings.ttsEnabled,settings.recordingConsentGranted,
                        outcome.lifecycle.snapshot.recordingState == com.pleos.explorer.domain.model.RecordingState.RECORDING && drivingForSpeech(elapsed))) try {
                        val tripId = requireNotNull(outcome.lifecycle.snapshot.tripId)
                        val reward = graph.trackRecordingStore.nextRewardForSpeech(tripId)
                        val landmark = if (reward == null) graph.trackRecordingStore.nextLandmarkForSpeech(tripId) else null
                        val claimed = if (reward != null) graph.trackRecordingStore.claimRewardSpeech(reward.id)
                            else landmark?.let { graph.trackRecordingStore.claimLandmarkSpeech(it.key) } ?: false
                        if (claimed) {
                            val name = (graph.regionCatalog as? com.pleos.explorer.data.catalog.RegionCatalogState.Ready)?.regions
                                ?.firstOrNull { it.code == reward?.subjectId?.removePrefix("REGION:") }?.name
                            val latest = graph.settingsRepository.settings.value
                            val at = SystemClock.elapsedRealtime()
                            if (speechPolicy.canAnnounce(at,latest.ttsEnabled,latest.recordingConsentGranted,
                                outcome.lifecycle.snapshot.recordingState == com.pleos.explorer.domain.model.RecordingState.RECORDING && drivingForSpeech(at))) {
                                speechPolicy.attempted(at)
                                speaker.speak(if (landmark != null) "새로운 명소를 수집했어요. 정차 후 도감을 확인해 주세요."
                                    else if (reward?.kind == com.pleos.explorer.domain.model.RewardKind.BADGE)
                                    "${name ?: "지역"} 배지를 받았어요." else "새로운 탐험 칭호를 받았어요.")
                            }
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { driveStatus.updateChannel("음성","음성 안내를 제공하지 못했어요 · 보상은 정차 후 확인할 수 있어요") }
                    if (outcome.lifecycle.transitions.any { it is com.pleos.explorer.domain.model.TripTransition.Ended }) {
                        driveStatus.message = "주행을 마쳤어요 · 정차한 지역의 방문을 확인하고 있어요"
                    }
                    if (driveStatus.hasDeparted) driveStatus.message = if (driveStatus.running)
                        "주행 기록 중 · 상세 정보는 정차 후 확인해 주세요" else "주행 기록 일시중지 · 지역 방문은 계속 확인하고 있어요"
                }
                gps.stop()
                source?.stop(); source = null
                driveStatus.finishing = true
                // Preserve actual access values; a manual stop remains recoverable rather than inventing P or revoked consent.
                session.process(sequence,com.pleos.explorer.domain.model.TripInput.AccessChanged(
                    graph.settingsRepository.settings.value.recordingConsentGranted,hasPreciseLocationPermission(),
                    SystemClock.elapsedRealtime(),System.currentTimeMillis()))
                if (locationStopRequested && driveStatus.hasDeparted && graph.settingsRepository.settings.value.recordingConsentGranted)
                    driveStatus.message = "주행 수신을 중단했어요 · 주행 기록에서 복구 종료해 주세요"
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { driveStatus.message = "차량 기록을 계속할 수 없어요 · 정확한 위치 권한과 차량 신호를 확인해 주세요" }
            finally {
                input.cancel()
                gps.stop()
                source?.stop(); source = null
                gpsSubscription = null
                locationActive = false
                driveStatus.monitoring = false
                driveStatus.running = false
                driveStatus.finishing = false
                driveStatus.hasDeparted = false
                notifyReport()
                stopSelf()
            }
        }
    }

    private fun startLifecycleReplay() {
        replayActive = true
        replayStopRequested = false
        driveStatus.message = "자동 시작·종료 판정 재생 중 · 합성 입력입니다"
        serviceScope.launch {
            try {
                val session = DriveLifecycleSession(graph.trackRecordingStore, java.util.UUID.randomUUID().toString(),
                    RecordingInputSource.REPLAY, com.pleos.explorer.data.catalog.RegionCatalogState.Missing)
                val base = System.currentTimeMillis()
                var sequence = 0L
                var lastOffset = 0L
                session.process(sequence++, com.pleos.explorer.domain.model.TripInput.AccessChanged(true,true,0,base))
                session.process(sequence++, com.pleos.explorer.domain.model.TripInput.Vehicle(
                    com.pleos.explorer.domain.model.TripVehicleSample(base,0,com.pleos.explorer.domain.model.Gear.D,false,11.1),
                    com.pleos.explorer.domain.model.VehicleCapabilities(
                        com.pleos.explorer.domain.model.SignalSupport.SUPPORTED,
                        com.pleos.explorer.domain.model.SignalSupport.SUPPORTED,
                        com.pleos.explorer.domain.model.SignalSupport.SUPPORTED)))
                val validator = com.pleos.explorer.domain.engine.LocationValidator()
                var previous: com.pleos.explorer.domain.model.ValidatedLocation? = null
                for (index in 0..30) {
                    if (replayStopRequested || !graph.settingsRepository.settings.value.recordingConsentGranted || !hasLocationPermission()) break
                    lastOffset = index * 1000L
                    val sample = LocationSample(base + lastOffset,lastOffset,GeoPoint(36.0 + index * 0.0001,127.0),11.1,0.0,5.0)
                    val validation = validator.validate(sample,previous)
                    previous = (validation as? com.pleos.explorer.domain.model.LocationValidation.Accepted)?.location ?: previous
                    val outcome = session.process(sequence++, com.pleos.explorer.domain.model.TripInput.Location(validation,lastOffset,sample.timeMs))
                    outcome.recordings.forEach(driveStatus::updateTrack)
                    delay(200)
                }
                driveStatus.finishing = true
                val access = graph.settingsRepository.settings.value.recordingConsentGranted && hasLocationPermission()
                val outcome = if (access) session.process(sequence,com.pleos.explorer.domain.model.TripInput.Vehicle(
                    com.pleos.explorer.domain.model.TripVehicleSample(base+lastOffset+1000,lastOffset+1000,com.pleos.explorer.domain.model.Gear.P,false,0.0)))
                else session.process(sequence,com.pleos.explorer.domain.model.TripInput.AccessChanged(false,hasLocationPermission(),lastOffset+1000,base+lastOffset+1000))
                driveStatus.message = if (outcome.recordings.lastOrNull()?.snapshot?.status == TripStatus.DISCARDED)
                    "판정 재생 종료 · 200m 미만 기록은 보관하지 않아요" else "D 시작·P 종료 판정과 경로 저장을 확인했어요 · 합성 입력입니다"
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { driveStatus.message = "주행 판정 재생을 완료하지 못했어요 · 기록을 확인해 주세요" }
            finally {
                replayActive = false
                driveStatus.running = false
                driveStatus.finishing = false
                notifyReport()
                stopSelf()
            }
        }
    }

    /** Synthetic observations have explicit accuracy; no Pleos confidence conversion is used. */
    private fun startTrackReplay() {
        replayActive = true
        replayStopRequested = false
        driveStatus.message = "검증 경로 재생 중 · 실제 차량 입력이 아닙니다"
        serviceScope.launch {
            try {
                val useCase = withContext(Dispatchers.IO) { graph.newTrackRecordingUseCase(RecordingInputSource.REPLAY) }
                if (!graph.settingsRepository.settings.value.recordingConsentGranted || !hasLocationPermission()) {
                    driveStatus.message = "위치 권한과 기록 저장 동의가 필요해요"
                    return@launch
                }
                val tripId = java.util.UUID.randomUUID().toString()
                val sessionId = java.util.UUID.randomUUID().toString()
                val startedAtMs = System.currentTimeMillis()
                useCase.start(tripId, startedAtMs, sessionId)
                graph.trackRecordingStore.setInputSource(tripId, RecordingInputSource.REPLAY)
                val landmark = withContext(Dispatchers.IO) { LandmarkLoader.load(this@RoadLogService).first() }
                for (index in 0..80) {
                    if (replayStopRequested || !graph.settingsRepository.settings.value.recordingConsentGranted ||
                        !hasLocationPermission()) break
                    val update = useCase.process(tripId, sessionId, index.toLong(), LocationSample(
                        timeMs = startedAtMs + index * 1_000L, elapsedMs = index * 1_000L,
                        point = GeoPoint(landmark.lat - 0.004 + index * 0.0001, landmark.lon), speedMps = 11.1,
                        bearingDeg = 0.0, accuracyM = 5.0,
                    ))
                    driveStatus.updateTrack(update)
                    delay(150)
                }
                driveStatus.finishing = true
                val result = useCase.finish(tripId, System.currentTimeMillis())
                driveStatus.message = if (result.snapshot.status == TripStatus.DISCARDED)
                    "재생 종료 · 200m 미만 기록은 보관하지 않아요"
                else "재생 경로를 저장했어요 · 주행 기록에서 확인해 주세요"
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                driveStatus.message = "경로 저장을 완료하지 못했어요 · 기록을 확인해 주세요"
            } finally {
                replayActive = false
                driveStatus.running = false
                driveStatus.finishing = false
                notifyReport()
                stopSelf()
            }
        }
    }

    private var segmentTime = 0L

    private fun stopLocationInput() {
        locationStopRequested = true
        gpsSubscription?.stop()
        locationInputs?.close()
    }

    /** Explicit manual GPS session; does not pretend that GPS proves a vehicle's gear state. */
    private fun startGpsRecording() {
        locationActive = true
        locationStopRequested = false
        driveStatus.message = "위치 수신을 기다리고 있어요"
        serviceScope.launch {
            val input = Channel<LocationSample>(64)
            locationInputs = input
            val gps = AndroidGpsSubscription(this@RoadLogService)
            gpsSubscription = gps
            var tripId: String? = null
            try {
                val engine = withContext(Dispatchers.IO) { graph.newGpsTrackRecordingUseCase() }
                val visits = withContext(Dispatchers.IO) { RegionVisitSession(graph.trackRecordingStore, graph.regionCatalog) }
                val ghosts = GhostRecordingSession(graph.trackRecordingStore, graph.trackRecordingStore.gpsHistory())
                val sessionId = java.util.UUID.randomUUID().toString()
                var sequence = 0L
                var inputFailure: Exception? = null
                if (!graph.settingsRepository.settings.value.recordingConsentGranted || !hasPreciseLocationPermission()) {
                    driveStatus.message = "위치 권한과 기록 저장 동의가 필요해요"
                    return@launch
                }
                gps.start(onSample = { sample ->
                    if (input.trySend(sample).isFailure) {
                        inputFailure = IllegalStateException("Location input queue full")
                        input.close(inputFailure)
                        gps.stop()
                    }
                }, onFailure = { error -> inputFailure = error; input.close(error); gps.stop() })
                while (!locationStopRequested) {
                    inputFailure?.let { throw it }
                    if (!graph.settingsRepository.settings.value.recordingConsentGranted || !hasPreciseLocationPermission()) break
                    val result = withTimeoutOrNull(250) { input.receiveCatching() } ?: continue
                    val sample = result.getOrNull() ?: if (result.exceptionOrNull() != null)
                        throw requireNotNull(result.exceptionOrNull()) else break
                    if (locationStopRequested || !graph.settingsRepository.settings.value.recordingConsentGranted ||
                        !hasPreciseLocationPermission()) break
                    val id = tripId ?: java.util.UUID.randomUUID().toString().also {
                        graph.trackRecordingStore.atomic {
                            engine.start(it, sample.timeMs, sessionId)
                            graph.trackRecordingStore.setInputSource(it, RecordingInputSource.ANDROID_GPS)
                            visits.start(sessionId, it)
                            ghosts.start(it, sessionId)
                        }
                        tripId = it
                    }
                    if (locationStopRequested || !graph.settingsRepository.settings.value.recordingConsentGranted ||
                        !hasPreciseLocationPermission()) break
                    val update = graph.trackRecordingStore.atomic {
                        engine.process(id, sessionId, sequence, sample).also {
                            visits.process(it)
                            ghosts.process(it)
                        }
                    }
                    sequence++
                    driveStatus.updateTrack(update)
                    driveStatus.message = if (update.validation is com.pleos.explorer.domain.model.LocationValidation.Accepted)
                        "위치 기록 중 · 상세 정보는 정차 후 확인해 주세요" else "정확한 위치 수신을 기다리고 있어요"
                }
                gps.stop()
                driveStatus.finishing = true
                val completed = tripId?.let { id -> graph.trackRecordingStore.atomic {
                    engine.finish(id, System.currentTimeMillis()).also {
                        graph.trackRecordingStore.recordGpsTripAchievement(it)
                        if (it.snapshot.status != TripStatus.DISCARDED) ghosts.process(it)
                        visits.finish(it.snapshot.status == TripStatus.DISCARDED, it.snapshot.trip.id)
                    }
                } }
                driveStatus.message = when {
                    completed == null -> "위치 수신 없이 종료했어요"
                    completed.snapshot.status == TripStatus.DISCARDED -> "200m 미만 기록은 보관하지 않아요"
                    else -> "위치 경로를 저장했어요 · 주행 기록에서 확인해 주세요"
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { driveStatus.message = "위치 기록을 완료하지 못했어요 · 위치 설정과 권한을 확인해 주세요" }
            finally {
                gps.stop()
                input.cancel()
                locationInputs = null
                gpsSubscription = null
                locationActive = false
                driveStatus.running = false
                driveStatus.finishing = false
                notifyReport()
                stopSelf()
            }
        }
    }

    private fun onSignal(signal: VehicleSignal) {
        if (recorder == null) return
        if (driveStatus.deleting || !graph.settingsRepository.settings.value.recordingConsentGranted || !hasLocationPermission()) {
            finishTrip()
            return
        }
        if (signal is VehicleSignal.Gear) {
            if (departure.onGear(signal.gear)) {
                segmentTime = signal.timeMs
                scheduler.onSignal(signal)
                finishTrip()
                return
            }
            driveStatus.hasDeparted = departure.hasDeparted
            if (departure.hasDeparted) driveStatus.message = "주행 기록 중 · 도착 리포트는 정차 후 표시해요"
            notifyReport()
        }
        if (driveStatus.mode == MODE_VEHICLE && !departure.hasDeparted) return
        segmentTime = signal.timeMs
        scheduler.onSignal(signal)
        recorder?.onSignal(signal)

        if (signal is VehicleSignal.Location) {
            dex.onLocation(signal.lat, signal.lon, signal.timeMs)?.let {
                scheduler.enqueue(RoadDex.announce(it), signal.timeMs, SpeechScheduler.Priority.HIGH)
                notifyReport()
            }
            findGhostOnce()
        }

    }

    /** 경로가 어느 정도 쌓이면 지난 주행 중 같은 길을 찾아 고스트로 쓴다. */
    private fun findGhostOnce() {
        val rec = recorder ?: return
        if (ghostChecked || rec.fingerprintSoFar.cells.size < 5) return
        ghostChecked = true
        val history = tripStore.all().asReversed().map { it to RouteFingerprint.of(it.route) }
        val prefix = history.map { (trip, fp) -> trip to RouteFingerprint.of(fp.cells.take(rec.fingerprintSoFar.cells.size)) }
        RouteMatcher.bestMatch(rec.fingerprintSoFar, prefix)?.let {
            ghost = GhostComparator(it)
            scheduler.enqueue("지난번의 나와 같은 길이에요. 오늘도 달려 볼까요?", segmentTime)
        }
    }

    private fun finishTrip() {
        val rec = recorder ?: return
        val trip = rec.finish()
        recorder = null
        source?.stop()
        source = null
        if (trip.route.isNotEmpty()) tripStore.add(trip)
        driveStatus.running = false
        driveStatus.finishing = true
        driveStatus.message = if (trip.route.isEmpty()) "위치 기록 없이 종료했어요" else "주행을 마쳤어요 · 도착 리포트를 확인해 주세요"
        speaker.shutdown()
        notifyReport()
        speaker.finish { stopSelf() }
    }

    private fun notifyReport() {
        sendBroadcast(Intent(ACTION_TRIP_UPDATED).setPackage(packageName))
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasPreciseLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    override fun onDestroy() {
        driveStatus.monitoring = false
        driveStatus.clearTrack()
        gpsSubscription?.stop()
        serviceScope.cancel()
        source?.stop()
        if (::speaker.isInitialized) speaker.shutdown()
        driveStatus.running = false
        driveStatus.finishing = false
        driveStatus.hasDeparted = false
        if (recorder != null) driveStatus.message = "주행이 중단됐어요 · 완료된 기록만 보관합니다"
        notifyReport()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(): Notification {
        val channelId = "roadlog"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(channelId, "RoadLog", NotificationManager.IMPORTANCE_LOW))
        val builder = Notification.Builder(this, channelId)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(if (driveStatus.monitoring && !driveStatus.running) "차량 신호와 지역 방문을 확인하고 있어요" else getString(R.string.notification_running))
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, ReportActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        if (driveStatus.monitoring && !driveStatus.running) builder.addAction(
            android.R.drawable.ic_menu_close_clear_cancel,"기록 대기 종료",
            PendingIntent.getService(this,1,Intent(this,RoadLogService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        return builder.build()
    }

    companion object {
        const val ACTION_TRIP_UPDATED = "com.roadlog.app.TRIP_UPDATED"
        const val ACTION_STOP = "com.roadlog.app.STOP"
        const val EXTRA_MODE = "mode"
        const val MODE_VEHICLE = "vehicle"
        const val MODE_DEMO_ROUGH = "demo_rough"
        const val MODE_DEMO_SMOOTH = "demo_smooth"
        const val MODE_TRACK_REPLAY = "track_replay"
        const val MODE_GPS_DEBUG = "gps_debug"
        const val MODE_EXPLORATION = "exploration"
        const val MODE_LIFECYCLE_REPLAY = "lifecycle_replay"
    }
}
