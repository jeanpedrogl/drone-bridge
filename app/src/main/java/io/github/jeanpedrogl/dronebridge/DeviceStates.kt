package io.github.jeanpedrogl.dronebridge

import dji.common.battery.BatteryState
import dji.common.camera.ExposureSettings
import dji.common.camera.StorageState
import dji.common.camera.SystemState
import dji.common.flightcontroller.FlightControllerState
import dji.common.gimbal.GimbalState
import dji.common.remotecontroller.HardwareState
import dji.sdk.products.Aircraft
import dji.sdk.sdkmanager.DJISDKManager
import org.json.JSONObject

/**
 * Latest state reported by each component of the aircraft, serialized for the computer by
 * [toJson] — on request only (`state.get`, `state.stream_start`; see [StateStreamer]), nothing is
 * sent on its own. Callbacks arrive on SDK threads; fields are volatile and
 * only ever replaced as a whole, so [toJson] always sees a consistent snapshot per component.
 *
 * The flight controller and battery callbacks are owned by `DroneController` (it also needs them
 * for the on-screen telemetry) and feed [flight]/[battery]; the rest is hooked here.
 */
class DeviceStates {
    @Volatile var flight: FlightControllerState? = null
    @Volatile var battery: BatteryState? = null
    @Volatile private var gimbal: GimbalState? = null
    @Volatile private var camera: SystemState? = null
    @Volatile private var exposure: ExposureSettings? = null
    @Volatile private var storage: StorageState? = null
    @Volatile private var remote: HardwareState? = null

    /** Hooks gimbal, camera and remote controller. Safe to call repeatedly as components connect. */
    fun attach(aircraft: Aircraft) {
        aircraft.gimbal?.setStateCallback { gimbal = it }
        aircraft.camera?.let { cam ->
            cam.setSystemStateCallback { camera = it }
            cam.setExposureSettingsCallback { exposure = it }
            cam.setStorageStateCallBack { storage = it }
        }
        aircraft.remoteController?.setHardwareStateCallback { remote = it }
    }

    fun clear() {
        flight = null
        battery = null
        gimbal = null
        camera = null
        exposure = null
        storage = null
        remote = null
    }

    /**
     * Snapshot of the requested [sections] (all of [SECTIONS] when null). A section whose
     * component has not reported yet (or is not present on this aircraft) is JSON null.
     */
    fun toJson(sections: Set<String>? = null): JSONObject {
        fun wanted(name: String) = sections == null || name in sections
        val json = JSONObject()
        json.put("stamp_ms", System.currentTimeMillis())
        if (wanted("product")) {
            json.put("product", JSONObject().put("model", DJISDKManager.getInstance().product?.model?.name ?: JSONObject.NULL))
        }
        if (wanted("flight")) json.put("flight", flight?.let(::flightJson) ?: JSONObject.NULL)
        if (wanted("gimbal")) json.put("gimbal", gimbal?.let(::gimbalJson) ?: JSONObject.NULL)
        if (wanted("camera")) json.put("camera", camera?.let { cameraJson(it, exposure, storage) } ?: JSONObject.NULL)
        if (wanted("battery")) json.put("battery", battery?.let(::batteryJson) ?: JSONObject.NULL)
        if (wanted("remote")) json.put("remote", remote?.let(::remoteJson) ?: JSONObject.NULL)
        return json
    }

    companion object {
        val SECTIONS = listOf("product", "flight", "gimbal", "camera", "battery", "remote")
    }

    private fun flightJson(s: FlightControllerState) = JSONObject().apply {
        val loc = s.aircraftLocation
        put("location", JSONObject().apply {
            num("latitude", loc?.latitude)
            num("longitude", loc?.longitude)
            num("altitude_m", loc?.altitude)
        })
        val att = s.attitude
        put("attitude_deg", JSONObject().apply {
            num("pitch", att?.pitch)
            num("roll", att?.roll)
            num("yaw", att?.yaw)
        })
        put("velocity_ms", JSONObject().apply {
            num("x", s.velocityX)
            num("y", s.velocityY)
            num("z", s.velocityZ)
        })
        put("heading_deg", s.aircraftHeadDirection)
        put("satellites", s.satelliteCount)
        put("gps_signal", s.gpsSignalLevel?.name ?: JSONObject.NULL)
        put("flight_mode", s.flightMode?.name ?: JSONObject.NULL)
        put("flight_mode_text", s.flightModeString ?: JSONObject.NULL)
        put("is_flying", s.isFlying)
        put("motors_on", s.areMotorsOn())
        put("flight_time_s", s.flightTimeInSeconds)
        num("ultrasonic_height_m", s.ultrasonicHeightInMeters)
        put("ultrasonic_in_use", s.isUltrasonicBeingUsed)
        put("vision_positioning_in_use", s.isVisionPositioningSensorBeingUsed)
        put("imu_preheating", s.isIMUPreheating)
        put("home", JSONObject().apply {
            put("is_set", s.isHomeLocationSet)
            num("latitude", s.homeLocation?.latitude)
            num("longitude", s.homeLocation?.longitude)
            put("return_height_m", s.goHomeHeight)
        })
        put("going_home", s.isGoingHome)
        put("go_home_state", s.goHomeExecutionState?.name ?: JSONObject.NULL)
        put("landing_confirmation_needed", s.isLandingConfirmationNeeded)
        put("low_battery_warning", s.isLowerThanBatteryWarningThreshold)
        put("serious_low_battery_warning", s.isLowerThanSeriousBatteryWarningThreshold)
        put("wind_warning", s.flightWindWarning?.name ?: JSONObject.NULL)
        put("reached_max_height", s.hasReachedMaxFlightHeight())
        put("reached_max_radius", s.hasReachedMaxFlightRadius())
    }

