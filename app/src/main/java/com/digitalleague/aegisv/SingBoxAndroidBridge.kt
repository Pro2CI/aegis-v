package com.digitalleague.aegisv

import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.IpPrefix
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.OsConstants
import android.util.Log
import io.nekohasekai.libbox.BridgeOptions
import io.nekohasekai.libbox.BridgeSession
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.Notification as LibboxNotification
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.PlatformUser
import io.nekohasekai.libbox.SetupOptions
import io.nekohasekai.libbox.ShellSession
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import io.nekohasekai.libbox.NetworkInterface as LibboxNetworkInterface
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface as JavaNetworkInterface

/**
 * Native Android host for sing-box libbox 1.14.x.
 * Gomobile interfaces are implemented directly: no reflective proxies or
 * silent default values at the TUN/file-descriptor boundary.
 */
internal class SingBoxAndroidBridge(private val vpn: VpnService) {
    companion object {
        private const val TAG = "AegisV/libbox"
        private val setupLock = Any()

        @Volatile
        private var setupCompleted = false
    }

    @Volatile
    private var commandServer: CommandServer? = null

    @Volatile
    private var tunDescriptor: ParcelFileDescriptor? = null

    private val platform = AegisPlatformInterface()
    private val handler = AegisCommandServerHandler()

    @Synchronized
    fun start(config: String) {
        close()
        require(config.isNotBlank() && config != "{}") { "sing-box configuration is empty" }
        ensureLibboxSetup()

        val server = CommandServer(handler, platform)
        try {
            commandServer = server
            server.start()
            server.startOrReloadService(config, OverrideOptions())
            check(tunDescriptor != null) {
                "sing-box accepted the configuration but did not call Android PlatformInterface.openTun()"
            }
            Log.i(TAG, "CommandServer started; Android TUN fd is established")
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    private fun ensureLibboxSetup() {
        if (setupCompleted) return
        synchronized(setupLock) {
            if (setupCompleted) return
            val base = vpn.filesDir.apply { mkdirs() }
            val temp = vpn.cacheDir.apply { mkdirs() }
            val options = SetupOptions().apply {
                basePath = base.absolutePath
                workingPath = base.absolutePath
                tempPath = temp.absolutePath
                fixAndroidStack =
                    Build.VERSION.SDK_INT <= Build.VERSION_CODES.N_MR1 ||
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                logMaxLines = 3000
                debug = false
                oomKillerEnabled = true
                oomMemoryLimit = 0L
                crashReportSource = "aegis-v"
            }
            Libbox.setup(options)
            setupCompleted = true
            Log.i(TAG, "libbox runtime initialized")
        }
    }

    private inner class AegisCommandServerHandler : CommandServerHandler {
        override fun serviceStop() {
            vpn.stopSelf()
        }

        override fun serviceReload() {
            Log.i(TAG, "libbox requested service reload")
        }

        override fun getSystemProxyStatus(): SystemProxyStatus = SystemProxyStatus()

        override fun setSystemProxyEnabled(isEnabled: Boolean) {
            Log.d(TAG, "System proxy request ignored in Android TUN mode")
        }

        override fun triggerNativeCrash() {
            Log.w(TAG, "Ignoring native-crash debug callback")
        }

        override fun writeDebugMessage(message: String) {
            Log.d(TAG, message)
        }

        override fun connectSSHAgent(): Int = -1
    }

    private inner class AegisPlatformInterface : PlatformInterface {
        override fun localDNSTransport(): LocalDNSTransport? = null

        override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

        override fun autoDetectInterfaceControl(fd: Int) {
            check(vpn.protect(fd)) { "VpnService.protect(" + fd + ") failed" }
        }

        override fun openTun(options: TunOptions): Int {
            check(VpnService.prepare(vpn) == null) { "Android VPN permission is not granted" }

            val builder = vpn.Builder()
                .setSession("Aegis-V sing-box")
                .setMtu(options.mtu.coerceIn(576, 9000))

            addAddresses(builder, options)
            if (options.autoRoute) {
                addDnsServers(builder, options)
                addRoutes(builder, options)
                addExcludedRoutes(builder, options)
                addApplicationRules(builder, options)
            }

            val descriptor = builder.establish()
                ?: error("Android refused to establish the VPN TUN interface")

            val previous = tunDescriptor
            tunDescriptor = descriptor
            runCatching { previous?.close() }
            Log.i(TAG, "Android TUN established: fd=" + descriptor.fd + ", mtu=" + options.mtu)
            return descriptor.fd
        }

        private fun addAddresses(builder: VpnService.Builder, options: TunOptions) {
            val ipv4 = options.inet4Address
            while (ipv4.hasNext()) {
                val address = ipv4.next()
                builder.addAddress(address.address(), address.prefix())
            }
            val ipv6 = options.inet6Address
            while (ipv6.hasNext()) {
                val address = ipv6.next()
                builder.addAddress(address.address(), address.prefix())
            }
        }

        private fun addDnsServers(builder: VpnService.Builder, options: TunOptions) {
            val dnsServers = options.dnsServerAddress
            while (dnsServers.hasNext()) {
                val address = dnsServers.next()
                if (address.isNotBlank()) {
                    runCatching { builder.addDnsServer(address) }
                        .onFailure { Log.w(TAG, "Ignoring invalid TUN DNS address: " + address, it) }
                }
            }
        }

        private fun addRoutes(builder: VpnService.Builder, options: TunOptions) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val ipv4 = options.inet4RouteAddress
                val hasIpv4Route = ipv4.hasNext()
                while (ipv4.hasNext()) {
                    val route = ipv4.next()
                    builder.addRoute(route.address(), route.prefix())
                }
                if (!hasIpv4Route && options.inet4Address.hasNext()) {
                    builder.addRoute("0.0.0.0", 0)
                }

                val ipv6 = options.inet6RouteAddress
                val hasIpv6Route = ipv6.hasNext()
                while (ipv6.hasNext()) {
                    val route = ipv6.next()
                    builder.addRoute(route.address(), route.prefix())
                }
                if (!hasIpv6Route && options.inet6Address.hasNext()) {
                    builder.addRoute("::", 0)
                }
            } else {
                val ipv4 = options.inet4RouteRange
                while (ipv4.hasNext()) {
                    val route = ipv4.next()
                    builder.addRoute(route.address(), route.prefix())
                }
                val ipv6 = options.inet6RouteRange
                while (ipv6.hasNext()) {
                    val route = ipv6.next()
                    builder.addRoute(route.address(), route.prefix())
                }
            }
        }

        private fun addExcludedRoutes(builder: VpnService.Builder, options: TunOptions) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
            val ipv4 = options.inet4RouteExcludeAddress
            while (ipv4.hasNext()) {
                val route = ipv4.next()
                builder.excludeRoute(IpPrefix(InetAddress.getByName(route.address()), route.prefix()))
            }
            val ipv6 = options.inet6RouteExcludeAddress
            while (ipv6.hasNext()) {
                val route = ipv6.next()
                builder.excludeRoute(IpPrefix(route.address(), route.prefix()))
            }
        }

