package io.nekohasekai.sagernet.utils

import android.annotation.TargetApi
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.runBlocking
import java.net.UnknownHostException

object DefaultNetworkListener {
    private sealed class NetworkMessage {
        class Start(val key: Any, val listener: (Network?, LinkProperties?) -> Unit) : NetworkMessage()
        class Get : NetworkMessage() {
            val response = CompletableDeferred<Network>()
        }

        class Stop(val key: Any) : NetworkMessage()

        /** A [Get] that timed out or was cancelled. */
        class Abandon(val request: Get) : NetworkMessage()

        /** Try to register again after every registration failed. */
        object Retry : NetworkMessage()

        class Put(val network: Network) : NetworkMessage()
        class Update(val network: Network, val linkProperties: LinkProperties? = null) : NetworkMessage()
        class Lost(val network: Network) : NetworkMessage()
        object Unavailable : NetworkMessage()
    }

    private val networkActor = GlobalScope.actor<NetworkMessage>(Dispatchers.Unconfined) {
        val listeners = mutableMapOf<Any, (Network?, LinkProperties?) -> Unit>()

        // an exception would end the actor, and with it all network updates of this process
        fun notify(
            listener: (Network?, LinkProperties?) -> Unit,
            network: Network?,
            link: LinkProperties?,
        ) {
            try {
                listener(network, link)
            } catch (e: Exception) {
                Logs.w(e)
            }
        }

        val networks = NetworkCandidates<Network, LinkProperties>()
        val pendingRequests = PendingRequests<Network>()
        for (message in channel) when (message) {
            is NetworkMessage.Start -> {
                if (listeners.isEmpty()) register()
                listeners[message.key] = message.listener
                if (networks.current != null) notify(message.listener, networks.current, networks.currentLink)
            }
            is NetworkMessage.Get -> when {
                // check() here would end the actor
                listeners.isEmpty() -> message.response.completeExceptionally(
                    IllegalStateException("Getting network without any listeners is not supported")
                )

                networks.current == null -> pendingRequests.add(message.response)
                else -> message.response.complete(networks.current!!)
            }
            is NetworkMessage.Abandon -> pendingRequests.remove(message.request.response)
            is NetworkMessage.Retry -> if (listeners.isNotEmpty() && !registered) register()
            is NetworkMessage.Stop -> if (listeners.isNotEmpty() && // was not empty
                listeners.remove(message.key) != null && listeners.isEmpty()
            ) {
                networks.clear()
                pendingRequests.failAll(UnknownHostException())
                unregister()
            }

            is NetworkMessage.Put -> {
                networks.onAvailable(message.network, fallback)
                pendingRequests.completeAll(message.network)
                listeners.values.forEach { notify(it, networks.current, networks.currentLink) }
            }
            is NetworkMessage.Update -> if (networks.onUpdate(message.network, message.linkProperties)) {
                listeners.values.forEach { notify(it, networks.current, networks.currentLink) }
            }
            is NetworkMessage.Lost -> if (networks.onLost(message.network)) {
                listeners.values.forEach { notify(it, networks.current, networks.currentLink) }
            }
            is NetworkMessage.Unavailable -> if (listeners.isNotEmpty() && !fallback) {
                // requestNetwork has released its callback; switch to listening for all matches.
                registered = false
                networks.clear()
                listeners.values.forEach { notify(it, null, null) }
                fallback = true
                registerFallback()
            }
        }
    }

    suspend fun start(key: Any, listener: (Network?, LinkProperties?) -> Unit) =
        networkActor.send(NetworkMessage.Start(key, listener))

    /**
     * @throws UnknownHostException if there is no network within [timeoutMs]
     */
    suspend fun get(timeoutMs: Long = 10_000L): Network = if (fallback) @TargetApi(23) {
        SagerNet.connectivity.activeNetwork
            ?: throw UnknownHostException() // failed to listen, return current if available
    } else NetworkMessage.Get().run {
        networkActor.send(this)
        awaitOrAbandon(response, timeoutMs) {
            networkActor.trySend(NetworkMessage.Abandon(this))
        } ?: throw UnknownHostException()
    }

    /**
     * False when no network callback could be registered: network changes are then not
     * delivered to listeners until a retry succeeds, even though [get] still works.
     */
    val listening get() = registered

