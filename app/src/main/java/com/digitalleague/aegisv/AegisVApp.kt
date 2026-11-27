package com.digitalleague.aegisv

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.Proxy
import java.net.URI
import java.net.URLDecoder
import java.net.URL
import java.net.InetAddress
import java.net.UnknownHostException
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.net.ssl.HttpsURLConnection
import kotlin.system.measureTimeMillis

/**
 * ============================================================================
 * AEGIS-V & ПОЛНАЯ ЦИФРОВАЯ ЛИГА (10/10 ГЕРОЕВ) — ЕДИНЫЙ МОДУЛЬ ДЛЯ ANDROID
 * ============================================================================
 * Включает:
 * 1. Всех 10 супергероев Цифровой Лиги Справедливости, объединённых шиной.
 * 2. Системный AegisVpnService + генератор конфигов sing-box (libbox).
 * 3. Встроенную лабораторию самодиагностики (LeagueDiagnosticLab) для проверки
 *    связи, DNS, парсера и сокетов в один клик прямо в приложении.
 * ============================================================================
 */

// ============================================================================
// ЧАСТЬ 1: МОДЕЛИ ДАННЫХ И КОНФИГУРАЦИЙ
// ============================================================================

enum class VpnProtocol {
    VLESS_REALITY,
    VLESS_TLS,
    TROJAN,
    HYSTERIA2,
    HYSTERIA,
    TUIC,
    VMESS,
    SOCKS,
    SHADOWSOCKS,
    ANYTLS,
    SHADOWTLS,
    HTTP,
    SSH,
    UNKNOWN
}

enum class RegionProfile(val displayName: String, val preferredProtocols: List<VpnProtocol>) {
    STRICT_DPI(
        "Регион с жестким DPI",
        listOf(VpnProtocol.VLESS_REALITY, VpnProtocol.HYSTERIA2, VpnProtocol.TROJAN)
    ),
    PROTOCOL_BLOCK(
        "Блокировка классических VPN",
        listOf(VpnProtocol.VLESS_REALITY, VpnProtocol.VLESS_TLS, VpnProtocol.TROJAN)
    ),
    UNSTABLE_NETWORK(
        "Нестабильный канал / Потеря пакетов",
        listOf(VpnProtocol.HYSTERIA2, VpnProtocol.VLESS_REALITY, VpnProtocol.TROJAN)
    ),
    OPEN_WEB(
        "Открытая сеть",
        listOf(VpnProtocol.VLESS_REALITY, VpnProtocol.VLESS_TLS, VpnProtocol.SHADOWSOCKS)
    )
}

data class FreeVpnNode(
    val id: String,
    val rawUri: String,
    val protocol: VpnProtocol,
    val host: String,
    val port: Int,
    val sni: String,
    val remark: String,
    val latencyMs: Long = -1L,
    val purityScore: Int = 100,
    val lastCheckedTimestamp: Long = System.currentTimeMillis()
) {
    val isHealthy: Boolean
        get() = latencyMs in 1..1500 && purityScore >= 80
}

// ============================================================================
// ЧАСТЬ 2: ШИНА ЦИФРОВОЙ ЛИГИ (ДЛЯ ОБЪЕДИНЕНИЯ ДО 10 СУПЕРГЕРОЕВ)
// ============================================================================

sealed class LeagueSignal {
    data class SafeTunnelEstablished(val activeNode: FreeVpnNode) : LeagueSignal()
    data class ThreatNeutralized(val heroName: String, val threatType: String, val details: String) : LeagueSignal()
    data class PacketStormStatus(val message: String, val connected: Boolean) : LeagueSignal()
    data class EmergencyFailover(val reason: String, val backupNode: FreeVpnNode?) : LeagueSignal()
    data class HeroJoinedLeague(val heroId: String, val heroName: String, val specialty: String) : LeagueSignal()
}

interface DigitalLeagueHero {
    val heroId: String
    val heroName: String
    val specialty: String
    suspend fun onLeagueSignal(signal: LeagueSignal)
}

object DigitalLeagueBus {
    private const val MAX_HEROES = 10
    private val heroes = ConcurrentHashMap<String, DigitalLeagueHero>()
    private val _signalFlow = MutableSharedFlow<LeagueSignal>(replay = 5, extraBufferCapacity = 32)
    val signalFlow: SharedFlow<LeagueSignal> = _signalFlow.asSharedFlow()

    suspend fun registerHero(hero: DigitalLeagueHero): Boolean {
        if (heroes.size >= MAX_HEROES && !heroes.containsKey(hero.heroId)) {
            return false
        }
        heroes[hero.heroId] = hero
        emitSignal(LeagueSignal.HeroJoinedLeague(hero.heroId, hero.heroName, hero.specialty))
        return true
    }

    suspend fun emitSignal(signal: LeagueSignal) {
        _signalFlow.emit(signal)
        heroes.values.forEach { hero ->
            try {
                hero.onLeagueSignal(signal)
            } catch (_: Exception) {
                // Изоляция ошибок между героями
            }
        }
    }

    fun getActiveHeroesCount(): Int = heroes.size
}

// ============================================================================
// ЧАСТЬ 3: СУПЕРСПОСОБНОСТИ ЯДРА AEGIS-V
// ============================================================================

object PurityShield {
    private val suspiciousKeywords = listOf(
        "honeypot", "mitm", "logger", "track", "spy", "127.0.0.1", "0.0.0.0", "localhost"
    )

    fun evaluateNodePurity(host: String, port: Int, rawUri: String): Int {
        var score = 100
        val lowerUri = rawUri.lowercase()
        val lowerHost = host.lowercase()

        if (suspiciousKeywords.any { lowerUri.contains(it) || lowerHost.contains(it) } || SentinelEyeHero.isBlockedHost(host)) {
            return 0
        }
        if (port !in 1..65535) return 0

        if (lowerUri.contains("security=reality") || lowerUri.contains("security=tls")) {
            score += 10
        } else if (lowerUri.startsWith("ss://") && !lowerUri.contains("2022")) {
            score -= 15
        }

        return score.coerceIn(0, 100)
    }
}

class NeuroChameleon {
    private val trustedMaskingDomains = listOf(
        "microsoft.com", "apple.com", "google.com", "cloudflare.com",
        "jsdelivr.net", "amazon.com", "bing.com", "yahoo.com"
    )

    fun calculateAdaptabilityBonus(node: FreeVpnNode, region: RegionProfile): Int {
        var bonus = 0
        val protoIndex = region.preferredProtocols.indexOf(node.protocol)
        if (protoIndex != -1) {
            bonus += (3 - protoIndex) * 25
        }
        if (trustedMaskingDomains.any { node.sni.contains(it, ignoreCase = true) }) {
            bonus += 20
        }
        if (node.port == 443) {
            bonus += 15
        }
        return bonus
    }
}

// ============================================================================
// ЧАСТЬ 4: БОЕВОЙ ДВИЖОК АГЕНТА (AEGIS-V ENGINE)
// ============================================================================

private fun decodeQueryParameters(rawQuery: String): Map<String, String> =
    rawQuery.split("&").mapNotNull { item ->
        if (item.isBlank()) return@mapNotNull null
        val key = item.substringBefore("=")
        val value = item.substringAfter("=", "")
        runCatching { URLDecoder.decode(key, "UTF-8") to URLDecoder.decode(value, "UTF-8") }.getOrNull()
    }.toMap()

