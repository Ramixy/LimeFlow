package io.github.dovecoteescapee.byedpi.data

import android.content.Context
import android.content.SharedPreferences
import java.io.File

enum class BypassHostKind {
    YOUTUBE,
    DISCORD,
    GENERAL,
    CUSTOM,
}

data class BypassHost(
    val domain: String,
    val kind: BypassHostKind,
    val builtin: Boolean = true,
)

data class HostSelection(
    val youtube: String,
    val discord: String,
    val extra: String,
) {
    val all: String
        get() = listOf(youtube, discord, extra).filter { it.isNotBlank() }.joinToString(" ")

    val hasYoutube: Boolean get() = youtube.isNotBlank()
    val hasDiscord: Boolean get() = discord.isNotBlank()
    val hasExtra: Boolean get() = extra.isNotBlank()
}

object BypassHosts {
    const val DISABLED_KEY = "bypass_hosts_disabled"
    const val CUSTOM_KEY = "bypass_hosts_custom"
    const val HOST_FILE_NAME = "bypass_hosts.txt"

    private val domainPattern =
        Regex("""^[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?:\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)+$""")

    /*
     * Domain lists must be initialized before `defaults`: Kotlin object fields
     * are evaluated top to bottom, and a forward reference here is null.
     *
     * YouTube + googlevideo from zapret list-google.txt plus LimeFlow extras
     * that Wi-Fi TSPU actually inspects.
     */
    private val youtubeDefaults = listOf(
        "youtube.com", "youtu.be", "youtubei.googleapis.com", "youtube.googleapis.com",
        "youtubeembeddedplayer.googleapis.com", "youtube-nocookie.com", "youtubekids.com",
        "youtubeeducation.com", "googlevideo.com", "ytimg.com", "ggpht.com",
        "googleusercontent.com", "jnn-pa.googleapis.com", "wide-youtube.l.google.com",
        "youtube-ui.l.google.com", "yt-video-upload.l.google.com", "ytimg.l.google.com",
        "video.google.com", "gvt1.com", "yt3.ggpht.com", "yt4.ggpht.com",
        "yt3.googleusercontent.com", "play.google.com",
    )

    /*
     * Discord from zapret-discord-youtube list-general.txt.
     */
    private val discordDefaults = listOf(
        "discord.com", "discord.gg", "discord.gift", "discord.gifts", "discord.media",
        "discord.dev", "discord.app", "discord.co", "discord.new", "discord.store",
        "discord.design", "discordapp.com", "discordapp.net", "discordcdn.com",
        "discordsays.com", "discordstatus.com", "discord-activities.com",
        "discordactivities.com", "discordpartygames.com", "dis.gd", "discordapp.io",
        "discordmerch.com", "discordsez.com", "discord.status",
        "stable.dl2.discordapp.net",
        "discord-attachments-uploads-prd.storage.googleapis.com",
    )

    /*
     * Remaining zapret general list plus Meta/X/Twitch that LimeFlow already scoped.
     */
    private val generalDefaults = listOf(
        "cloudflare-ech.com", "encryptedsni.com", "cloudflareaccess.com",
        "cloudflareapps.com", "cloudflarebolt.com", "cloudflareclient.com",
        "cloudflareinsights.com", "cloudflareok.com", "cloudflarepartners.com",
        "cloudflareportal.com", "cloudflarepreview.com", "cloudflareresolve.com",
        "cloudflaressl.com", "cloudflarestatus.com", "cloudflarestorage.com",
        "cloudflarestream.com", "cloudflaretest.com", "cloudfront.net",
        "frankerfacez.com", "ffzap.com", "betterttv.net", "7tv.app", "7tv.io",
        "localizeapi.com", "klipy.com", "live-video.net",
        "twitch.tv", "ttvnw.net", "jtvnw.net",
        "instagram.com", "cdninstagram.com", "facebook.com", "fbcdn.net", "whatsapp.net",
        "x.com", "twitter.com", "twimg.com", "t.co", "rutracker.org",
    )

    val defaults: List<BypassHost> = buildList {
        youtubeDefaults.forEach { add(BypassHost(it, BypassHostKind.YOUTUBE)) }
        discordDefaults.forEach { add(BypassHost(it, BypassHostKind.DISCORD)) }
        generalDefaults.forEach { add(BypassHost(it, BypassHostKind.GENERAL)) }
    }

    val defaultSelection: HostSelection = selection(emptySet(), emptyList())

    fun normalize(raw: String): String? {
        val host = raw.trim().lowercase()
            .removePrefix("https://")
            .removePrefix("http://")
            .substringBefore('/')
            .substringBefore(':')
            .removePrefix("www.")
        if (host.isEmpty() || !domainPattern.matches(host)) return null
        return host
    }

