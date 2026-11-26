package com.digitalleague.aegisv

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.system.OsConstants
import android.os.ParcelFileDescriptor
import android.util.Log
import java.lang.reflect.Proxy

/**
 * Small Android host bridge for the official sing-box libbox CommandServer API.
 * Platform callbacks are dispatched reflectively so the app stays buildable
 * against the pinned gomobile AAR without leaking libbox types into the UI.
 */
internal class SingBoxAndroidBridge(private val vpn: VpnService) {
    private var commandServer: Any? = null
    private var tunDescriptor: ParcelFileDescriptor? = null
    private var platform: Any? = null
    private var handler: Any? = null

    @Synchronized
    fun start(config: String) {
        close()
        val libbox = Class.forName("io.nekohasekai.libbox.Libbox")
        val setupType = Class.forName("io.nekohasekai.libbox.SetupOptions")
        val setup = setupType.getDeclaredConstructor().newInstance()
        val base = vpn.filesDir
        val work = vpn.getExternalFilesDir(null) ?: vpn.filesDir
        val temp = vpn.cacheDir
        setupType.getMethod("setBasePath", String::class.java).invoke(setup, base.absolutePath)
        setupType.getMethod("setWorkingPath", String::class.java).invoke(setup, work.absolutePath)
        setupType.getMethod("setTempPath", String::class.java).invoke(setup, temp.absolutePath)
        runCatching { setupType.getMethod("setLogMaxLines", Long::class.javaPrimitiveType).invoke(setup, 1000L) }
        runCatching { setupType.getMethod("setDebug", Boolean::class.javaPrimitiveType).invoke(setup, false) }
        libbox.getMethod("setup", setupType).invoke(null, setup)

        val platformType = Class.forName("io.nekohasekai.libbox.PlatformInterface")
        platform = Proxy.newProxyInstance(platformType.classLoader, arrayOf(platformType)) { _, method, args ->
            when (method.name) {
                "usePlatformAutoDetectInterfaceControl" -> true
                "autoDetectInterfaceControl" -> {
                    val fd = (args?.getOrNull(0) as? Number)?.toInt() ?: -1
                    if (fd >= 0 && !vpn.protect(fd)) error("VpnService.protect(fd) failed")
                    null
                }
                "openTun" -> openTun(args?.getOrNull(0) ?: error("missing TunOptions"))
                "getInterfaces" -> createNetworkInterfaceIterator()
                "underNetworkExtension", "includeAllNetworks", "usePlatformShell",
                "usePlatformBridge", "usePlatformAutoRedirect", "usePlatformWIFIMonitor",
                "usePlatformConnectionOwnerFinder", "usePlatformNotification",
                "usePlatformNeighborResolver" -> false
                "tailscaleHostname" -> android.os.Build.MODEL ?: "Android"
                "registerMyInterface", "startDefaultInterfaceMonitor", "closeDefaultInterfaceMonitor",
                "clearDNSCache", "startNeighborMonitor", "closeNeighborMonitor",
                "sendNotification", "cancelNotification", "autoDetectInterfaceControl" -> null
                "readWIFIState", "localDNSTransport", "findConnectionOwner",
                "lookupUser", "openShellSession", "createBridge",
                "createAutoRedirect", "lookupSFTPServer", "readSystemSSHHostKey" -> null
                "toString" -> "AegisVPlatformInterface"
                "hashCode" -> System.identityHashCode(this)
                "equals" -> this === args?.getOrNull(0)
                else -> defaultValue(method.returnType)
            }
        }

        val handlerType = Class.forName("io.nekohasekai.libbox.CommandServerHandler")
        handler = Proxy.newProxyInstance(handlerType.classLoader, arrayOf(handlerType)) { _, method, args ->
            when (method.name) {
                "writeDebugMessage" -> { Log.d("AegisV/libbox", args?.getOrNull(0)?.toString().orEmpty()); null }
                "serviceStop" -> { close(); null }
                "serviceReload", "setSystemProxyEnabled", "triggerNativeCrash" -> null
                "connectSSHAgent" -> -1
                "getSystemProxyStatus" -> null
                "toString" -> "AegisVCommandServerHandler"
                "hashCode" -> System.identityHashCode(this)
                "equals" -> this === args?.getOrNull(0)
                else -> defaultValue(method.returnType)
            }
        }

        val commandType = Class.forName("io.nekohasekai.libbox.CommandServer")
        val ctor = commandType.constructors.firstOrNull { it.parameterCount == 2 }
            ?: error("libbox CommandServer(handler, platformInterface) constructor not found")
        commandServer = ctor.newInstance(handler, platform)
        commandType.getMethod("start").invoke(commandServer)
        val overrideType = Class.forName("io.nekohasekai.libbox.OverrideOptions")
        val overrideOptions = overrideType.getDeclaredConstructor().newInstance()
        val startMethod = commandType.methods.firstOrNull {
            it.name == "startOrReloadService" && it.parameterCount == 2 &&
                it.parameterTypes[0] == String::class.java
        } ?: error("libbox startOrReloadService(config, options) not found")
        startMethod.invoke(commandServer, config, overrideOptions)
    }