class AegisVEngine(
    private val appContext: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) : DigitalLeagueHero {

    override val heroId: String = "HERO_01_AEGIS_V"
    override val heroName: String = "Aegis-V (Эгида-Свобода)"
    override val specialty: String = "Автономный поиск свободной связи и обход блокировок"

    private val chameleon = NeuroChameleon()
    private val verifiedPool = ConcurrentHashMap<String, FreeVpnNode>()

    private val _activePoolFlow = MutableStateFlow<List<FreeVpnNode>>(emptyList())
    val activePoolFlow: StateFlow<List<FreeVpnNode>> = _activePoolFlow.asStateFlow()

    private val _bestNodeFlow = MutableStateFlow<FreeVpnNode?>(null)
    val bestNodeFlow: StateFlow<FreeVpnNode?> = _bestNodeFlow.asStateFlow()

    private val _logsFlow = MutableStateFlow<List<String>>(emptyList())
    val logsFlow: StateFlow<List<String>> = _logsFlow.asStateFlow()

    @Volatile
    var currentRegion: RegionProfile = RegionProfile.STRICT_DPI

    @Volatile
    private var isPatrolling = false
    private var patrolJob: Job? = null

    private val publicVolunteerSources = listOf(
        // Прямые репозитории
        "https://raw.githubusercontent.com/barry-far/V2ray-Configs/main/Sub1.txt",
        "https://raw.githubusercontent.com/yebekhe/TVC/main/subscriptions/xray/normal/mix",
        "https://raw.githubusercontent.com/mahdibland/V2RayAggregator/master/Eternity",
        // CDN-зеркала jsDelivr (работают даже там, где заблокирован raw.githubusercontent.com!)
        "https://cdn.jsdelivr.net/gh/barry-far/V2ray-Configs@main/Sub1.txt",
        "https://cdn.jsdelivr.net/gh/yebekhe/TVC@main/subscriptions/xray/normal/mix",
        "https://cdn.jsdelivr.net/gh/mahdibland/V2RayAggregator@master/Eternity"
    )

    init {
        scope.launch {
            DigitalLeagueBus.registerHero(this@AegisVEngine)
            // Призываем всю команду Цифровой Лиги (10 / 10 героев)
            DigitalLeagueBus.registerHero(MirrorPhantomHero)
            DigitalLeagueBus.registerHero(PacketStormHero)
            DigitalLeagueBus.registerHero(CipherDnsHero)
            DigitalLeagueBus.registerHero(GhostCloakHero)
            DigitalLeagueBus.registerHero(TurboPulseHero)
            DigitalLeagueBus.registerHero(SentinelEyeHero)
            DigitalLeagueBus.registerHero(BridgeWeaverHero)
            DigitalLeagueBus.registerHero(HeartCoreHero)
        }
    }

    fun startAutonomousPatrol() {
        if (isPatrolling) return
        isPatrolling = true
        log("🦸‍♂️ Aegis-V пробудился. Запускаю автономный поиск свободных узлов...")

        patrolJob = scope.launch {
            // Restore encrypted profiles from the previous successful scan before network access.
            val cached = loadEncryptedNodeCache()
            if (cached.isNotEmpty()) {
                log("🗃️ Из защищённого кэша восстановлено профилей: ${cached.size}")
            } else {
                log("🗃️ Защищённый кэш узлов пуст.")
            }
            while (isActive && isPatrolling) {
                try {
                    logNetworkRoute()
                    val downloaded = harvestPublicConfigs()
                    if (downloaded.isNotEmpty()) saveEncryptedNodeCache(downloaded)
                    val candidates = (downloaded + cached).distinctBy { it.rawUri }.take(120)
                    log("📡 Профилей: из сети ${downloaded.size}, из кэша ${cached.size}, всего ${candidates.size}")
                    val tcpReachable = verifyNodesInParallel(candidates.take(60))
                    log("TCP-проверка: проверено ${minOf(candidates.size, 60)}, порт доступен у ${tcpReachable.size}")
                    // Never erase a previously verified pool because a blocked/empty scan returned zero.
                    if (tcpReachable.isNotEmpty()) {
                        verifiedPool.clear()
                        tcpReachable.forEach { node -> verifiedPool[node.id] = node }
                        log("Пул обновлён: ${tcpReachable.size} проверенных TCP-кандидатов.")
                    } else {
                        log("Новый пул не получен; прежние проверенные узлы сохранены.")
                    }
                    updateRankedPool()
                    log("Актуальных TCP-кандидатов в списке: ${_activePoolFlow.value.size}")
                } catch (e: Exception) {
                    log("⚠️ Ошибка сканирования: ${e.javaClass.simpleName}: ${e.localizedMessage ?: "без подробностей"}")
                }
                delay(45_000L)
            }
        }
    }

    fun stopPatrol() {
        isPatrolling = false
        patrolJob?.cancel()
        log("⏸️ Патрулирование Aegis-V приостановлено.")
    }

    private fun logNetworkRoute() {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = cm?.activeNetwork
        val caps = network?.let { cm.getNetworkCapabilities(it) }
        if (caps == null) {
            log("Сеть Android: активная сеть или её параметры недоступны.")
            return
        }
        val vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val internet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        val cellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        log("Сеть Android: VPN=${vpn}, validated=${validated}, internet=${internet}, Wi-Fi=${wifi}, cellular=${cellular}")
    }

    private fun cacheSecretKey(): SecretKey {
        val alias = "aegis-v-node-cache-v1"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun saveEncryptedNodeCache(nodes: List<FreeVpnNode>) {
        runCatching {
            val json = JSONArray()
            nodes.distinctBy { it.rawUri }.take(300).forEach { json.put(it.rawUri) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, cacheSecretKey())
            val encrypted = cipher.iv + cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
            val encoded = Base64.getEncoder().encodeToString(encrypted)
            appContext.getSharedPreferences("aegis_v_secure_cache", Context.MODE_PRIVATE)
                .edit().putString("encrypted_nodes_v1", encoded).apply()
            log("Защищённый кэш обновлён: ${minOf(nodes.size, 300)} профилей (AES-GCM).")
        }.onFailure { log("Не удалось сохранить защищённый кэш: ${it.javaClass.simpleName}") }
    }

    private fun loadEncryptedNodeCache(): List<FreeVpnNode> = runCatching {
        val encoded = appContext.getSharedPreferences("aegis_v_secure_cache", Context.MODE_PRIVATE)
            .getString("encrypted_nodes_v1", null) ?: return emptyList()
        val bytes = Base64.getDecoder().decode(encoded)
        require(bytes.size > 12) { "Invalid encrypted cache" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, cacheSecretKey(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        val json = JSONArray(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
        (0 until json.length()).mapNotNull { index ->
            parseVpnUri(json.optString(index).takeIf { it.isNotBlank() } ?: return@mapNotNull null)
        }
    }.onFailure { log("Защищённый кэш недоступен: ${it.javaClass.simpleName}") }.getOrDefault(emptyList())

    private suspend fun harvestPublicConfigs(): List<FreeVpnNode> = withContext(Dispatchers.IO) {
        // Try independent CDN hostnames in parallel so one blocked mirror cannot
        // consume its full timeout before the next source is attempted.
        suspend fun fetchAndParse(sourceUrl: String): Pair<String, List<FreeVpnNode>> {
            val host = runCatching { URI(sourceUrl).host }.getOrNull() ?: sourceUrl
            val responseBody = MirrorPhantomHero.fetchThroughStealthMirror(sourceUrl)
            if (responseBody.isNullOrBlank()) {
                log("Источник $host: ${MirrorPhantomHero.diagnosticFor(sourceUrl)}")
                return host to emptyList()
            }
            // GitHub Contents API may return JSON with a base64-encoded "content" field.
            val rawContent = runCatching {
                val json = JSONObject(responseBody)
                if (json.optString("encoding") == "base64" && json.has("content")) {
                    decodeBase64Text(json.optString("content")) ?: responseBody
                } else responseBody
            }.getOrDefault(responseBody)
            val parsed = tryDecodeBase64(rawContent.trim()).lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .mapNotNull(::parseVpnUri)
                .distinctBy { it.rawUri }
                .toList()
            log("Источник $host: ${MirrorPhantomHero.diagnosticFor(sourceUrl)}; профилей после разбора: ${parsed.size}")
            return host to parsed
        }

        suspend fun fetchSources(urls: List<String>): List<FreeVpnNode> = coroutineScope {
            urls.distinct().map { url -> async(Dispatchers.IO) { fetchAndParse(url) } }.awaitAll()
                .flatMap { (host, nodes) ->
                    log("Источник $host: найдено профилей: ${nodes.size}")
                    nodes
                }.distinctBy { it.rawUri }
        }

        val primarySources = publicVolunteerSources.flatMap { BridgeWeaverHero.expandMirrorSources(it) }.distinct()
        val discovered = fetchSources(primarySources).toMutableList()

        // The secondary CDN front doors existed but were not called by the active loader.
        // Try them before falling back to the GitHub API.
        if (discovered.isEmpty()) {
            val secondarySources = publicVolunteerSources
                .flatMap { BridgeWeaverHero.expandSecondaryMirrorSources(it) }
                .distinct()
            log("Основные источники пусты; запускаю дополнительные CDN-зеркала: ${secondarySources.size}")
            discovered.addAll(fetchSources(secondarySources))
        }

        if (discovered.isEmpty()) {
            val apiFallbacks = publicVolunteerSources
                .mapNotNull(BridgeWeaverHero::githubApiFallback)
                .distinct()
            log("CDN-зеркала не дали профилей; проверяю GitHub API: ${apiFallbacks.size}")
            discovered.addAll(fetchSources(apiFallbacks))
        }
        log("Всего найдено профилей до проверки доступности: ${discovered.size}")
        discovered.distinctBy { it.rawUri }
    }
    private fun decodeBase64Text(value: String): String? {
        val compact = value.filterNot { it.isWhitespace() }
        if (compact.isEmpty()) return null
        val normalized = compact.replace('-', '+').replace('_', '/')
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        return runCatching { String(Base64.getDecoder().decode(padded), Charsets.UTF_8) }.getOrNull()
    }

    private fun tryDecodeBase64(content: String): String {
        val trimmed = content.trim().removePrefix("\uFEFF")
        if (trimmed.contains("://") || trimmed.startsWith("{")) return trimmed
        val whole = decodeBase64Text(trimmed)
        if (!whole.isNullOrBlank() && (whole.contains("://") || whole.trimStart().startsWith("{"))) return whole
        return trimmed.lineSequence().map { line ->
            val item = line.trim()
            if (item.contains("://") || item.isEmpty()) item
            else decodeBase64Text(item)?.takeIf { it.contains("://") || it.trimStart().startsWith("{") } ?: item
        }.joinToString("\n")
    }

    private fun parseVpnUri(input: String): FreeVpnNode? {
        val uriStr = input.trim().removePrefix("\uFEFF")
        return try {
            if (uriStr.startsWith("vmess://", true)) {
                val jsonText = decodeBase64Text(uriStr.substringAfter("://").substringBefore("#")) ?: return null
                val profile = JSONObject(jsonText)
                val host = profile.optString("add").takeIf { it.isNotBlank() } ?: return null
                val port = profile.optString("port").toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
                val sni = profile.optString("sni").takeIf { it.isNotBlank() } ?: host
                val purity = PurityShield.evaluateNodePurity(host, port, uriStr)
                if (purity < 80) return null
                return FreeVpnNode("${host}_${port}_${uriStr.hashCode().toUInt().toString(16)}", uriStr,
                    VpnProtocol.VMESS, host, port, sni, profile.optString("ps").ifBlank { "VMess-$host" },
                    purityScore = purity)
            }
            val protocol = when {
                uriStr.startsWith("vless://", true) && uriStr.contains("security=reality", true) -> VpnProtocol.VLESS_REALITY
                uriStr.startsWith("vless://", true) -> VpnProtocol.VLESS_TLS
                uriStr.startsWith("trojan://", true) -> VpnProtocol.TROJAN
                uriStr.startsWith("hy2://", true) || uriStr.startsWith("hysteria2://", true) -> VpnProtocol.HYSTERIA2
                uriStr.startsWith("hysteria://", true) -> VpnProtocol.HYSTERIA
                uriStr.startsWith("tuic://", true) -> VpnProtocol.TUIC
                uriStr.startsWith("socks://", true) || uriStr.startsWith("socks5://", true) -> VpnProtocol.SOCKS
                uriStr.startsWith("anytls://", true) -> VpnProtocol.ANYTLS
                uriStr.startsWith("shadowtls://", true) || uriStr.startsWith("shadow-tls://", true) -> VpnProtocol.SHADOWTLS
                uriStr.startsWith("http://", true) || uriStr.startsWith("https://", true) -> VpnProtocol.HTTP
                uriStr.startsWith("ssh://", true) -> VpnProtocol.SSH
                uriStr.startsWith("ss://", true) -> VpnProtocol.SHADOWSOCKS
                else -> return null
            }
            if (protocol == VpnProtocol.SHADOWSOCKS && !uriStr.substringAfter("://").substringBefore("#").substringBefore("?").contains("@")) {
                val legacy = decodeBase64Text(uriStr.substringAfter("://").substringBefore("#").substringBefore("?")) ?: return null
                val endpoint = legacy.substringAfterLast("@", "")
                val hostPort = endpoint.substringAfterLast("@", "").substringAfterLast(":")
                val host = endpoint.substringAfterLast("@").substringBeforeLast(":").substringBeforeLast(":")
                val port = hostPort.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
                if (host.isBlank()) return null
                val purity = PurityShield.evaluateNodePurity(host, port, uriStr)
                if (purity < 80) return null
                return FreeVpnNode("${host}_${port}_${uriStr.hashCode().toUInt().toString(16)}", uriStr,
                    protocol, host, port, host, "SS-$host", purityScore = purity)
            }
            val normalized = uriStr.replace(Regex("(?i)^(hy2|hysteria2|hysteria|vless|trojan|tuic|socks5|socks|ss|anytls|shadowtls|shadow-tls)://"), "http://")
            val parsed = URI(normalized)
            val host = parsed.host ?: return null
            val defaultPort = when (protocol) {
                VpnProtocol.SOCKS -> 1080
                VpnProtocol.HTTP -> if (uriStr.startsWith("https://", true)) 443 else 8080
                VpnProtocol.SSH -> 22
                else -> 443
            }
            val port = parsed.port.takeIf { it in 1..65535 } ?: defaultPort
            val query = decodeQueryParameters(parsed.rawQuery.orEmpty())
            val sni = query["sni"]?.takeIf { it.isNotBlank() } ?: query["peer"]?.takeIf { it.isNotBlank() } ?: host
            val purity = PurityShield.evaluateNodePurity(host, port, uriStr)
            if (purity < 80) return null
            FreeVpnNode("${host}_${port}_${uriStr.hashCode().toUInt().toString(16)}", uriStr,
                protocol, host, port, sni, parsed.fragment ?: "${protocol.name}-$host", purityScore = purity)
        } catch (_: Exception) { null }
    }

    private suspend fun verifyNodesInParallel(candidates: List<FreeVpnNode>): List<FreeVpnNode> = coroutineScope {
        candidates.map { node ->
            async(Dispatchers.IO) {
                val ping = measureRealTcpPing(node.host, node.port, timeoutMs = 1500)
                if (ping in 1..1499) {
                    node.copy(latencyMs = ping, lastCheckedTimestamp = System.currentTimeMillis())
                } else {
                    null
                }
            }
        }.awaitAll().filterNotNull()
    }

    private fun measureRealTcpPing(host: String, port: Int, timeoutMs: Int): Long {
        return try {
            val socket = Socket()
            val elapsed = measureTimeMillis {
                socket.connect(InetSocketAddress(host, port), timeoutMs)
            }
            socket.close()
            elapsed
        } catch (_: Exception) {
            -1L
        }
    }

    private suspend fun revalidateExistingPool() {
        val iterator = verifiedPool.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val newPing = measureRealTcpPing(entry.value.host, entry.value.port, 1200)
            if (newPing <= 0L) {
                iterator.remove()
                if (_bestNodeFlow.value?.id == entry.key) {
                    log("⚡ Zero-Drop: узел ${entry.value.host} потерял стабильность. Мгновенный прыжок на резерв!")
                    val backup = verifiedPool.values.minByOrNull { it.latencyMs }
                    DigitalLeagueBus.emitSignal(LeagueSignal.EmergencyFailover("Degraded latency", backup))
                }
            } else {
                verifiedPool[entry.key] = entry.value.copy(latencyMs = newPing)
            }
        }
    }

    private suspend fun updateRankedPool() {
        val ranked = verifiedPool.values
            .filter { it.isHealthy }
            .distinctBy { it.rawUri }
            .sortedByDescending { node ->
                val chameleonBonus = chameleon.calculateAdaptabilityBonus(node, currentRegion)
                (node.purityScore + chameleonBonus) - (node.latencyMs / 5)
            }
            .take(15)

        _activePoolFlow.value = ranked

        val topNode = ranked.firstOrNull()
        if (topNode != null && topNode.id != _bestNodeFlow.value?.id) {
            _bestNodeFlow.value = topNode
            log("✅ Лучший свободный канал готов: [${topNode.protocol}] ${topNode.host}:${topNode.port} (${topNode.latencyMs} мс)")
            // A reachable TCP endpoint is only a candidate, not an established VPN tunnel.
            // SafeTunnelEstablished must only be emitted after verified packet forwarding.
        }
    }

    override suspend fun onLeagueSignal(signal: LeagueSignal) {
        when (signal) {
            is LeagueSignal.HeroJoinedLeague -> {
                log("🤝 В Цифровую Лигу вступил новый герой: ${signal.heroName} (${DigitalLeagueBus.getActiveHeroesCount()}/10)")
            }
            is LeagueSignal.ThreatNeutralized -> {
                log("🛡️ Герой ${signal.heroName} обезвредил угрозу: ${signal.threatType}.")
            }
            else -> Unit
        }
    }

    private fun log(message: String) {
        val safeMessage = HeartCoreHero.sanitizeDiagnostic(message)
        val updated = (_logsFlow.value + safeMessage).takeLast(50)
        _logsFlow.value = updated
    }
}

// ============================================================================
// ЧАСТЬ 4.2: ГЕРОЙ #02 — MIRROR-PHANTOM (ПРИЗРАК ЗЕРКАЛ И ОБХОД ЦЕНЗУРЫ)
// ============================================================================

/**
 * Герой #02: Если в стране пользователя заблокированы репозитории с ключами,
 * он использует DNS-over-HTTPS (Cloudflare/Google) и маскировку TLS-заголовков,
 * чтобы пробить цензуру и достать свежие конфигурации для Aegis-V.
 */
object MirrorPhantomHero : DigitalLeagueHero {
    override val heroId: String = "HERO_02_MIRROR_PHANTOM"
    override val heroName: String = "Mirror-Phantom (Призрак Зеркал)"
    override val specialty: String = "Загрузка подписок с резервным DNS-over-HTTPS"

    private data class DohProvider(val host: String, val ip: String)
    private val dohProviders = listOf(
        DohProvider("cloudflare-dns.com", "1.1.1.1"),
        DohProvider("dns.google", "8.8.8.8"),
        DohProvider("dns.quad9.net", "9.9.9.9")
    )
    private val diagnostics = ConcurrentHashMap<String, String>()

    fun diagnosticFor(sourceUrl: String): String {
        val host = runCatching { URI(sourceUrl).host }.getOrNull() ?: "unknown"
        return diagnostics[host] ?: "проверка ещё не завершена"
    }

    private class BootstrapDns(private val providers: List<DohProvider>) : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            runCatching { Dns.SYSTEM.lookup(hostname) }.getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?.let { return it }

            val resolved = mutableListOf<InetAddress>()
            var lastFailure: Exception? = null
            for (provider in providers) {
                try {
                    val client = OkHttpClient.Builder()
                        .dns(Dns { requested ->
                            if (requested.equals(provider.host, ignoreCase = true)) {
                                listOf(InetAddress.getByAddress(provider.host, provider.ip.split(".").map { it.toInt().toByte() }.toByteArray()))
                            } else {
                                Dns.SYSTEM.lookup(requested)
                            }
                        })
                        .connectTimeout(5, TimeUnit.SECONDS)
                        .readTimeout(5, TimeUnit.SECONDS)
                        .callTimeout(7, TimeUnit.SECONDS)
                        .build()

                    for (recordType in listOf("A", "AAAA")) {
                        val encodedName = java.net.URLEncoder.encode(hostname, "UTF-8")
                        val request = Request.Builder()
                            .url("https://${provider.host}/dns-query?name=$encodedName&type=$recordType")
                            .header("Accept", "application/dns-json")
                            .header("User-Agent", "AegisV-Android/0.3")
                            .build()
                        client.newCall(request).execute().use { response ->
                            if (!response.isSuccessful) throw IllegalStateException("DoH HTTP ${response.code}")
                            val json = JSONObject(response.body?.string().orEmpty())
                            val answers = json.optJSONArray("Answer") ?: JSONArray()
                            for (index in 0 until answers.length()) {
                                val answer = answers.optJSONObject(index) ?: continue
                                val type = answer.optInt("type")
                                val address = answer.optString("data")
                                if ((type == 1 || type == 28) && address.isNotBlank()) {
                                    runCatching { InetAddress.getByName(address) }.getOrNull()?.let { resolved.add(it) }
                                }
                            }
                        }
                    }
                    if (resolved.isNotEmpty()) return resolved.distinct()
                } catch (error: Exception) {
                    lastFailure = error
                }
            }
            throw UnknownHostException("System DNS and DoH providers failed for $hostname: ${lastFailure?.javaClass?.simpleName ?: "no address"}")
        }
    }

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(BootstrapDns(dohProviders))
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(18, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    suspend fun fetchThroughStealthMirror(targetUrl: String): String? = withContext(Dispatchers.IO) {
        val host = runCatching { URI(targetUrl).host }.getOrNull() ?: "unknown"
        if (!targetUrl.startsWith("https://", ignoreCase = true)) {
            diagnostics[host] = "отклонено: разрешён только HTTPS"
            return@withContext null
        }
        try {
            val request = Request.Builder()
                .url(targetUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0.0.0 Mobile Safari/537.36")
                .header("Accept", "application/vnd.github.raw, application/vnd.github+json, text/plain, application/octet-stream;q=0.9,*/*;q=0.5")
                .header("Accept-Encoding", "identity")
                .header("Cache-Control", "no-cache")
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    diagnostics[host] = "HTTP ${response.code}; DNS/TLS/соединение пройдены"
                    Log.w("AegisV/Mirror", "HTTP ${response.code}: $host")
                    return@withContext null
                }
                val body = response.body ?: run {
                    diagnostics[host] = "HTTP ${response.code}, пустое тело"
                    return@withContext null
                }
                if (body.contentLength() > 4L * 1024L * 1024L) {
                    diagnostics[host] = "ответ больше лимита 4 MiB"
                    return@withContext null
                }
                val output = ByteArrayOutputStream()
                body.byteStream().use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (output.size() + count > 4 * 1024 * 1024) {
                            diagnostics[host] = "ответ превысил лимит 4 MiB"
                            return@withContext null
                        }
                        output.write(buffer, 0, count)
                    }
                }
                val text = output.toString("UTF-8")
                diagnostics[host] = "HTTP ${response.code}, получено ${output.size()} байт"
                if (text.isBlank()) null else text
            }
        } catch (error: Exception) {
            val reason = "${error.javaClass.simpleName}: ${error.message.orEmpty().take(120)}"
            diagnostics[host] = "ошибка DNS/TLS/HTTPS: $reason"
            Log.w("AegisV/Mirror", "Fetch failed: $host: $reason")
            null
        }
    }

    override suspend fun onLeagueSignal(signal: LeagueSignal) {
        // Диагностика остаётся доступной; загрузка узлов выполняется до VPN-туннеля.
    }
}

