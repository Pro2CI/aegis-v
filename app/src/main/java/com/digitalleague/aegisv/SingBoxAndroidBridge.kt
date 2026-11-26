package com.digitalleague.aegisv

import android.net.VpnService
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
                "underNetworkExtension", "includeAllNetworks", "usePlatformShell",
                "usePlatformBridge", "usePlatformAutoRedirect", "usePlatformWIFIMonitor",
                "usePlatformConnectionOwnerFinder", "usePlatformNotification",
                "usePlatformNeighborResolver" -> false
                "tailscaleHostname" -> android.os.Build.MODEL ?: "Android"
                "registerMyInterface", "startDefaultInterfaceMonitor", "closeDefaultInterfaceMonitor",
                "clearDNSCache", "startNeighborMonitor", "closeNeighborMonitor",
                "sendNotification", "cancelNotification", "autoDetectInterfaceControl" -> null
                "readWIFIState", "localDNSTransport", "findConnectionOwner",
                "getInterfaces", "lookupUser", "openShellSession", "createBridge",
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
        val ipv4Routes = addRoutes(builder, options, "inet4RouteAddress")
        if (ipv4Routes == 0) builder.addRoute("0.0.0.0", 0)
        addRoutes(builder, options, "inet6RouteAddress")
        addStrings(builder, options, "dnsServerAddress", true)
        addStrings(builder, options, "excludePackage", false)
        val pfd = builder.establish() ?: error("Android refused to establish the TUN interface")
        tunDescriptor?.close()
        tunDescriptor = pfd
        return pfd.fd
    }

    private fun addAddresses(builder: VpnService.Builder, options: Any, property: String) {
        iterate(readProperty(options, property)) { item ->
            val address = readProperty(item, "address")?.toString() ?: return@iterate
            val prefix = (readProperty(item, "prefix") as? Number)?.toInt() ?: return@iterate
            builder.addAddress(address, prefix)
        }
    }

    private fun addRoutes(builder: VpnService.Builder, options: Any, property: String): Int {
        var count = 0
        iterate(readProperty(options, property)) { item ->
            val raw = item?.toString().orEmpty()
            val slash = raw.lastIndexOf('/')
            if (slash > 0) {
                val address = raw.substring(0, slash)
                val prefix = raw.substring(slash + 1).toIntOrNull() ?: return@iterate
                builder.addRoute(address, prefix)
                count++
            } else {
                val address = readProperty(item ?: return@iterate, "address")?.toString() ?: return@iterate
                val prefix = (readProperty(item, "prefix") as? Number)?.toInt() ?: return@iterate
                builder.addRoute(address, prefix)
                count++
            }
        }
        return count
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
        val suffix = name.replaceFirstChar { it.uppercase() }
        return runCatching { target.javaClass.getMethod("get$suffix").invoke(target) }
            .recoverCatching { target.javaClass.getMethod(name).invoke(target) }
            .getOrNull()
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
