package com.pleos.explorer.platform.ux

import android.car.Car
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.os.Handler
import android.os.Looper

/** All reads and callbacks are serialized on the main looper; no polling. */
class CarUxRestrictionSource(context: Context) : UxRestrictionSource {
    private val context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var car: Car? = null
    private var manager: CarUxRestrictionsManager? = null
    private var changed: ((UxAccessState) -> Unit)? = null
    private var generation = 0L
    private var connectionGeneration = 0L
    private val timeout = Runnable { changed?.invoke(UxAccessState(UxAvailability.UNAVAILABLE)) }

    override fun start(onChanged: (UxAccessState) -> Unit) {
        stop()
        changed = onChanged
        val token = generation
        onChanged(UxAccessState(UxAvailability.CONNECTING))
        handler.postDelayed(timeout, 5_000)
        try {
            car = Car.createCar(context, handler, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connected, ready ->
                handler.post {
                    if (token != generation || changed == null) return@post
                    val connectionToken = ++connectionGeneration
                    handler.removeCallbacks(timeout)
                    unregister()
                    if (!ready) {
                        changed?.invoke(UxAccessState(UxAvailability.UNAVAILABLE))
                    } else try {
                        val ux = connected.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager
                            ?: error("UX restriction service unavailable")
                        manager = ux
                        ux.registerListener { restrictions ->
                            handler.post {
                                if (token == generation && connectionToken == connectionGeneration) publish(restrictions)
                            }
                        }
                        publish(ux.currentCarUxRestrictions)
                    } catch (_: Exception) {
                        connectionGeneration++
                        unregister()
                        changed?.invoke(UxAccessState(UxAvailability.UNAVAILABLE))
                    }
                }
            }
        } catch (_: Exception) {
            handler.removeCallbacks(timeout)
            changed?.invoke(UxAccessState(UxAvailability.UNAVAILABLE))
        }
    }

    private fun publish(value: CarUxRestrictions?) {
        val state = if (value == null) UxAccessState(UxAvailability.UNAVAILABLE) else UxAccessState(
            UxAvailability.AVAILABLE, value.isRequiresDistractionOptimization, value.activeRestrictions)
        changed?.invoke(state)
    }

    private fun unregister() {
        runCatching { manager?.unregisterListener() }
        manager = null
    }
    override fun stop() {
        generation++
        connectionGeneration++
        changed = null
        handler.removeCallbacks(timeout)
        unregister()
        runCatching { car?.disconnect() }
        car = null
    }
}