// ============================================================================
// ЧАСТЬ 4.3: ГЕРОЙ #03 — PACKET-STORM (РЕАЛЬНЫЙ ДВИЖОК ТУННЕЛИРОВАНИЯ ПАКЕТОВ)
// ============================================================================

/**
 * Герой #03: Отвечает за то, чтобы пакеты из виртуальной карты Android (TUN)
 * РЕАЛЬНО уходили в интернет через выбранный сервер, а не зависали в пустоте.
 * Использует только подтверждённую нативную интеграцию sing-box/libbox.
 * Локального TCP-запасного шлюза нет: без проверки data-plane VPN не запускается.
 */
object PacketStormHero : DigitalLeagueHero {
    override val heroId: String = "HERO_03_PACKET_STORM"
    override val heroName: String = "Packet-Storm (Шторм Пакетов)"
    override val specialty: String = "Android TUN bridge and sing-box CommandServer"

    @Volatile
    private var activeBridge: SingBoxAndroidBridge? = null

    fun igniteTunnelEngine(
        singBoxJsonConfig: String,
        activeNode: FreeVpnNode,
        service: AegisVpnService,
        onStatus: (String) -> Unit
    ): Boolean {
        shutdownTunnelEngine()
        return try {
            val bridge = SingBoxAndroidBridge(service)
            bridge.start(singBoxJsonConfig)
            activeBridge = bridge
            onStatus("libbox запущен для ${activeNode.host}:${activeNode.port}")
            true
        } catch (e: Exception) {
            Log.e("AegisV/PacketStorm", "libbox failed to start for ${activeNode.host}:${activeNode.port}", e)
            onStatus("Ошибка запуска sing-box: ${e.message ?: e.javaClass.simpleName}")
            shutdownTunnelEngine()
            false
        }
    }