    private fun gimbalJson(s: GimbalState) = JSONObject().apply {
        val att = s.attitudeInDegrees
        put("attitude_deg", JSONObject().apply {
            num("pitch", att?.pitch)
            num("roll", att?.roll)
            num("yaw", att?.yaw)
        })
        put("mode", s.mode?.name ?: JSONObject.NULL)
        put("pitch_at_stop", s.isPitchAtStop)
        put("roll_at_stop", s.isRollAtStop)
        put("yaw_at_stop", s.isYawAtStop)
        num("yaw_relative_to_aircraft_deg", s.yawRelativeToAircraftHeading)
    }

    private fun cameraJson(s: SystemState, e: ExposureSettings?, st: StorageState?) = JSONObject().apply {
        put("mode", s.mode?.name ?: JSONObject.NULL)
        put("is_recording", s.isRecording)
        put("recording_time_s", s.currentVideoRecordingTimeInSeconds)
        put("shooting_single_photo", s.isShootingSinglePhoto)
        put("shooting_burst_photo", s.isShootingBurstPhoto)
        put("shooting_interval_photo", s.isShootingIntervalPhoto)
        put("storing_photo", s.isStoringPhoto)
        put("overheating", s.isOverheating)
        put("has_error", s.hasError())
        put("exposure", e?.let {
            JSONObject().apply {
                put("iso", it.iso)
                put("shutter_speed", it.shutterSpeed?.name ?: JSONObject.NULL)
                put("aperture", it.aperture?.name ?: JSONObject.NULL)
                put("compensation", it.exposureCompensation?.name ?: JSONObject.NULL)
            }
        } ?: JSONObject.NULL)
        put("storage", st?.let {
            JSONObject().apply {
                put("location", it.storageLocation?.name ?: JSONObject.NULL)
                put("inserted", it.isInserted)
                put("full", it.isFull)
                put("formatting", it.isFormatting)
                put("has_error", it.hasError())
                put("total_mb", it.totalSpaceInMB)
                put("remaining_mb", it.remainingSpaceInMB)
                put("photos_left", it.availableCaptureCount)
                put("recording_s_left", it.availableRecordingTimeInSeconds)
            }
        } ?: JSONObject.NULL)
    }

    private fun batteryJson(s: BatteryState) = JSONObject().apply {
        put("percent", s.chargeRemainingInPercent)
        put("voltage_mv", s.voltage)
        put("current_ma", s.current)
        num("temperature_c", s.temperature)
        put("charge_remaining_mah", s.chargeRemaining)
        put("full_charge_capacity_mah", s.fullChargeCapacity)
        put("discharges", s.numberOfDischarges)
        put("charging", s.isBeingCharged)
    }

    private fun remoteJson(s: HardwareState) = JSONObject().apply {
        put("left_stick", JSONObject().put("h", s.leftStick?.horizontalPosition ?: 0).put("v", s.leftStick?.verticalPosition ?: 0))
        put("right_stick", JSONObject().put("h", s.rightStick?.horizontalPosition ?: 0).put("v", s.rightStick?.verticalPosition ?: 0))
        put("flight_mode_switch", s.flightModeSwitch?.name ?: JSONObject.NULL)
    }
}

/** Puts a number, or JSON null when it is missing or not finite (NaN is not valid JSON). */
internal fun JSONObject.num(key: String, value: Number?) {
    val d = value?.toDouble()
    put(key, if (d == null || d.isNaN() || d.isInfinite()) JSONObject.NULL else d)
}
