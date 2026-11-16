package ch.nthnbch.kalendar

import android.app.Application
import ch.nthnbch.kalendar.data.Alarms
import ch.nthnbch.kalendar.data.Replies
import ch.nthnbch.kalendar.data.Repository
import ch.nthnbch.kalendar.data.Settings
import ch.nthnbch.kalendar.data.SyncWorker
import ch.nthnbch.kalendar.data.Updater

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Settings.applyTheme(this)
        Alarms.createChannel(this)
        Replies.createChannel(this)
        Updater.createChannel(this)
        Updater.schedule(this)
        if (Repository.get(this).account.value != null) SyncWorker.schedule(this)
    }
}