    fun shutdownTunnelEngine() {
        try {
            activeBridge?.close()
        } catch (_: Exception) {
        } finally {
            activeBridge = null
        }
    }

    override suspend fun onLeagueSignal(signal: LeagueSignal) {
        // Failover is handled only after a validated replacement profile is available.
    }
}

// ============================================================================
// ЧАСТЬ 4.4: ОСТАЛЬНЫЕ 7 ГЕРОЕВ ЛИГИ (КОМПЛЕКТАЦИЯ КОМАНДЫ 10 / 10)
// ============================================================================

/** Герой #04: Защита от подмены DNS провайдером через DNS-over-HTTPS */
object CipherDnsHero : DigitalLeagueHero {
    override val heroId = "HERO_04_CIPHER_DNS"
    override val heroName = "Cipher-DNS (Оракул Чистых Имён)"
    override val specialty = "Блокировка подмены DNS через зашифрованный DoH"

    suspend fun resolveCleanIp(domain: String): String? = withContext(Dispatchers.IO) {
        try {
            require(domain.matches(Regex("[A-Za-z0-9.-]{1,253}"))) { "Invalid DNS query name" }
            val url = URL("https://1.1.1.1/dns-query?name=$domain&type=A")
            val conn = (url.openConnection() as HttpsURLConnection).apply {
                setRequestProperty("Accept", "application/dns-json")
                connectTimeout = 4000
                readTimeout = 4000
            }
            if (conn.responseCode == 200) {
                val body = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
                conn.disconnect()
                val response = JSONObject(body)
                if (response.optInt("Status", -1) != 0) return@withContext null
                val answers = response.optJSONArray("Answer") ?: return@withContext null
                for (index in 0 until answers.length()) {
                    val answer = answers.optJSONObject(index) ?: continue
                    if (answer.optInt("type") == 1) {
                        val address = answer.optString("data")
                        if (address.matches(Regex("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}"))) return@withContext address
                    }
                }
                return@withContext null
            }
            conn.disconnect()
        } catch (_: Exception) {
        }
        null
    }

    override suspend fun onLeagueSignal(signal: LeagueSignal) {}
}

/** Герой #05: единая TLS-политика для поддерживаемых sing-box outbound. */
object GhostCloakHero : DigitalLeagueHero {
    override val heroId = "HERO_05_GHOST_CLOAK"
    override val heroName = "Ghost-Cloak (Плащ Невидимки)"
    override val specialty = "Проверка TLS SNI и допустимого uTLS fingerprint"

    fun applyTlsProfile(tls: JSONObject, query: Map<String, String>, fallbackSni: String) {
        val sni = query["sni"]?.takeIf { it.isNotBlank() } ?: fallbackSni
        require(sni.length <= 253 && sni.matches(Regex("[A-Za-z0-9.-]+"))) { "Invalid TLS server name" }
        tls.put("server_name", sni)
        val fingerprint = query["fp"]?.lowercase()?.takeIf {
            it in setOf("chrome", "firefox", "edge", "safari", "ios", "android", "360", "qq")
        } ?: "chrome"
        tls.put("utls", JSONObject().put("enabled", true).put("fingerprint", fingerprint))
    }

