package ch.nthnbch.kalendar.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ch.nthnbch.kalendar.BuildConfig
import ch.nthnbch.kalendar.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Looks for a newer release on GitHub, downloads its APK and hands it to the system installer.
 * Android always asks the user to confirm an installation: a fully silent update is not possible for a normal app.
 */
object Updater {

    const val CHANNEL = "updates"
    private const val API = "https://api.github.com/repos/nthnbch/kalendar/releases/latest"

    data class Release(val version: String, val apkUrl: String)

    private val client by lazy { OkHttpClient.Builder().callTimeout(120, TimeUnit.SECONDS).build() }

    /** The newest published release, or null when it cannot be read. */
    suspend fun latest(): Release? = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(Request.Builder().url(API).header("Accept", "application/vnd.github+json").build()).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val json = JSONObject(response.body!!.string())
                val assets = json.getJSONArray("assets")
                val apk = (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { it.getString("name").endsWith(".apk") }
                    ?: return@use null
                Release(json.getString("tag_name").removePrefix("v"), apk.getString("browser_download_url"))
            }
        }.getOrNull()
    }

    fun isNewer(release: Release): Boolean {
        fun parts(v: String) = v.split('.', '-').map { it.toIntOrNull() ?: 0 }
        val a = parts(release.version)
        val b = parts(BuildConfig.VERSION_NAME)
        for (i in 0 until maxOf(a.size, b.size)) {
            val d = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    suspend fun download(context: Context, release: Release): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { deleteRecursively(); mkdirs() }
        val file = File(dir, "Kalendar-${release.version}.apk")
        client.newCall(Request.Builder().url(release.apkUrl).build()).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code}" }
            response.body!!.byteStream().use { input -> file.outputStream().use { input.copyTo(it) } }
        }
        file
    }

    /** Opens the system installer on [apk]; first sends the user to the "install unknown apps" switch if needed. */
    fun install(context: Context, apk: File) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun createChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL, context.getString(R.string.settings_updates), NotificationManager.IMPORTANCE_LOW)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<UpdateWorker>(1, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("update-check", ExistingPeriodicWorkPolicy.KEEP, request)
    }

    internal fun notify(context: Context, release: Release) {
        val granted = android.os.Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!granted) return
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, ch.nthnbch.kalendar.ui.MainActivity::class.java).putExtra("open_settings", true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.update_version_available, release.version))
            .setContentText(context.getString(R.string.update_notice))
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(7001, notification)
    }
}

/** Once a day: tells the user (once per version) when a newer release exists. */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!Settings.updateCheck(applicationContext)) return Result.success()
        val release = Updater.latest() ?: return Result.success()
        if (Updater.isNewer(release) && Settings.lastUpdateNotified(applicationContext) != release.version) {
            Settings.setLastUpdateNotified(applicationContext, release.version)
            Updater.notify(applicationContext, release)
        }
        return Result.success()
    }
}