    private fun openTun(options: Any): Int {
        val builder = vpn.Builder().setSession("AEGIS-V sing-box")
        val mtu = (readProperty(options, "mtu") as? Number)?.toInt() ?: 1500
        builder.setMtu(mtu.coerceIn(576, 9000))
        addAddresses(builder, options, "inet4Address")
        addAddresses(builder, options, "inet6Address")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            addRoutes(builder, options, "inet4RouteAddress")
            addRoutes(builder, options, "inet6RouteAddress")
        } else {
            addRoutes(builder, options, "inet4RouteRange")
            addRoutes(builder, options, "inet6RouteRange")
        }
        addStrings(builder, options, "dnsServerAddress", true)
        addStrings(builder, options, "excludePackage", false)
        val pfd = builder.establish() ?: error("Android refused to establish the TUN interface")
        tunDescriptor?.close()
        tunDescriptor = pfd
        return pfd.fd
    }

    private fun addAddresses(builder: VpnService.Builder, options: Any, property: String) {
        iterate(readProperty(options, property)) { item ->
            val entry = item ?: return@iterate
            val address = readProperty(entry, "address")?.toString() ?: return@iterate
            val prefix = (readProperty(entry, "prefix") as? Number)?.toInt() ?: return@iterate
            builder.addAddress(address, prefix)
        }
    }

    private fun addRoutes(builder: VpnService.Builder, options: Any, property: String) {
        iterate(readProperty(options, property)) { item ->
            val entry = item ?: return@iterate
            val address = readProperty(entry, "address")?.toString() ?: return@iterate
            val prefix = (readProperty(entry, "prefix") as? Number)?.toInt() ?: return@iterate
            builder.addRoute(address, prefix)
        }
    }

    private fun addStrings(builder: VpnService.Builder, options: Any, property: String, dns: Boolean) {
        iterate(readProperty(options, property)) { item ->
            val value = item?.toString() ?: return@iterate
            if (dns) builder.addDnsServer(value)
            else runCatching { builder.addDisallowedApplication(value) }
        }
    }

    private fun iterate(value: Any?, action: (Any?) -> Unit) {
        if (value == null) return
        runCatching {
            val hasNext = value.javaClass.getMethod("hasNext")
            val next = value.javaClass.getMethod("next")
            while (hasNext.invoke(value) as Boolean) action(next.invoke(value))
        }.onFailure { Log.w("AegisV/libbox", "Iterator read failed", it) }
    }

    private fun readProperty(target: Any, name: String): Any? {
        val getter = "get\${name.replaceFirstChar { it.uppercase() }}"
        val method = target.javaClass.methods.firstOrNull {
            it.parameterCount == 0 && (it.name.equals(getter, ignoreCase = true) ||
                it.name.equals(name, ignoreCase = true))
        } ?: return null
        return runCatching { method.invoke(target) }.getOrNull()
    }

    private fun createNetworkInterfaceIterator(): Any {
        val connectivity = vpn.getSystemService(ConnectivityManager::class.java)
        val systemInterfaces = runCatching {
            java.net.NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        }.getOrDefault(emptyList())
        val boxType = Class.forName("io.nekohasekai.libbox.NetworkInterface")
        val entries = mutableListOf<Any>()

        for (network in connectivity.allNetworks) {
            val link = runCatching { connectivity.getLinkProperties(network) }.getOrNull() ?: continue
            val capabilities = runCatching { connectivity.getNetworkCapabilities(network) }.getOrNull() ?: continue
            val name = link.interfaceName ?: continue
            val systemInterface = systemInterfaces.firstOrNull { it.name == name } ?: continue
            val entry = boxType.getDeclaredConstructor().newInstance()
            val flags = runCatching {
                var value = 0
                if (systemInterface.isUp) value = value or OsConstants.IFF_UP or OsConstants.IFF_RUNNING
                if (systemInterface.isLoopback) value = value or OsConstants.IFF_LOOPBACK
                if (systemInterface.isPointToPoint) value = value or OsConstants.IFF_POINTOPOINT
                if (systemInterface.supportsMulticast()) value = value or OsConstants.IFF_MULTICAST
                value
            }.getOrDefault(0)

            setProperty(entry, "name", name)
            setProperty(entry, "index", systemInterface.index)
            setProperty(entry, "mtu", runCatching { systemInterface.mtu }.getOrDefault(1500))
            setProperty(entry, "addresses", stringIterator(systemInterface.interfaceAddresses.map {
                "\${it.address.hostAddress}/\${it.networkPrefixLength}"
            }))
            setProperty(entry, "dnsServer", stringIterator(link.dnsServers.mapNotNull { it.hostAddress }))
            setProperty(entry, "dnsSearchDomain", stringIterator(
                link.domains?.split(Regex("[,\\\\s]+"))?.filter { it.isNotBlank() }.orEmpty()
            ))
            setProperty(entry, "gateway", stringIterator(
                link.routes.filter { it.destination.prefixLength == 0 }
                    .mapNotNull { route -> route.gateway?.takeUnless { it.isAnyLocalAddress }?.hostAddress }
            ))
            setProperty(entry, "flags", flags)
            setProperty(entry, "type", when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 1
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 2
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 3
                else -> 0
            })
            setProperty(entry, "metered",
                !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
            entries.add(entry)
        }

        val iteratorType = Class.forName("io.nekohasekai.libbox.NetworkInterfaceIterator")
        val iterator = entries.iterator()
        return Proxy.newProxyInstance(iteratorType.classLoader, arrayOf(iteratorType)) { _, method, _ ->
            when (method.name) {
                "hasNext" -> iterator.hasNext()
                "next" -> if (iterator.hasNext()) iterator.next() else boxType.getDeclaredConstructor().newInstance()
                else -> defaultValue(method.returnType)
            }
        }
    }

    private fun stringIterator(values: List<String>): Any {
        val iteratorType = Class.forName("io.nekohasekai.libbox.StringIterator")
        val iterator = values.iterator()
        return Proxy.newProxyInstance(iteratorType.classLoader, arrayOf(iteratorType)) { _, method, _ ->
            when (method.name) {
                "hasNext" -> iterator.hasNext()
                "next" -> if (iterator.hasNext()) iterator.next() else ""
                "len" -> values.size
                else -> defaultValue(method.returnType)
            }
        }
    }

    private fun setProperty(target: Any, name: String, value: Any) {
        val setter = "set\${name.replaceFirstChar { it.uppercase() }}"
        val method = target.javaClass.methods.firstOrNull {
            it.name.equals(setter, ignoreCase = true) && it.parameterCount == 1 &&
                it.parameterTypes[0].isAssignableFrom(value.javaClass)
        } ?: error("libbox NetworkInterface setter not found: $setter")
        method.invoke(target, value)
    }

    private fun defaultValue(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        java.lang.Void.TYPE -> null
        else -> null
    }

    @Synchronized
    fun close() {
        val server = commandServer
        commandServer = null
        if (server != null) {
            runCatching { server.javaClass.getMethod("closeService").invoke(server) }
            runCatching { server.javaClass.getMethod("close").invoke(server) }
        }
        runCatching { tunDescriptor?.close() }
        tunDescriptor = null
        platform = null
        handler = null
    }
}
