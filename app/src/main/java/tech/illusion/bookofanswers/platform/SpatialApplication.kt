package tech.illusion.bookofanswers.platform

import android.app.Application
import com.pico.spatial.ui.foundation.dsl.launch
import tech.illusion.bookofanswers.mainApp

class SpatialApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        launch(::mainApp)
    }
}