    suspend fun stop(key: Any) = networkActor.send(NetworkMessage.Stop(key))

    // NB: this runs in ConnectivityThread, and this behavior cannot be changed until API 26
    private object Callback : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) =
            runBlocking { networkActor.send(NetworkMessage.Put(network)) }

        override fun onCapabilitiesChanged(
            network: Network, networkCapabilities: NetworkCapabilities
        ) { // it's a good idea to refresh capabilities
            runBlocking { networkActor.send(NetworkMessage.Update(network)) }
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) =
            runBlocking { networkActor.send(NetworkMessage.Update(network, linkProperties)) }

        override fun onLost(network: Network) =
            runBlocking { networkActor.send(NetworkMessage.Lost(network)) }

        override fun onUnavailable() =
            runBlocking { networkActor.send(NetworkMessage.Unavailable) }
    }

    private var fallback = false

    @Volatile
    private var registered = false
    private var retryAttempt = 0
    private val retry = Runnable { networkActor.trySend(NetworkMessage.Retry) }
    private val request = NetworkRequest.Builder().apply {
        addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
        if (Build.VERSION.SDK_INT == 23) {  // workarounds for OEM bugs
            removeCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            removeCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
        }
    }.build()
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Unfortunately registerDefaultNetworkCallback is going to return VPN interface since Android P DP1:
     * https://android.googlesource.com/platform/frameworks/base/+/dda156ab0c5d66ad82bdcf76cda07cbc0a9c8a2e
     *
     * This makes doing a requestNetwork with REQUEST necessary so that we don't get ALL possible networks that
     * satisfies default network capabilities but only THE default network. Unfortunately, we need to have
     * android.permission.CHANGE_NETWORK_STATE to be able to call requestNetwork.
     *
     * Source: https://android.googlesource.com/platform/frameworks/base/+/2df4c7d/services/core/java/com/android/server/ConnectivityService.java#887
     */
    private fun register() {
        try {
            fallback = false
            registered = false
            when (Build.VERSION.SDK_INT) {
                in 31..Int.MAX_VALUE -> @TargetApi(31) {
                    SagerNet.connectivity.registerBestMatchingNetworkCallback(
                        request, Callback, mainHandler
                    )
                }
                in 28 until 31 -> @TargetApi(28) {  // we want REQUEST here instead of LISTEN
                    SagerNet.connectivity.requestNetwork(request, Callback, mainHandler)
                }
                in 26 until 28 -> @TargetApi(26) {
                    SagerNet.connectivity.registerDefaultNetworkCallback(Callback, mainHandler)
                }
                in 24 until 26 -> @TargetApi(24) {
                    SagerNet.connectivity.registerDefaultNetworkCallback(Callback)
                }
                else -> {
                    SagerNet.connectivity.requestNetwork(request, Callback)
                    // known bug on API 23: https://stackoverflow.com/a/33509180/2245107
                }
            }
            registered = true
            retryAttempt = 0
        } catch (e: Exception) {
            Logs.w(e)
            fallback = true
            registerFallback()
        }
    }

    /**
     * Keeps getting network changes after the preferred registration failed (no permission,
     * too many callbacks). LISTEN reports every matching non-VPN network instead of only the
     * default one, which is still better than no updates at all.
     */
    private fun registerFallback() {
        try {
            if (Build.VERSION.SDK_INT >= 26) @TargetApi(26) {
                SagerNet.connectivity.registerNetworkCallback(request, Callback, mainHandler)
            } else {
                SagerNet.connectivity.registerNetworkCallback(request, Callback)
            }
            registered = true
            retryAttempt = 0
            Logs.w("Using fallback network callback; tracking all matching networks")
        } catch (e: Exception) {
            Logs.w(e)
            val delay = listenRetryDelayMs(retryAttempt++)
            Logs.w("Network callback unavailable, network changes are not monitored, retry in ${delay / 1000}s")
            mainHandler.postDelayed(retry, delay)
        }
    }

    // a callback that was never registered throws IllegalArgumentException here
    private fun unregister() {
        mainHandler.removeCallbacks(retry)
        retryAttempt = 0
        if (!registered) return
        registered = false
        try {
            SagerNet.connectivity.unregisterNetworkCallback(Callback)
        } catch (e: Exception) {
            Logs.w(e)
        }
    }
}
