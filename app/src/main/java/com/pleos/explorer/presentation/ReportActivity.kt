package com.pleos.explorer.presentation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.pleos.explorer.R
import com.pleos.explorer.di.AppGraph
import com.pleos.explorer.data.repository.LegacyReportRepository
import com.pleos.explorer.service.RoadLogService
import com.pleos.explorer.platform.ux.CarUxRestrictionSource
import com.pleos.explorer.platform.ux.NonAutomotiveUxSource
import com.pleos.explorer.platform.ux.UxObservationSession

class ReportActivity : ComponentActivity() {
    private val graph by lazy { AppGraph.from(this) }
    private var pendingMode: String? = null
    private var uxSession: UxObservationSession? = null
    private val locationPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        val mode = pendingMode
        pendingMode = null
        if (mode != null && hasLocation() && requiredPermissions(mode).all(::isGranted)) launchDrive(mode)
        else Toast.makeText(this, R.string.location_required, Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(AppViewModel::class.java))
                @Suppress("UNCHECKED_CAST")
                return AppViewModel(graph.settingsRepository, graph.driveStatus,
                    LegacyReportRepository(this@ReportActivity,
                        clearMapCache = { com.pleos.explorer.platform.map.MapCacheCleaner(applicationContext).clear() }
                    ) { graph.trackRecordingStore },
                    graph.uxAccessGate, graph.mapPreferences) as T
            }
        }
        val model = ViewModelProvider(this, factory)[AppViewModel::class.java]
        setContent {
            ExplorerApp(model, onStartDrive = ::startDrive, onStop = {
                startService(Intent(this, RoadLogService::class.java).setAction(RoadLogService.ACTION_STOP))
            })
        }
    }

    override fun onStart() {
        super.onStart()
        val source = if (packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            CarUxRestrictionSource(this)
        } else NonAutomotiveUxSource()
        uxSession = UxObservationSession(graph.uxAccessGate, source).also { it.start() }
    }

    override fun onStop() {
        uxSession?.stop()
        uxSession = null
        super.onStop()
    }

    private fun startDrive(mode: String) {
        if (!graph.uxAccessGate.state.value.canShowDetails) return
        if (!graph.settingsRepository.settings.value.recordingConsentGranted) return
        if (graph.driveStatus.running || graph.driveStatus.finishing || graph.driveStatus.deleting) return
        if (mode == RoadLogService.MODE_VEHICLE &&
            !packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            Toast.makeText(this, R.string.vehicle_environment_required, Toast.LENGTH_LONG).show()
            return
        }
        val permissions = requiredPermissions(mode).filterNot(::isGranted).toMutableList()
        if (!hasLocation()) permissions += listOf(
            Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION,
        )
        if (permissions.isNotEmpty()) {
            pendingMode = mode
            locationPermission.launch(permissions.distinct().toTypedArray())
            return
        }
        launchDrive(mode)
    }

    private fun launchDrive(mode: String) {
        if (!graph.uxAccessGate.state.value.canShowDetails ||
            !graph.settingsRepository.settings.value.recordingConsentGranted ||
            graph.driveStatus.running || graph.driveStatus.finishing || graph.driveStatus.deleting) return
        try {
            startForegroundService(Intent(this, RoadLogService::class.java).putExtra(RoadLogService.EXTRA_MODE, mode))
        } catch (error: IllegalStateException) {
            Toast.makeText(this, R.string.service_start_failed, Toast.LENGTH_LONG).show()
        } catch (error: SecurityException) {
            Toast.makeText(this, R.string.location_required, Toast.LENGTH_LONG).show()
        }
    }

    private fun isGranted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun hasLocation() = isGranted(Manifest.permission.ACCESS_COARSE_LOCATION) ||
        isGranted(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun requiredPermissions(mode: String): List<String> {
        val permissions = mutableListOf<String>()
        if (mode == RoadLogService.MODE_GPS_DEBUG) permissions += Manifest.permission.ACCESS_FINE_LOCATION
        if (graph.settingsRepository.settings.value.ttsEnabled &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            permissions += "pleos.car.permission.TTS_SERVICE"
        }
        if (mode == RoadLogService.MODE_VEHICLE) permissions += listOf("android.car.permission.CAR_SPEED", "pleos.car.permission.CAR_DRIVE",
            "pleos.car.permission.CAR_LIGHTS", "pleos.car.permission.CAR_ENERGY",
            "pleos.car.permission.FUSED_LOCATION", "pleos.car.permission.NAVI_ROUTE")
        return permissions.filter { permission ->
            // OS 버전에 따라 일반 권한과 런타임 권한이 달라질 수 있다.
            runCatching {
                packageManager.getPermissionInfo(permission, 0).protection == PermissionInfo.PROTECTION_DANGEROUS
            }.getOrDefault(false)
        }
    }

}