    override suspend fun onLeagueSignal(signal: LeagueSignal) {}
}

/** Герой #06: управляемый opt-in mux; не меняет поведение профилей без mux=1. */
object TurboPulseHero : DigitalLeagueHero {
    override val heroId = "HERO_06_TURBO_PULSE"
    override val heroName = "Turbo-Pulse (Ускоритель Потока)"
    override val specialty = "Опциональное мультиплексирование sing-box для VLESS"

    fun applyMultiplex(outbound: JSONObject, query: Map<String, String>, protocol: VpnProtocol) {
        if (query["mux"] != "1" || protocol != VpnProtocol.VLESS_REALITY && protocol != VpnProtocol.VLESS_TLS) return
        outbound.put("multiplex", JSONObject().apply {
            put("enabled", true)
            put("protocol", "smux")
            put("max_connections", 4)
            put("min_streams", 4)
            put("max_streams", 16)
            put("padding", true)
        })
    }

    override suspend fun onLeagueSignal(signal: LeagueSignal) {}
}

/** Герой #07: блокировка локальных, тестовых и известных мусорных endpoint до проверки. */
object SentinelEyeHero : DigitalLeagueHero {
    override val heroId = "HERO_07_SENTINEL_EYE"
    override val heroName = "Sentinel-Eye (Всевидящее Око)"
    override val specialty = "Фильтрация локальных и подозрительных VPN endpoint"

    private val blockedHosts = setOf("localhost", "localhost.localdomain", "0.0.0.0", "127.0.0.1", "::1")
    fun isBlockedHost(host: String): Boolean {
        val normalized = host.trim().trimEnd('.').lowercase()
        return normalized in blockedHosts || normalized.endsWith(".localhost") ||
            normalized.endsWith(".invalid") || normalized.endsWith(".test")
    }

    override suspend fun onLeagueSignal(signal: LeagueSignal) {}
}

/** Герой #09: генерация проверяемых CDN-кандидатов для публичных raw GitHub подписок. */
object BridgeWeaverHero : DigitalLeagueHero {
    override val heroId = "HERO_09_BRIDGE_WEAVER"
    override val heroName = "Bridge-Weaver (Ткач Мостов)"
    override val specialty = "Резервный CDN-маршрут для публичных источников подписок"

    private data class GitHubRawPath(val owner: String, val repository: String, val branch: String, val filePath: String)

    private fun parseGitHubRawPath(sourceUrl: String): GitHubRawPath? {
        val prefix = "https://raw.githubusercontent.com/"
        if (!sourceUrl.startsWith(prefix)) return null
        val path = sourceUrl.removePrefix(prefix)
        val owner = path.substringBefore("/")
        val remainder = path.substringAfter("/", "")
        val repository = remainder.substringBefore("/")
        val branchAndPath = remainder.substringAfter("/", "")
        val branch = branchAndPath.substringBefore("/")
        val filePath = branchAndPath.substringAfter("/", "")
        if (owner.isBlank() || repository.isBlank() || branch.isBlank() || filePath.isBlank()) return null
        return GitHubRawPath(owner, repository, branch, filePath)
    }

    fun expandMirrorSources(sourceUrl: String): List<String> {
        val candidates = linkedSetOf(sourceUrl)
        val path = parseGitHubRawPath(sourceUrl) ?: return candidates.toList()
        candidates.add("https://cdn.jsdelivr.net/gh/${path.owner}/${path.repository}@${path.branch}/${path.filePath}")
        return candidates.toList()
    }

    fun expandSecondaryMirrorSources(sourceUrl: String): List<String> {
        val path = parseGitHubRawPath(sourceUrl) ?: return emptyList()
        val repoPath = "${path.owner}/${path.repository}"
        return listOf(
            "https://fastly.jsdelivr.net/gh/$repoPath@${path.branch}/${path.filePath}",
            "https://gcore.jsdelivr.net/gh/$repoPath@${path.branch}/${path.filePath}",
            "https://testingcf.jsdelivr.net/gh/$repoPath@${path.branch}/${path.filePath}",
            "https://raw.githack.com/$repoPath/${path.branch}/${path.filePath}",
            "https://github.com/$repoPath/raw/refs/heads/${path.branch}/${path.filePath}"
        )
    }
    fun githubApiFallback(sourceUrl: String): String? {
        val path = parseGitHubRawPath(sourceUrl) ?: return null
        return "https://api.github.com/repos/${path.owner}/${path.repository}/contents/${path.filePath}?ref=${path.branch}"
    }
    override suspend fun onLeagueSignal(signal: LeagueSignal) {}
}

/** Герой #10: Аудитор этики, энергоэффективности и Zero-Logs */
object HeartCoreHero : DigitalLeagueHero {
    override val heroId = "HERO_10_HEART_CORE"
    override val heroName = "Heart-Core (Хранитель Чистого Сердца)"
    override val specialty = "Очистка диагностических сообщений от ключей и учётных данных"
    private val vpnUriPattern = Regex("(?i)(?:vless|trojan|ss|hy2|hysteria2)://\\S+")
    private val credentialPattern = Regex("(?i)(password|passwd|uuid|token|private[_-]?key|pbk)=([^&\\s]+)")
    private val uuidPattern = Regex("(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\\b")

    fun sanitizeDiagnostic(message: String): String =
        message.replace(vpnUriPattern, "<vpn-uri-redacted>")
            .replace(credentialPattern) { match -> match.groupValues[1] + "=<redacted>" }
            .replace(uuidPattern, "<uuid-redacted>")

    override suspend fun onLeagueSignal(signal: LeagueSignal) {
        // Diagnostic data is kept in memory only and sanitized before it reaches the UI.
    }
}

/**
 * Встроенная лаборатория самопроверки всей Лиги (10/10) в реальном времени
 */
object LeagueDiagnosticLab {
    suspend fun runFullSystemCheck(): List<String> = withContext(Dispatchers.IO) {
        val report = mutableListOf<String>()

        // 1. Проверка укомплектованности Лиги
        val count = DigitalLeagueBus.getActiveHeroesCount()
        report.add("1. Состав Цифровой Лиги: $count / 10 героев в строю.")

        // 2. Проверка Героя #04 Cipher-DNS (реальный DoH запрос)
        val resolved = CipherDnsHero.resolveCleanIp("github.com")
        report.add(
            if (resolved != null) "2. Cipher-DNS (DoH): OK (github.com -> $resolved)"
            else "2. Cipher-DNS (DoH): Сеть ограничена, переход на резервный мост"
        )

        // 3. Проверка Героя #02 Mirror-Phantom (доступность CDN-зеркала)
        val mirrorText = MirrorPhantomHero.fetchThroughStealthMirror(
            "https://cdn.jsdelivr.net/gh/barry-far/V2ray-Configs@main/Sub1.txt"
        )
        report.add(
            if (!mirrorText.isNullOrBlank()) "3. Mirror-Phantom (CDN): OK (получено ${mirrorText.length} байт ключей)"
            else "3. Mirror-Phantom (CDN): это зеркало недоступно; будут проверены альтернативные маршруты"
        )

        // 4. Проверка Героя #03 Packet-Storm (наличие нативного ядра libbox)
        val hasLibbox = try {
            Class.forName("io.nekohasekai.libbox.Libbox")
            true
        } catch (_: Exception) {
            false
        }
        val hasAndroidBridge = runCatching {
            Class.forName("com.digitalleague.aegisv.SingBoxAndroidBridge")
        }.isSuccess
        report.add(
            when {
                !hasLibbox -> "4. Packet-Storm: нативное ядро libbox не подключено; VPN недоступен"
                !hasAndroidBridge -> "4. Packet-Storm: libbox найден, но класс Android TUN bridge не загружен"
                else -> "4. Packet-Storm: libbox и Android TUN bridge найдены; создание TUN проверяется при подключении"
            }
        )

        report
    }
}

// ============================================================================
// ЧАСТЬ 4.5: ГЕНЕРАТОР БОЕВОГО КОНФИГА SING-BOX И СИСТЕМНЫЙ ANDROID VPNSERVICE
// ============================================================================

/**
 * Превращает найденный бесплатный узел (vless://, trojan://, hy2://, ss://)
 * в полноценный JSON-конфиг для ядра sing-box с маскировкой uTLS (Нейро-Хамелеон).
 */
