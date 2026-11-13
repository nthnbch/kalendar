package ch.nthnbch.kalendar

import android.app.Application
import ch.nthnbch.kalendar.data.Alarms
import ch.nthnbch.kalendar.data.Repository
import ch.nthnbch.kalendar.data.SyncWorker

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Alarms.createChannel(this)
        if (Repository.get(this).account.value != null) SyncWorker.schedule(this)
    }
}