        private fun addApplicationRules(builder: VpnService.Builder, options: TunOptions) {
            val included = options.includePackage
            while (included.hasNext()) {
                val packageName = included.next()
                try {
                    builder.addAllowedApplication(packageName)
                } catch (_: PackageManager.NameNotFoundException) {
                    Log.w(TAG, "Allowed package is not installed: " + packageName)
                }
            }

            val excluded = options.excludePackage
            while (excluded.hasNext()) {
                val packageName = excluded.next()
                try {
                    builder.addDisallowedApplication(packageName)
                } catch (_: PackageManager.NameNotFoundException) {
                    Log.w(TAG, "Disallowed package is not installed: " + packageName)
                }
            }
        }

        override fun findConnectionOwner(
            ipProtocol: Int,
            sourceAddress: String,
            sourcePort: Int,
            destinationAddress: String,
            destinationPort: Int
        ): ConnectionOwner {
            val uid = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    vpn.getSystemService(ConnectivityManager::class.java).getConnectionOwnerUid(
                        ipProtocol,
                        InetSocketAddress(sourceAddress, sourcePort),
                        InetSocketAddress(destinationAddress, destinationPort)
                    )
                } else {
                    Process.INVALID_UID
                }
            }.getOrDefault(Process.INVALID_UID)
            return ConnectionOwner().apply { userId = uid }
        }

        override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) = Unit
        override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) = Unit

        override fun getInterfaces(): NetworkInterfaceIterator {
            val entries = runCatching { buildNetworkInterfaces() }
                .onFailure { Log.w(TAG, "Unable to enumerate Android network interfaces", it) }
                .getOrDefault(emptyList())
            val iterator = entries.iterator()
            return object : NetworkInterfaceIterator {
                override fun hasNext(): Boolean = iterator.hasNext()
                override fun next(): LibboxNetworkInterface =
                    if (iterator.hasNext()) iterator.next() else LibboxNetworkInterface()
            }
        }

        private fun buildNetworkInterfaces(): List<LibboxNetworkInterface> {
            val connectivity = vpn.getSystemService(ConnectivityManager::class.java)
            val systemInterfaces = JavaNetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            val result = mutableListOf<LibboxNetworkInterface>()

            for (network in connectivity.allNetworks) {
                val capabilities = connectivity.getNetworkCapabilities(network) ?: continue
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
                val link = connectivity.getLinkProperties(network) ?: continue
                val name = link.interfaceName ?: continue
                val systemInterface = systemInterfaces.firstOrNull { it.name == name } ?: continue

                val flags = runCatching {
                    var value = 0
                    if (systemInterface.isUp) {
                        value = value or OsConstants.IFF_UP or OsConstants.IFF_RUNNING
                    }
                    if (systemInterface.isLoopback) value = value or OsConstants.IFF_LOOPBACK
                    if (systemInterface.isPointToPoint) value = value or OsConstants.IFF_POINTOPOINT
                    if (systemInterface.supportsMulticast()) value = value or OsConstants.IFF_MULTICAST
                    value
                }.getOrDefault(0)

                result += LibboxNetworkInterface().apply {
                    this.name = name
                    index = systemInterface.index
                    mtu = runCatching { systemInterface.mtu }.getOrDefault(1500)
                    addresses = StringArray(systemInterface.interfaceAddresses.map {
                        it.address.hostAddress.substringBefore("%") + "/" + it.networkPrefixLength
                    })
                    dnsServer = StringArray(link.dnsServers.mapNotNull { it.hostAddress })
                    gateway = StringArray(
                        link.routes.filter { it.destination.prefixLength == 0 }
                            .mapNotNull { route ->
                                route.gateway?.takeUnless { it.isAnyLocalAddress }?.hostAddress
                            }
                    )
                    this.flags = flags
                    type = when {
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ->
                            Libbox.InterfaceTypeWIFI
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ->
                            Libbox.InterfaceTypeCellular
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ->
                            Libbox.InterfaceTypeEthernet
                        else -> Libbox.InterfaceTypeOther
                    }
                    metered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                }
            }
            return result
        }

        override fun underNetworkExtension(): Boolean = false
        override fun includeAllNetworks(): Boolean = false
        override fun readWIFIState(): WIFIState? = null
        override fun sendNotification(notification: LibboxNotification) = Unit
        override fun clearDNSCache() = Unit
        override fun registerMyInterface(name: String) = Unit
        override fun usePlatformShell(): Boolean = false
        override fun checkPlatformShell() = Unit
        override fun lookupUser(username: String): PlatformUser = PlatformUser()
        override fun lookupSFTPServer(): String = ""
        override fun readSystemSSHHostKey(): String = ""
        override fun tailscaleHostname(): String = Build.MODEL ?: "Android"

        override fun openShellSession(
            user: PlatformUser,
            command: String,
            environ: StringIterator,
            term: String,
            rows: Int,
            cols: Int
        ): ShellSession = throw UnsupportedOperationException("Shell sessions are not supported on Android")

        override fun startNeighborMonitor(listener: io.nekohasekai.libbox.NeighborUpdateListener) = Unit
        override fun closeNeighborMonitor(listener: io.nekohasekai.libbox.NeighborUpdateListener) = Unit
        override fun usePlatformBridge(): Boolean = false
        override fun cancelNotification(identifier: String, typeID: Int) = Unit

        override fun createBridge(options: BridgeOptions): BridgeSession =
            throw UnsupportedOperationException("Platform bridge is not used by Aegis-V")
    }

    private class StringArray(private val values: List<String>) : StringIterator {
        private var index = 0
        override fun hasNext(): Boolean = index < values.size
        override fun next(): String = if (hasNext()) values[index++] else ""
        override fun len(): Int = values.size
    }

    @Synchronized
    fun close() {
        val server = commandServer
        commandServer = null
        if (server != null) {
            runCatching { server.close() }
                .onFailure { Log.w(TAG, "CommandServer.close failed", it) }
            runCatching { server.closeService() }
                .onFailure { Log.w(TAG, "CommandServer.closeService failed", it) }
        }
        runCatching { tunDescriptor?.close() }
            .onFailure { Log.w(TAG, "TUN descriptor close failed", it) }
        tunDescriptor = null
    }
}