object SingBoxConfigBuilder {
    fun buildJsonConfig(node: FreeVpnNode, tunFd: Int): String {
        val root = JSONObject()

        // sing-box 1.14 removed the legacy DNS server and TUN address fields.
        // Use the current typed DNS server schema and the unified TUN address list.
        val dns = JSONObject().apply {
            put("servers", JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "https")
                    put("tag", "google-doh")
                    put("server", "8.8.8.8")
                    put("server_port", 443)
                    put("path", "/dns-query")
                    put("detour", "proxy-out")
                    put("tls", JSONObject().apply {
                        put("enabled", true)
                        put("server_name", "dns.google")
                    })
                })
            })
            put("final", "google-doh")
        }
        root.put("dns", dns)

        // Android VpnService receives this interface through PlatformInterface.openTun().
        val inbounds = JSONArray().apply {
            put(JSONObject().apply {
                put("type", "tun")
                put("tag", "tun-in")
                put("address", JSONArray().apply {
                    put("172.19.0.1/30")
                    put("fdfe:dcba:9876::1/126")
                })
                put("mtu", 1500)
                put("auto_route", true)
                put("strict_route", true)
                put("stack", "system")
            })
        }
        root.put("inbounds", inbounds)

        // The proxy is the explicit default route for all packets captured by TUN.
        val proxyOutbound = parseNodeToSingBoxOutbound(node)
        val outbounds = JSONArray().apply {
            put(proxyOutbound)
            put(JSONObject().apply {
                put("type", "direct")
                put("tag", "direct-out")
            })
        }
        root.put("outbounds", outbounds)
        root.put("route", JSONObject().put("final", "proxy-out"))

        return root.toString(2)
    }

    private fun decodeBase64Text(value: String): String? {
        val compact = value.filterNot { it.isWhitespace() }
        if (compact.isEmpty()) return null
        val normalized = compact.replace('-', '+').replace('_', '/')
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        return runCatching { String(Base64.getDecoder().decode(padded), Charsets.UTF_8) }.getOrNull()
    }

    private fun parseNodeToSingBoxOutbound(node: FreeVpnNode): JSONObject {
        val normalizedUri = node.rawUri.replace(
            Regex("(?i)^(hy2|hysteria2|hysteria|vless|trojan|tuic|socks5|socks|ss|anytls|shadowtls|shadow-tls)://"),
            "http://"
        )
        val uri = URI(normalizedUri)
        val queryMap = decodeQueryParameters(uri.rawQuery.orEmpty())
        val uuidOrPass = uri.rawUserInfo?.let { URLDecoder.decode(it, "UTF-8") }.orEmpty()

        val out = JSONObject().apply {
            put("tag", "proxy-out")
            put("server", node.host)
            put("server_port", node.port)
        }

        when (node.protocol) {
            VpnProtocol.VLESS_REALITY, VpnProtocol.VLESS_TLS -> {
                out.put("type", "vless")
                out.put("uuid", uuidOrPass)
                out.put("flow", queryMap["flow"] ?: "")

                val tls = JSONObject().apply {
                    put("enabled", true)
                    put("server_name", node.sni)
                    // Нейро-Хамелеон: подделка TLS-отпечатка под обычный браузер Chrome
                    put("utls", JSONObject().apply {
                        put("enabled", true)
                        put("fingerprint", queryMap["fp"] ?: "chrome")
                    })
                    if (node.protocol == VpnProtocol.VLESS_REALITY) {
                        put("reality", JSONObject().apply {
                            put("enabled", true)
                            put("public_key", queryMap["pbk"] ?: "")
                            put("short_id", queryMap["sid"] ?: "")
                        })
                    }
                }
                GhostCloakHero.applyTlsProfile(tls, queryMap, node.sni)
                out.put("tls", tls)
                TurboPulseHero.applyMultiplex(out, queryMap, node.protocol)
            }
            VpnProtocol.TROJAN -> {
                out.put("type", "trojan")
                out.put("password", uuidOrPass)
                val tls = JSONObject().apply {
                    put("enabled", true)
                    put("server_name", node.sni)
                }
                GhostCloakHero.applyTlsProfile(tls, queryMap, node.sni)
                out.put("tls", tls)
            }
            VpnProtocol.HYSTERIA2 -> {
                out.put("type", "hysteria2")
                out.put("password", uuidOrPass)
                val tls = JSONObject().apply {
                    put("enabled", true)
                    put("server_name", node.sni)
                }
                GhostCloakHero.applyTlsProfile(tls, queryMap, node.sni)
                out.put("tls", tls)
            }
            VpnProtocol.VMESS -> {
                val profile = JSONObject(decodeBase64Text(node.rawUri.substringAfter("://").substringBefore("#")) ?: error("Invalid VMess payload"))
                out.put("type", "vmess")
                out.put("uuid", profile.optString("id"))
                out.put("alter_id", profile.optInt("aid", 0))
                out.put("security", profile.optString("scy").ifBlank { "auto" })
                if (profile.optString("net") == "ws") out.put("transport", JSONObject().apply {
                    put("type", "ws")
                    profile.optString("path").takeIf { it.isNotBlank() }?.let { put("path", it) }
                    profile.optString("host").takeIf { it.isNotBlank() }?.let { put("headers", JSONObject().put("Host", it)) }
                })
                if (profile.optString("tls") == "tls") out.put("tls", JSONObject().put("enabled", true).put("server_name", node.sni))
            }
            VpnProtocol.TUIC -> {
                out.put("type", "tuic")
                out.put("uuid", uri.rawUserInfo?.substringBefore(":")?.let { URLDecoder.decode(it, "UTF-8") } ?: "")
                out.put("password", uri.rawUserInfo?.substringAfter(":", "")?.let { URLDecoder.decode(it, "UTF-8") } ?: "")
                out.put("congestion_control", queryMap["congestion_control"] ?: "bbr")
                out.put("udp_relay_mode", queryMap["udp_relay_mode"] ?: "native")
                out.put("tls", JSONObject().put("enabled", true).put("server_name", node.sni))
            }
            VpnProtocol.HYSTERIA -> {
                out.put("type", "hysteria")
                out.put("up_mbps", queryMap["upmbps"]?.toIntOrNull() ?: 100)
                out.put("down_mbps", queryMap["downmbps"]?.toIntOrNull() ?: 100)
                out.put("password", uri.rawUserInfo?.let { URLDecoder.decode(it, "UTF-8") } ?: queryMap["auth"] ?: "")
                out.put("tls", JSONObject().put("enabled", true).put("server_name", node.sni))
            }
            VpnProtocol.SOCKS -> {
                out.put("type", "socks")
                out.put("version", "5")
                uri.rawUserInfo?.let { user ->
                    out.put("username", URLDecoder.decode(user.substringBefore(":"), "UTF-8"))
                    out.put("password", URLDecoder.decode(user.substringAfter(":", ""), "UTF-8"))
                }
            }
            VpnProtocol.HTTP -> {
                out.put("type", "http")
                uri.rawUserInfo?.let { user ->
                    out.put("username", URLDecoder.decode(user.substringBefore(":"), "UTF-8"))
                    out.put("password", URLDecoder.decode(user.substringAfter(":", ""), "UTF-8"))
                }
                if (node.rawUri.startsWith("https://", true)) {
                    out.put("tls", JSONObject().put("enabled", true).put("server_name", node.sni))
                }
            }
            VpnProtocol.ANYTLS -> {
                val password = uri.rawUserInfo?.let { URLDecoder.decode(it, "UTF-8") }.orEmpty()
                require(password.isNotBlank()) { "AnyTLS password is missing" }
                out.put("type", "anytls")
                out.put("password", password)
                out.put("tls", JSONObject().put("enabled", true).put("server_name", node.sni))
            }
            VpnProtocol.SHADOWTLS -> {
                val password = uri.rawUserInfo?.let { URLDecoder.decode(it, "UTF-8") }.orEmpty()
                require(password.isNotBlank()) { "ShadowTLS password is missing" }
                out.put("type", "shadowtls")
                out.put("version", queryMap["version"]?.toIntOrNull() ?: 3)
                out.put("password", password)
                out.put("tls", JSONObject().put("enabled", true).put("server_name", node.sni))
            }
            VpnProtocol.SSH -> {
                out.put("type", "ssh")
                val userInfo = uri.rawUserInfo.orEmpty()
                val username = URLDecoder.decode(userInfo.substringBefore(":"), "UTF-8")
                require(username.isNotBlank()) { "SSH username is missing" }
                out.put("user", username)
                userInfo.substringAfter(":", "").takeIf { it.isNotBlank() }?.let {
                    out.put("password", URLDecoder.decode(it, "UTF-8"))
                }
            }
            VpnProtocol.UNKNOWN -> error("Unsupported proxy protocol")
            else -> {
                val (method, password) = decodeShadowsocksCredentials(node.rawUri, uri)
                require(method.isNotBlank() && password.isNotBlank()) { "Invalid Shadowsocks method/password" }
                out.put("type", "shadowsocks")
                out.put("method", method)
                out.put("password", password)
            }
        }
        return out
    }

    private fun decodeShadowsocksCredentials(rawUri: String, parsed: URI): Pair<String, String> {
        fun decodeBase64(value: String): String {
            val normalized = value.replace('-', '+').replace('_', '/')
            val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
            return String(Base64.getDecoder().decode(padded), Charsets.UTF_8)
        }
        // SIP002: ss://BASE64(method:password)@host:port
        val encodedUserInfo = parsed.rawUserInfo?.substringBefore(':')
        if (!encodedUserInfo.isNullOrBlank()) {
            val decoded = runCatching { decodeBase64(encodedUserInfo) }.getOrNull().orEmpty()
            val method = decoded.substringBefore(':')
            val password = decoded.substringAfter(":", "")
            if (method.isNotBlank() && password.isNotBlank()) return method to password
        }
        // Legacy: ss://BASE64(method:password@host:port)#name
        val payload = rawUri.removePrefix("ss://").substringBefore("#").substringBefore("?")
        val decoded = decodeBase64(payload)
        val credentials = decoded.substringBeforeLast('@', decoded)
        val method = credentials.substringBefore(':')
        val password = credentials.substringAfter(":", "")
        require(method.isNotBlank() && password.isNotBlank()) { "Unsupported Shadowsocks URI encoding" }
        return method to password
    }
}

