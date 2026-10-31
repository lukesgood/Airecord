package com.airecord.app

import android.app.Application
import android.content.Context
import com.airecord.app.data.MeetingRepository
import com.airecord.app.data.SettingsRepository
import com.airecord.app.models.ModelManager
import com.airecord.app.service.MeetingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

class AirecordApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        MeetingService.createChannel(this)
    }
}

/** Manual dependency container. */
class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = SettingsRepository(context)
    val meetings = MeetingRepository(File(context.filesDir, "meetings"))
    val models = ModelManager(context, appScope)
}

val Context.container: AppContainer get() = (applicationContext as AirecordApp).container
