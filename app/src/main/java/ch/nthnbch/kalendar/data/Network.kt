package ch.nthnbch.kalendar.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Thrown instead of attempting a request when the device has no connection at all. */
class OfflineException : IOException("No network connection")

object Network {

    /** True when the system reports a network that can reach the internet. */
    fun isOnline(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = manager.getNetworkCapabilities(manager.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** Errors that mean "could not reach the server" rather than "the server said no". */
    fun isConnectivityFailure(e: Throwable): Boolean =
        e is OfflineException || e is UnknownHostException || e is ConnectException ||
            e is SocketTimeoutException || e is NoRouteToHostException
}