/**
 * НАСТОЯЩИЙ СИСТЕМНЫЙ ANDROID VPN-СЕРВИС (перехватывает трафик всего устройства)
 */
class AegisVpnService : VpnService() {

    companion object {
        const val ACTION_CONNECT = "com.digitalleague.aegisv.CONNECT"
        const val ACTION_DISCONNECT = "com.digitalleague.aegisv.DISCONNECT"
        const val EXTRA_NODE_URI = "extra_node_uri"
        const val EXTRA_NODE_HOST = "extra_node_host"
        const val EXTRA_NODE_PORT = "extra_node_port"
        const val EXTRA_NODE_SNI = "extra_node_sni"
        const val EXTRA_NODE_PROTO = "extra_node_proto"
        private const val CHANNEL_ID = "aegis_v_guardian_channel"
        private const val NOTIFICATION_ID = 1001

        @Volatile
        var isServiceRunning: Boolean = false
            private set
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                stopVpnTunnel()
                return START_NOT_STICKY
            }
            ACTION_CONNECT -> {
                val rawUri = intent.getStringExtra(EXTRA_NODE_URI) ?: return START_NOT_STICKY
                val host = intent.getStringExtra(EXTRA_NODE_HOST) ?: return START_NOT_STICKY
                val port = intent.getIntExtra(EXTRA_NODE_PORT, 443)
                val sni = intent.getStringExtra(EXTRA_NODE_SNI) ?: host
                val protoName = intent.getStringExtra(EXTRA_NODE_PROTO) ?: VpnProtocol.VLESS_REALITY.name

                val node = FreeVpnNode(
                    id = "${host}_$port",
                    rawUri = rawUri,
                    protocol = VpnProtocol.valueOf(protoName),
                    host = host,
                    port = port,
                    sni = sni,
                    remark = "ActiveShield"
                )
                startForegroundGuardian(node)
                establishRealTunnel(node)
            }
        }
        return START_STICKY
    }

    private fun startForegroundGuardian(node: FreeVpnNode) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Aegis-V Щит Свободы",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        val notification = builder
            .setContentTitle("Aegis-V: проверка запуска")
            .setContentText("VPN не активен — проверяется нативный движок")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    /**
     * Физическое создание виртуального сетевого интерфейса TUN в ядре Linux/Android
     */
    private fun establishRealTunnel(node: FreeVpnNode) {
        serviceScope.launch {
            try {
                // libbox creates the Android TUN itself through PlatformInterface.openTun().
                val singBoxJsonConfig = SingBoxConfigBuilder.buildJsonConfig(node, -1)
                val engineStarted = PacketStormHero.igniteTunnelEngine(
                    singBoxJsonConfig,
                    node,
                    this@AegisVpnService
                ) { engineMsg ->
                    serviceScope.launch {
                        DigitalLeagueBus.emitSignal(
                            LeagueSignal.PacketStormStatus(engineMsg, engineMsg.startsWith("libbox запущен"))
                        )
                    }
                }
                if (!engineStarted) {
                    stopVpnTunnel()
                    return@launch
                }
                isServiceRunning = true
            } catch (e: Exception) {
                Log.e("AegisVpnService", "sing-box start failed", e)
                stopVpnTunnel()
            }
        }
    }

    private fun stopVpnTunnel() {
        isServiceRunning = false
        PacketStormHero.shutdownTunnelEngine()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopVpnTunnel()
        serviceScope.cancel()
        super.onDestroy()
    }
}

// ============================================================================
// ЧАСТЬ 5: UI-ОБОЛОЧКА НА JETPACK COMPOSE + ТОЧКА ВХОДА AegisVApp()
// ============================================================================

private val DeepSpaceBg = Color(0xFF050814)
private val CardSurface = Color(0xFF0F172A)
private val CyanHero = Color(0xFF38BDF8)
private val GoldCape = Color(0xFFFBBF24)
private val EmeraldSafe = Color(0xFF34D399)
private val TextPrimary = Color(0xFFF8FAFC)
private val TextSecondary = Color(0xFF94A3B8)

/**
 * Готовая точка входа для вызова из MainActivity:
 * setContent { AegisVApp() }
 * Автоматически запрашивает разрешение Android VpnService.prepare() и запускает TUN.
 */
@Composable
fun AegisVApp() {
    val context = LocalContext.current
    val engine = remember(context.applicationContext) { AegisVEngine(context.applicationContext) }
    var pendingNodeToConnect by remember { mutableStateOf<FreeVpnNode?>(null) }

    // Лаунчер системного диалога Android: «Разрешить приложению создать VPN-подключение?»
    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && pendingNodeToConnect != null) {
            startAegisVpnService(context, pendingNodeToConnect!!)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            engine.stopPatrol()
        }
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            background = DeepSpaceBg,
            surface = CardSurface,
            primary = CyanHero,
            secondary = GoldCape
        )
    ) {
        AegisVMainScreen(
            engine = engine,
            onConnectTunnel = { node ->
                val prepareIntent = VpnService.prepare(context)
                if (prepareIntent != null) {
                    // Первый запуск: запрашиваем у системы разрешение на VPN
                    pendingNodeToConnect = node
                    vpnPermissionLauncher.launch(prepareIntent)
                } else {
                    // Разрешение уже есть — мгновенно поднимаем TUN-интерфейс
                    startAegisVpnService(context, node)
                }
            },
            onDisconnectTunnel = {
                val stopIntent = Intent(context, AegisVpnService::class.java).apply {
                    action = AegisVpnService.ACTION_DISCONNECT
                }
                context.startService(stopIntent)
            }
        )
    }
}

private fun startAegisVpnService(context: Context, node: FreeVpnNode) {
    val intent = Intent(context, AegisVpnService::class.java).apply {
        action = AegisVpnService.ACTION_CONNECT
        putExtra(AegisVpnService.EXTRA_NODE_URI, node.rawUri)
        putExtra(AegisVpnService.EXTRA_NODE_HOST, node.host)
        putExtra(AegisVpnService.EXTRA_NODE_PORT, node.port)
        putExtra(AegisVpnService.EXTRA_NODE_SNI, node.sni)
        putExtra(AegisVpnService.EXTRA_NODE_PROTO, node.protocol.name)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
    } else {
        context.startService(intent)
    }
}

