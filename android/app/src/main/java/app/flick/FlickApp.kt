package app.flick

import android.app.Application
import android.content.Context
import app.flick.core.FlickClient

class FlickApp : Application() {
    lateinit var store: Store
        private set
    lateinit var discovery: Discovery
        private set
    val client by lazy { FlickClient() }
    /** App-wide background work (e.g. sending the clipboard). */
    val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO +
            kotlinx.coroutines.CoroutineExceptionHandler { _, e -> CrashLog.caught("background", e) },
    )

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        Payloads.appContext = this
        store = Store(this)
        discovery = Discovery(this, store, client)
        TransferService.createChannel(this)
        LinkService.createChannels(this)
    }

    companion object {
        fun get(ctx: Context) = ctx.applicationContext as FlickApp
    }
}