    fun catalog(preferences: SharedPreferences): List<Pair<BypassHost, Boolean>> {
        val disabled = preferences.getStringSet(DISABLED_KEY, emptySet()).orEmpty()
        val custom = customHosts(preferences)
        return (defaults + custom).distinctBy { it.domain }.map { host ->
            host to (host.domain !in disabled)
        }
    }

    fun selection(preferences: SharedPreferences): HostSelection {
        val disabled = preferences.getStringSet(DISABLED_KEY, emptySet()).orEmpty()
        return selection(disabled, customHosts(preferences))
    }

    fun selection(
        disabled: Set<String>,
        custom: List<BypassHost> = emptyList(),
    ): HostSelection {
        val enabled = (defaults + custom).filter { it.domain !in disabled }
        return HostSelection(
            youtube = join(enabled.filter { it.kind == BypassHostKind.YOUTUBE }),
            discord = join(enabled.filter { it.kind == BypassHostKind.DISCORD }),
            extra = join(enabled.filter {
                it.kind == BypassHostKind.GENERAL || it.kind == BypassHostKind.CUSTOM
            }),
        )
    }

    fun setEnabled(preferences: SharedPreferences, domain: String, enabled: Boolean) {
        val disabled = preferences.getStringSet(DISABLED_KEY, emptySet()).orEmpty().toMutableSet()
        if (enabled) disabled.remove(domain) else disabled.add(domain)
        persistDisabled(preferences, disabled)
    }

    fun setAllEnabled(preferences: SharedPreferences, enabled: Boolean) {
        val disabled = if (enabled) {
            emptySet()
        } else {
            catalog(preferences).map { it.first.domain }.toSet()
        }
        persistDisabled(preferences, disabled)
    }

    fun setKindEnabled(preferences: SharedPreferences, kind: BypassHostKind, enabled: Boolean) {
        val disabled = preferences.getStringSet(DISABLED_KEY, emptySet()).orEmpty().toMutableSet()
        catalog(preferences)
            .map { it.first }
            .filter { it.kind == kind || (kind == BypassHostKind.GENERAL && it.kind == BypassHostKind.CUSTOM) }
            .forEach { host ->
                if (enabled) disabled.remove(host.domain) else disabled.add(host.domain)
            }
        persistDisabled(preferences, disabled)
    }

    fun enabledCount(preferences: SharedPreferences): Int =
        catalog(preferences).count { it.second }

    private fun persistDisabled(preferences: SharedPreferences, disabled: Set<String>) {
        preferences.edit().putStringSet(DISABLED_KEY, HashSet(disabled)).apply()
    }

    fun addCustom(preferences: SharedPreferences, raw: String): BypassHost? {
        val domain = normalize(raw) ?: return null
        if (defaults.any { it.domain == domain } || customHosts(preferences).any { it.domain == domain }) {
            setEnabled(preferences, domain, true)
            return BypassHost(domain, kindOf(domain), builtin = defaults.any { it.domain == domain })
        }
        val hosts = customHosts(preferences) + BypassHost(domain, BypassHostKind.CUSTOM, builtin = false)
        preferences.edit().putString(CUSTOM_KEY, hosts.joinToString("\n") { it.domain }).apply()
        setEnabled(preferences, domain, true)
        return BypassHost(domain, BypassHostKind.CUSTOM, builtin = false)
    }

    fun removeCustom(preferences: SharedPreferences, domain: String) {
        val hosts = customHosts(preferences).filterNot { it.domain == domain }
        val disabled = preferences.getStringSet(DISABLED_KEY, emptySet()).orEmpty().toMutableSet()
        disabled.remove(domain)
        preferences.edit()
            .putString(CUSTOM_KEY, hosts.joinToString("\n") { it.domain })
            .putStringSet(DISABLED_KEY, HashSet(disabled))
            .apply()
    }

    fun writeHostFile(context: Context, preferences: SharedPreferences): File {
        val file = File(context.filesDir, HOST_FILE_NAME)
        val lines = catalog(preferences).map { (host, enabled) ->
            val mark = if (enabled) "" else "#"
            "$mark${host.domain}"
        }
        file.writeText(lines.joinToString("\n", postfix = "\n"))
        return file
    }

    private fun customHosts(preferences: SharedPreferences): List<BypassHost> =
        preferences.getString(CUSTOM_KEY, "").orEmpty()
            .lineSequence()
            .mapNotNull { normalize(it) }
            .distinct()
            .map { BypassHost(it, BypassHostKind.CUSTOM, builtin = false) }
            .toList()

    private fun kindOf(domain: String): BypassHostKind =
        defaults.firstOrNull { it.domain == domain }?.kind ?: BypassHostKind.CUSTOM

    private fun join(hosts: List<BypassHost>): String =
        hosts.map { it.domain }.distinct().joinToString(" ")
}
