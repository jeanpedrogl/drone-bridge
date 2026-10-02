package io.github.jeanpedrogl.dronebridge

import android.app.Application
import android.content.Context
import com.cySdkyc.clx.Helper

class DroneApplication : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // Required by the DJI Mobile SDK v4 before anything else touches it.
        Helper.install(this)
    }
}