@Composable
fun AegisVMainScreen(
    engine: AegisVEngine,
    onConnectTunnel: (FreeVpnNode) -> Unit = {},
    onDisconnectTunnel: () -> Unit = {}
) {
    val activePool by engine.activePoolFlow.collectAsState()
    val bestNode by engine.bestNodeFlow.collectAsState()
    val logs by engine.logsFlow.collectAsState()

    var isVpnConnected by remember { mutableStateOf(false) }
    var selectedNode by remember { mutableStateOf<FreeVpnNode?>(null) }
    var selectedRegion by remember { mutableStateOf(RegionProfile.STRICT_DPI) }
    var activeHeroCount by remember { mutableIntStateOf(DigitalLeagueBus.getActiveHeroesCount()) }
    var statusBanner by remember { mutableStateOf("Готов к автономному поиску свободной связи") }

    LaunchedEffect(Unit) {
        engine.startAutonomousPatrol()
        DigitalLeagueBus.signalFlow.collect { signal ->
            activeHeroCount = DigitalLeagueBus.getActiveHeroesCount()
            when (signal) {
                is LeagueSignal.SafeTunnelEstablished -> {
                    statusBanner = "Кандидат на подключение: ${signal.activeNode.host} (${signal.activeNode.latencyMs} мс)"
                }
                is LeagueSignal.EmergencyFailover -> {
                    // Не перезапускаем VPN автоматически: TCP-пинг не подтверждает
                    // совместимость профиля, а повторный старт мог обрывать рабочий туннель.
                    statusBanner = "Резервный узел найден. Выберите его вручную для переподключения."
                }
                is LeagueSignal.HeroJoinedLeague -> {
                    statusBanner = "🤝 К Лиге присоединился: ${signal.heroName}"
                }
                is LeagueSignal.ThreatNeutralized -> {
                    statusBanner = "🛡️ Угроза нейтрализована: ${signal.threatType}"
                }
                is LeagueSignal.PacketStormStatus -> {
                    isVpnConnected = signal.connected
                    statusBanner = signal.message
                }
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = DeepSpaceBg
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 32.dp)
        ) {
            item {
                LeagueHeaderBar(
                    activeHeroes = activeHeroCount.coerceAtLeast(1),
                    maxHeroes = 10
                )
            }

            item {
                HeroGuardianCard(
                    isConnected = isVpnConnected,
                    bestNode = selectedNode ?: bestNode,
                    statusText = statusBanner,
                    onToggleConnection = {
                        if (isVpnConnected) {
                            isVpnConnected = false
                            onDisconnectTunnel()
                            statusBanner = "VPN отключён"
                        } else {
                            // Запускаем реальный Android VpnService + libbox PlatformInterface.
                            // Состояние "подключено" не выставляем заранее: его подтвердит
                            // успешное создание TUN в нативном мосте.
                            val nodeToConnect = selectedNode ?: bestNode ?: activePool.firstOrNull()
                            if (nodeToConnect == null) {
                                statusBanner = "Нет доступных узлов. Дождитесь завершения поиска."
                            } else {
                                statusBanner = "Запрашиваю разрешение Android и запускаю VPN: ${nodeToConnect.host}:${nodeToConnect.port}"
                                onConnectTunnel(nodeToConnect)
                            }
                        }
                    }
                )
            }

            item {
                RegionSelectorSection(
                    selectedRegion = selectedRegion,
                    onRegionSelected = { newRegion ->
                        selectedRegion = newRegion
                        engine.currentRegion = newRegion
                        statusBanner = "Адаптация Хамелеона: ${newRegion.displayName}"
                    }
                )
            }

            item {
                PureHeartIntegrityCard()
            }

            item {
                LeagueDiagnosticCard(
                    onDiagnosticReport = { lines ->
                        statusBanner = lines.lastOrNull() ?: "Диагностика Лиги завершена"
                    }
                )
            }

            item {
                LiveEngineTerminal(logs = logs)
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "🚀 Свободные узлы (${activePool.size})",
                        color = TextPrimary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Авто-проверка TCP/Purity",
                        color = CyanHero,
                        fontSize = 12.sp
                    )
                }
            }

            if (activePool.isEmpty()) {
                item {
                    ScanningPlaceholderCard()
                }
            } else {
                items(activePool, key = { it.id }) { node ->
                    FreeNodeItemCard(
                        node = node,
                        isActive = (selectedNode ?: bestNode)?.id == node.id && isVpnConnected,
                        onSelectNode = {
                            selectedNode = node
                            statusBanner = "Переключаюсь на ${node.protocol.name}: ${node.host}:${node.port}"
                            onConnectTunnel(node)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun LeagueHeaderBar(activeHeroes: Int, maxHeroes: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(CardSurface)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Brush.linearGradient(listOf(CyanHero, GoldCape))),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Shield,
                    contentDescription = "Aegis-V",
                    tint = DeepSpaceBg,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "AEGIS-V",
                    color = TextPrimary,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 18.sp
                )
                Text(
                    text = "Цифровой Благотворитель",
                    color = GoldCape,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Surface(
            shape = CircleShape,
            color = CyanHero.copy(alpha = 0.15f),
            border = BorderStroke(1.dp, CyanHero.copy(alpha = 0.4f))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.Groups,
                    contentDescription = null,
                    tint = CyanHero,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Лига: $activeHeroes/$maxHeroes",
                    color = CyanHero,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun HeroGuardianCard(
    isConnected: Boolean,
    bestNode: FreeVpnNode?,
    statusText: String,
    onToggleConnection: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "hero_anim")
    val floatOffset by infiniteTransition.animateFloat(
        initialValue = -6f,
        targetValue = 6f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "float"
    )
    val radarAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(5000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "radar"
    )

    val ringColor by animateColorAsState(
        targetValue = if (isConnected) EmeraldSafe else CyanHero,
        label = "ring_color"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurface),
        border = BorderStroke(1.dp, ringColor.copy(alpha = 0.35f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(160.dp)
                    .offset(y = floatOffset.dp)
                    .clickable { onToggleConnection() },
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val outerRadius = size.minDimension / 2.1f

                    drawCircle(
                        color = ringColor.copy(alpha = 0.2f),
                        radius = outerRadius,
                        center = center,
                        style = Stroke(width = 3.dp.toPx())
                    )

                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                GoldCape.copy(alpha = 0.28f),
                                ringColor.copy(alpha = 0.12f),
                                Color.Transparent
                            ),
                            center = center,
                            radius = outerRadius
                        ),
                        radius = outerRadius,
                        center = center
                    )
                }

                Box(
                    modifier = Modifier
                        .size(144.dp)
                        .rotate(radarAngle)
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawArc(
                            color = ringColor,
                            startAngle = 0f,
                            sweepAngle = 65f,
                            useCenter = false,
                            style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
                        )
                    }
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = if (isConnected) Icons.Rounded.VerifiedUser else Icons.Rounded.PowerSettingsNew,
                        contentDescription = "Включить защиту",
                        tint = ringColor,
                        modifier = Modifier.size(44.dp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (isConnected) "ЩИТ АКТИВЕН" else "ЗАПУСТИТЬ",
                        color = TextPrimary,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 13.sp
                    )
                    Text(
                        text = "100% FREE",
                        color = GoldCape,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = statusText,
                color = if (isConnected) EmeraldSafe else CyanHero,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (bestNode != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Авто-выбор: ${bestNode.protocol.name} • Пинг ${bestNode.latencyMs} мс • Чистота ${bestNode.purityScore}%",
                    color = TextSecondary,
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
private fun RegionSelectorSection(
    selectedRegion: RegionProfile,
    onRegionSelected: (RegionProfile) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "🦎 Нейро-Хамелеон (Профиль вашей сети)",
            color = TextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(RegionProfile.entries) { profile ->
                val isSelected = profile == selectedRegion
                FilterChip(
                    selected = isSelected,
                    onClick = { onRegionSelected(profile) },
                    label = {
                        Text(
                            text = profile.displayName,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = CardSurface,
                        labelColor = TextSecondary,
                        selectedContainerColor = CyanHero.copy(alpha = 0.2f),
                        selectedLabelColor = CyanHero
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = isSelected,
                        borderColor = Color.White.copy(alpha = 0.1f),
                        selectedBorderColor = CyanHero
                    )
                )
            }
        }
    }
}

@Composable
private fun PureHeartIntegrityCard() {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = GoldCape.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, GoldCape.copy(alpha = 0.3f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.Favorite,
                contentDescription = "Чистое сердце",
                tint = GoldCape,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "Клятва Чистого Сердца • Zero-Logs",
                    color = GoldCape,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
                Text(
                    text = "Без слежки, рекламы и центрального рубильника. Работает автономно на благо людей.",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
            }
        }
    }
}

@Composable
private fun LeagueDiagnosticCard(onDiagnosticReport: (List<String>) -> Unit) {
    val scope = rememberCoroutineScope()
    var isRunning by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<String>>(emptyList()) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurface),
        border = BorderStroke(1.dp, CyanHero.copy(alpha = 0.3f))
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "🧪 Самопроверка Лиги (10/10)",
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
                Button(
                    onClick = {
                        if (!isRunning) {
                            isRunning = true
                            scope.launch {
                                val rep = LeagueDiagnosticLab.runFullSystemCheck()
                                results = rep
                                isRunning = false
                                onDiagnosticReport(rep)
                            }
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CyanHero, contentColor = DeepSpaceBg)
                ) {
                    Text(
                        text = if (isRunning) "Тест..." else "Запустить тест",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }

            if (results.isNotEmpty()) {
                results.forEach { line ->
                    Text(
                        text = line,
                        color = EmeraldSafe,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

@Composable
private fun LiveEngineTerminal(logs: List<String>) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(145.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF020617)),
        border = BorderStroke(1.dp, CyanHero.copy(alpha = 0.2f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "AEGIS_CORE_RADAR.LOG",
                    color = CyanHero,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "LIVE",
                    color = EmeraldSafe,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 6.dp),
                color = Color.White.copy(alpha = 0.08f)
            )
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(logs.takeLast(6)) { logLine ->
                    Text(
                        text = logLine,
                        color = TextSecondary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun FreeNodeItemCard(
    node: FreeVpnNode,
    isActive: Boolean,
    onSelectNode: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelectNode() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) CyanHero.copy(alpha = 0.14f) else CardSurface
        ),
        border = BorderStroke(
            width = 1.dp,
            color = if (isActive) CyanHero else Color.White.copy(alpha = 0.08f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = node.remark,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (isActive) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "АКТИВЕН",
                            color = DeepSpaceBg,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(GoldCape)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${node.protocol.name} • SNI: ${node.sni}",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "${node.latencyMs} мс",
                    color = if (node.latencyMs < 120) EmeraldSafe else GoldCape,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 14.sp
                )
                Text(
                    text = "Щит: ${node.purityScore}%",
                    color = CyanHero,
                    fontSize = 10.sp
                )
            }
        }
    }
}

@Composable
private fun ScanningPlaceholderCard() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CardSurface)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = CyanHero, modifier = Modifier.size(28.dp))
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "Aegis-V сканирует открытые репозитории и проверяет пинг...",
                color = TextSecondary,
                fontSize = 12.sp
            )
        }
    }
}