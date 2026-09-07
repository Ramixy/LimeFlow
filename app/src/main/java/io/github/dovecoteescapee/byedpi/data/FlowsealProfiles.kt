package io.github.dovecoteescapee.byedpi.data

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

enum class ProfileKind {
    YOUTUBE,
    DISCORD,
    UNIVERSAL,
    OPERATOR,
    GENERAL,
    CUSTOM,
}

data class FlowsealProfile(
    val id: String,
    val name: String,
    val method: String,
    val description: String,
    val arguments: String,
    val custom: Boolean = false,
    val kind: ProfileKind = ProfileKind.GENERAL,
    val badge: String = "",
)

object FlowsealProfiles {
    /*
     * Domain scopes are passed inline as `-H:"a b c"`. ByeDPI's ftob() treats a leading
     * ':' as a literal string instead of a file path, so no asset extraction is needed.
     * Matching is suffix based, so a bare apex domain also covers its subdomains.
     *
     * Every TCP desync group carries `-H`. The engine then appends an empty passthrough
     * group for everything else, so unselected sites are not desynced.
     */
    private fun hostFlag(hosts: String): String =
        if (hosts.isBlank()) "" else "-H:\"$hosts\" "

    private fun scopedHostFlag(hosts: String): String =
        hostFlag(hosts.ifBlank { "_limeflow.invalid" })

    /*
     * A real QUIC Initial packet used as the UDP decoy. The stock engine only repeats the
     * client's own datagram, which current TSPU builds recognise; a genuine-looking Initial
     * with a foreign connection ID is what community configs settled on for QUIC/443.
     * Single quotes keep the backslashes intact through shellSplit().
     */
    private const val quicFake =
        "-l':\\xC2\\x00\\x00\\x00\\x01\\x14\\x2E\\xE3\\xE3\\x5F\\x6B\\xBB\\x23\\xA8\\xE6" +
            "\\x5D\\xA9\\x78\\x21\\xCF\\xC2\\x72\\x4C\\x8F\\xC4\\x5E\\x14\\x00\\x00\\x00" +
            "\\x00\\xC5\\x00\\x00\\x00\\x00\\x4C\\x00\\xA7\\x00\\x00\\x00\\x00\\x00\\x00" +
            "\\x44\\x00\\x00\\x80\\x00\\x00\\x00\\x0D\\xFC\\xFA\\x1D\\xCD\\x73\\xBA\\x2A" +
            "\\x90\\x93\\xB3\\xEE\\xF7\\x43\\xC5\\x85\\xDA\\xFF\\x45\\x3C\\x00\\x00\\x00" +
            "\\x00\\x00\\x00\\x7C\\x00\\x9B\\x00\\xF6\\x00\\x00\\xDD\\x00\\x00\\x00\\x00" +
            "\\x00\\x00\\x00\\x00\\x00\\x59\\xA8\\xE4\\x00\\x00\\x00\\x00\\x00\\x00\\x00" +
            "\\x00\\x7B\\x00\\x0F\\x00\\x00\\x00\\x48\\x4E\\x00\\x00\\x00\\x06\\xF3\\x00" +
            "\\x00\\x00\\x00\\xD9\\x5A\\x00\\x00\\x00\\x00\\x00\\x00\\x00\\x00\\x00\\x00" +
            "\\x00\\x00\\x00\\x00\\x00\\x00\\x00'"

    /*
     * A timeout is required for the torst trigger to ever fire, since it is what turns a
     * silent drop into a detectable event.
     *
     * Deliberately no -L: despite what byedpi/README.md still claims, main.c parses it as
     * the letters s/o/n rather than a level 0-3, so "-L1" is rejected outright and the proxy
     * refuses to start. Its only relevant mode, 's', sorts groups by trigger count and would
     * break the positional `-An` cascade below anyway.
     */
    private const val globals = "-T4"

    /*
     * QUIC on 443 plus Discord voice ranges. `-J`/`-G` are the junk train (Jc / Jmin-Jmax).
     * Voice stays 50000-50100: a 50000-65535 sweep stalled unrelated UDP and broke tests.
     * UDP groups stay port-scoped: the SOCKS UDP hook does not read TLS SNI.
     * quicFakePayload/voiceFake override the decoys: without them the engine repeats
     * the client datagram (voice) or uses the built-in fake.
     */
    private fun udpGroups(
        quicFakes: Int,
        voiceFakes: Int,
        junkCount: Int = 8,
        junkMin: Int = 64,
        junkMax: Int = 320,
        hosts: HostSelection = BypassHosts.defaultSelection,
        quicFakePayload: String = quicFake,
        voiceFake: String = "",
    ): String {
        val quicOn = hosts.hasYoutube || hosts.hasExtra || hosts.hasDiscord
        val quicJunk = if (quicOn) junkCount else 0
        val quicCount = if (quicOn) quicFakes else 0
        val voiceFakeFlag = if (voiceFake.isBlank()) "" else "-l':$voiceFake' "
        val voice = if (hosts.hasDiscord) {
            "-Ku -V19294-19344 ${voiceFakeFlag}-a$voiceFakes -An " +
                "-Ku -V50000-50100 ${voiceFakeFlag}-a$voiceFakes -An "
        } else {
            "-Ku -V19294-19344 -a0 -An " +
                "-Ku -V50000-50100 -a0 -An "
        }
        return "-Ku -V443 -R1-8 -J$quicJunk -G$junkMin-$junkMax $quicFakePayload -a$quicCount -An " +
            voice
    }

    private fun scoped(
        youtube: String,
        discord: String,
        generic: String,
        retry: String,
        hosts: HostSelection,
    ): String {
        val youtubeGroup = "-Kt,h ${scopedHostFlag(hosts.youtube)}-R1-8 $youtube -An "
        val discordGroup = "-Kt,h ${scopedHostFlag(hosts.discord)}$discord -An "
        val extraGroup = "-Kt,h ${scopedHostFlag(hosts.extra)}$generic -An "
        val retryHosts = hosts.all.ifBlank { "_limeflow.invalid" }
        return youtubeGroup + discordGroup + extraGroup +
            "-Kt,h ${hostFlag(retryHosts)}-At,r,s $retry"
    }

    private fun profileArgs(
        youtube: String,
        discord: String,
        generic: String,
        retry: String,
        quicFakes: Int = 11,
        voiceFakes: Int = 6,
        junkCount: Int = 8,
        junkMin: Int = 64,
        junkMax: Int = 320,
        hosts: HostSelection = BypassHosts.defaultSelection,
        quicFakePayload: String = quicFake,
        voiceFake: String = "",
    ) = "$globals ${udpGroups(quicFakes, voiceFakes, junkCount, junkMin, junkMax, hosts, quicFakePayload, voiceFake)}" +
        scoped(youtube, discord, generic, retry, hosts)

    /*
     * Flat chains still carry `-H` of the enabled host list so unselected sites pass through.
     *
     * `-S` (md5sig) is deliberately absent everywhere: Android kernels are built without
     * CONFIG_TCP_MD5SIG, and desync.c aborts the whole fake stage when setsockopt fails,
     * so it silently disabled the fake packet rather than protecting it.
     */
    private val flatBase = listOf(
        "-Qr -f-200 -s3:5+sm -a1 -As -d1 -s4+sm -s8+sh -f-300 -d6+sh -a1 -At,r,s -o2 -f-30 -As -r5 -Mh -r6+sh -f-250 -s2:7+s -s3:6+sm -a1 -At,r,s -s3:5+sm -s6+s -s7:9+s -q30+sm -a1",
        "-d1 -d3+s -s6+s -d9+s -s12+s -d15+s -s20+s -d25+s -s30+s -d35+s -r1+s -a1 -As -d1 -d3+s -s6+s -d9+s -s12+s -d15+s -s20+s -d25+s -s30+s -d35+s -a1",
        "-q2 -s2 -s3+s -r3 -s4 -r4 -s5+s -r5+s -s6 -s7+s -r8 -s9+s -Qr -Mh,d,r -a1 -At,r -s2+s -r2 -d2 -s3 -r3 -r4 -s4 -d5+s -r5 -d6 -s7+s -d7 -a1",
        "-o1 -d1 -a1 -At,r,s -s1 -d1 -s5+s -s10+s -s15+s -s20+s -r1+s -a1 -As -s1 -d1 -s5+s -s10+s -s15+s -s20+s -a1",
        "-d1+s -s50+s -a1 -As -f20 -r2+s -a1 -At -d2 -s1+s -s5+s -s10+s -s15+s -s25+s -s35+s -s50+s -s60+s -a1",
        "-d1 -s1 -q1 -a1 -Ar -s5 -o1+s -d3+s -s6+s -d9+s -s12+s -d15+s -s20+s -d25+s -s30+s -d35+s -a1",
        "-d1 -s1+s -d3+s -s6+s -d9+s -s12+s -d15+s -s20+s -d25+s -s30+s -d35+s -a1",
        "-d1 -s1+s -d1+s -s3+s -d6+s -s12+s -d14+s -s20+s -d24+s -s30+s -a1",
        "-o1 -a1 -At,r,s -f-1 -a1 -Ar,s -o1 -a1 -At -r1+s -f-1 -t6 -a1",
        "-d1 -s1+s -s3+s -s6+s -s9+s -s12+s -s15+s -s20+s -s30+s -a1",
        "-d1 -d3+s -s6+s -d6+s -s7+s -d8+s -s10+s -a1 -t12 -At,s -r3",
        "-f-1 -Qr -s1+sm -d3+s -s5+sm -o2 -a1 -As -r1+s -d8+s -a1",
        "-r-1+s -o20+sm -s3:7+sm -d5:3+sm -f300+s -Qr -f-1 -a1",
        "-o2 -O4 -s1 -q1 -a1 -Ar -s5 -o1+s -f1+s -r20+s -a1",
        "-Qr -f-1 -t8 -s1+s -d3+s -a1",
        "-r5+s -s25+s -a1 -At,r,s -s50 -r5+s -s50+s -a1",
        "-d1 -d3+s -s6+s -d9+s -s20+s -d25+s -s30+s -a1",
        "-d9+s -q20+s -s25+s -t5 -a1 -At,r,s -r1+h -a1",
        "-q1+s -s29+s -s30+s -s14+s -o5+s -f-1 -a1",
        "-d1 -s1+s -r1+s -e1 -m1 -o1+s -f-1 -t2 -a1",
        "-d1 -o1 -a1 -Ar -o1 -a1 -At -f-1 -r1+s -a1",
    )

    private fun flat(
        index: Int,
        quicFakes: Int = 11,
        voiceFakes: Int = 6,
        hosts: HostSelection = BypassHosts.defaultSelection,
    ): String {
        val host = scopedHostFlag(hosts.all)
        return "$globals ${udpGroups(quicFakes, voiceFakes, hosts = hosts)}" +
            "-Kt,h $host${flatBase[index]}"
    }

    /*
     * Building blocks for the scoped profiles. These must stay free of `-A`: it opens a
     * new option group, and a group started here would inherit none of the `-K`/`-H`
     * filters from scoped(), so the chain would leak onto every domain.
     */
    private const val ytWifi =
        "-Qr -nwww.google.com -nwww.gstatic.com -nfonts.gstatic.com " +
            "-f-1 -t5 -s1+s -d3+s -s6+s -d9+s -s12+s -d15+s -s20+s -d25+s -s30+s -r1+s"
    private const val ytOob = "-o1 -o25+s -r1+s"
    /*
     * Discord gateway from the last working 1.1.7 chain: camouflage SNI, SNI split,
     * OOB and disorder. No -S (no TCP MD5SIG on Android), no ozon/`-t6` flood.
     */
    private const val dcFakeChain =
        "-Qr -nvk.com -nwww.google.com -f-204 -s1:5+sm -o2 -d1 -Mh,d"
    private const val dcOob = "-o2 -r3+s -Qr -nwww.google.com -f-1 -t8"
    private const val genericOob = "-s1 -q1 -o1+s -r1+s"
    private const val genericSplit = "-Qr -f-1 -t8 -s1+s -d3+s -o1"
    private const val retryDeep = "-o1 -d1 -s3+sm -r1+s -Qr -f-1 -t6"
    private const val retryOob = "-o1 -r-5+se -Qr -f-1"

    fun builtins(hosts: HostSelection = BypassHosts.defaultSelection): List<FlowsealProfile> = listOf(
        FlowsealProfile(
            "limeflow_universal",
            "LIMEFLOW UNIVERSAL",
            "scoped QUIC + adaptive TLS",
            "Отдельные цепочки для YouTube, Discord и выбранных сайтов. Остальной трафик без desync",
            profileArgs(
                youtube = ytWifi,
                discord = dcFakeChain,
                generic = genericOob,
                retry = retryDeep,
                quicFakes = 12,
                voiceFakes = 8,
                hosts = hosts,
            ),
            kind = ProfileKind.UNIVERSAL,
            badge = "Все сервисы",
        ),
        FlowsealProfile(
            "limeflow_youtube",
            "LIMEFLOW YOUTUBE",
            "Wi-Fi fake TLS + QUIC junk",
            "YouTube на домашнем Wi-Fi и LTE: случайный ClientHello и junk-поезд на QUIC 443",
            profileArgs(
                youtube = ytWifi,
                discord = dcOob,
                generic = genericSplit,
                retry = retryOob,
                quicFakes = 16,
                junkCount = 10,
                junkMin = 64,
                junkMax = 384,
                hosts = hosts,
            ),
            kind = ProfileKind.YOUTUBE,
            badge = "Wi-Fi",
        ),
        FlowsealProfile(
            "limeflow_amnezia",
            "LIMEFLOW AMNEZIA",
            "junk train Jc + QUIC fake",
            "Локальный junk-поезд Jc/Jmin–Jmax, как идея AmneziaWG — без шифрования Amnezia",
            profileArgs(
                youtube = ytWifi,
                discord = dcFakeChain,
                generic = "-Qr -f-1 -t6 -s1+s -d3+s -o1",
                retry = retryDeep,
                quicFakes = 8,
                voiceFakes = 6,
                junkCount = 10,
                junkMin = 80,
                junkMax = 400,
                hosts = hosts,
            ),
            kind = ProfileKind.UNIVERSAL,
            badge = "Junk",
        ),
        /*
         * Порты стратегий zapret-discord-youtube 1.10.0 (winws) на движок byedpi.
         * Один в один они не переносятся (seqovl-pattern, badseq/ts-fooling и
         * repeats в byedpi отсутствуют), поэтому взяты эквиваленты: fake стадия,
         * multisplit/multidisorder цепочки, fake-копия ClientHello с подменой
         * хоста (-Qo -n<домен>, аналог hostfakesplit) и короткий fake (-Qm=100,
         * аналог stun.bin). Хосты берутся из тех же конфигов: ya.ru, ozon.ru,
         * www.google.com.
         */
        FlowsealProfile(
            "limeflow_z_alt",
            "ZAPRET ALT",
            "fake + fakedsplit",
            "Порт general (ALT) 1.10.0: fake с рандомизацией и подменой SNI на google, разрез и disorder, как в fakedsplit",
            profileArgs(
                youtube = "-Qr -nwww.google.com -f-1 -t5 -s1+s -d1+s -r1+s",
                discord = "-Qr -nvk.com -nwww.google.com -f-204 -s1:5+sm -o2 -d1 -Mh,d",
                generic = "-Qr -nwww.google.com -f-1 -t5 -s1+s -d1+s",
                retry = retryDeep,
                quicFakes = 10,
                voiceFakes = 6,
                hosts = hosts,
            ),
            kind = ProfileKind.UNIVERSAL,
            badge = "zapret",
        ),
        FlowsealProfile(
            "limeflow_z_alt2",
            "ZAPRET ALT2",
            "split-pos 2 + sni",
            "Порт general (ALT2): разрез на 2-м байте плюс на границе SNI, multisplit поверх fake",
            profileArgs(
                youtube = "-Qr -nwww.google.com -f-1 -t5 -s2 -s1+s -d3+s",
                discord = "-Qr -nvk.com -nwww.google.com -f-204 -s2 -s1:5+sm -d1",
                generic = "-Qr -nwww.google.com -f-1 -t5 -s2 -s1+s",
                retry = retryDeep,
                quicFakes = 10,
                voiceFakes = 6,
                hosts = hosts,
            ),
            kind = ProfileKind.YOUTUBE,
            badge = "ALT2",
        ),
        FlowsealProfile(
            "limeflow_z_alt3",
            "ZAPRET ALT3 YA",
            "hostfakesplit ya.ru",
            "Порт general (ALT3): fake-копия настоящего ClientHello с хостом ya.ru вместо реального",
            profileArgs(
                youtube = "-Qo -nya.ru -f-1 -t5 -s1+s -d3+s -r1+s",
                discord = "-Qo -nya.ru -f-204 -s1:5+sm -o2 -d1 -Mh,d",
                generic = "-Qo -nya.ru -f-1 -t5 -s1+s",
                retry = retryDeep,
                quicFakes = 10,
                voiceFakes = 6,
                hosts = hosts,
            ),
            kind = ProfileKind.UNIVERSAL,
            badge = "ya.ru",
        ),
        FlowsealProfile(
            "limeflow_z_alt4",
            "ZAPRET MULTISPLIT",
            "fake + multisplit",
            "Порт general (ALT4): fake с частым разрезом по всей длине ClientHello",
            profileArgs(
                youtube = "-Qr -nwww.google.com -f-1 -t5 -s1+s -s3+s -s6+s -d9+s -s12+s -r1+s",
                discord = "-Qr -nvk.com -nwww.google.com -f-204 -s1:5+sm -o2 -d1",
                generic = "-Qr -nwww.google.com -f-1 -t5 -s1+s -s3+s -s6+s",
                retry = retryDeep,
                quicFakes = 10,
                voiceFakes = 6,
                hosts = hosts,
            ),
            kind = ProfileKind.YOUTUBE,
            badge = "Мульти",
        ),
        FlowsealProfile(
            "limeflow_z_alt7",
            "ZAPRET SNIEXT",
            "split 2 + sniext",
            "Порт general (ALT7): разрез на 2-м байте, на границе SNI и посреди хоста",
            profileArgs(
                youtube = "-Qr -nwww.google.com -f-1 -t5 -s2 -s1+s -d1+m -r1+s",
                discord = "-Qr -nvk.com -nwww.google.com -f-204 -s2 -s1:5+sm -d1",
                generic = "-Qr -nwww.google.com -f-1 -t5 -s2 -s1+s",
                retry = retryDeep,
                quicFakes = 10,
                voiceFakes = 6,
                hosts = hosts,
            ),
            kind = ProfileKind.YOUTUBE,
            badge = "SNIext",
        ),
        FlowsealProfile(
            "limeflow_z_alt9",
            "ZAPRET OZON",
            "hostfakesplit ozon.ru",
            "Порт general (ALT9): fake-копия ClientHello с хостом ozon.ru, как в hostfakesplit",
            profileArgs(
                youtube = "-Qo -nozon.ru -f-1 -t5 -s1+s -d3+s -r1+s",
                discord = "-Qo -nozon.ru -f-204 -s1:5+sm -o2 -d1 -Mh,d",
                generic = "-Qo -nozon.ru -f-1 -t5 -s1+s",
                retry = retryDeep,
                quicFakes = 10,
                voiceFakes = 6,
                hosts = hosts,
            ),
            kind = ProfileKind.UNIVERSAL,
            badge = "Ozon",
        ),
        FlowsealProfile(
            "limeflow_z_auto",
            "ZAPRET AUTO 1.10",
            "fake + multidisorder",
            "Порт general (FAKE TLS AUTO) 1.10.0: multidisorder с разрезами на 1-м байте и посреди SLD",
            profileArgs(
                youtube = "-Qr -nwww.google.com -f-1 -t5 -d1+s -d1+m -d3+s -d9+s -r1+s",
                discord = "-Qr -nvk.com -nwww.google.com -f-204 -d1+m -s1:5+sm -o2 -d1",
                generic = "-Qr -nwww.google.com -f-1 -t5 -d1+s -d1+m",
                retry = retryDeep,
                quicFakes = 10,
                voiceFakes = 6,
                hosts = hosts,
            ),
            kind = ProfileKind.YOUTUBE,
            badge = "Auto",
        ),
        FlowsealProfile(
            "limeflow_z_short",
            "ZAPRET SHORTFAKE",
            "short fake (stun)",
            "Порт ALT10/SIMPLE FAKE ALT2: короткий fake на 100 байт вместо полного ClientHello, как stun.bin",
            profileArgs(
                youtube = "-Qr,m=100 -nwww.google.com -f-1 -t5 -s1+s -d3+s",
                discord = "-Qr,m=100 -nvk.com -nwww.google.com -f-204 -s1:5+sm -d1",
                generic = "-Qr,m=100 -nwww.google.com -f-1 -t5 -s1+s",
                retry = retryDeep,
                quicFakes = 10,
                voiceFakes = 6,
                hosts = hosts,
            ),
            kind = ProfileKind.GENERAL,
            badge = "Короткий",
        ),
        /*
         * ZF-серия: fake-пакеты — дословные бинари zapret-discord-youtube 1.10.0
         * (FakePayloads), голосовые группы получают ACTIVE_DISCORD_UDP вместо
         * повтора клиентской датаграммы. AUTOTTTL подбирает TTL fake сам.
         */
        FlowsealProfile(
            "limeflow_zf_google",
            "ZAPRET GFAKE",
            "google CH fake 681b",
            "Полный Chrome-ClientHello из zapret на TCP, настоящий QUIC Initial на 443 и Discord-fake на голосе",
            profileArgs(
                youtube = "-Qr,d -l':${FakePayloads.FAKE_TLS_GOOGLE}' -t5 -s1+s -d3+s -r1+s",
                discord = "-Qr,d -l':${FakePayloads.FAKE_TLS_GOOGLE}' -t5 -s1:5+sm -o2 -d1 -Mh,d",
                generic = "-Qr,d -l':${FakePayloads.FAKE_TLS_GOOGLE}' -t5 -s1+s -d3+s",
                retry = retryDeep,
                quicFakes = 10,
                voiceFakes = 8,
                hosts = hosts,
                quicFakePayload = "-l':${FakePayloads.FAKE_QUIC_GOOGLE}'",
                voiceFake = FakePayloads.FAKE_DISCORD_UDP,
            ),
            kind = ProfileKind.UNIVERSAL,
            badge = "GFake",
        ),
        FlowsealProfile(
            "limeflow_zf_maxru",
            "ZAPRET MFAKE",
            "max.ru CH fake 664b",
            "ClientHello с SNI max.ru из zapret на TCP, QUIC Initial на 443, Discord-fake на голосе",
            profileArgs(
                youtube = "-Qr,d -l':${FakePayloads.FAKE_TLS_MAXRU}' -t5 -s1+s -d3+s -r1+s",
                discord = "-Qr,d -l':${FakePayloads.FAKE_TLS_MAXRU}' -t5 -s1:5+sm -o2 -d1 -Mh,d",
                generic = "-Qr,d -l':${FakePayloads.FAKE_TLS_MAXRU}' -t5 -s1+s -d3+s",
                retry = retryDeep,
                quicFakes = 10,
                voiceFakes = 8,
                hosts = hosts,
                quicFakePayload = "-l':${FakePayloads.FAKE_QUIC_GOOGLE}'",
                voiceFake = FakePayloads.FAKE_DISCORD_UDP,
            ),
            kind = ProfileKind.UNIVERSAL,
            badge = "MFake",
        ),
        FlowsealProfile(
            "limeflow_zf_4pda",
            "ZAPRET 4PFAKE",
            "4pda CH fake 284b",
            "Компактный ClientHello (284 байта) из zapret — быстрый fake для слабых сетей и устройств",
            profileArgs(
                youtube = "-Qr,d -l':${FakePayloads.FAKE_TLS_4PDA}' -t5 -s1+s -d3+s -r1+s",
                discord = "-Qr,d -l':${FakePayloads.FAKE_TLS_4PDA}' -t5 -s1:5+sm -o2 -d1 -Mh,d",
                generic = "-Qr,d -l':${FakePayloads.FAKE_TLS_4PDA}' -t5 -s1+s -d3+s",
                retry = retryDeep,
                quicFakes = 8,
                voiceFakes = 6,
                hosts = hosts,
                quicFakePayload = "-l':${FakePayloads.FAKE_QUIC_GOOGLE}'",
                voiceFake = FakePayloads.FAKE_DISCORD_UDP,
            ),
            kind = ProfileKind.UNIVERSAL,
            badge = "4PFake",
        ),
        FlowsealProfile(
            "limeflow_zf_voice",
            "ZAPRET VOICE",
            "voice-first + fakes",
            "Голос Discord в приоритете: 12 fake с Discord-подменой на голосовых портах, google-fake на gateway",
            profileArgs(
                youtube = ytWifi,
                discord = "-Qr,d -l':${FakePayloads.FAKE_TLS_GOOGLE}' -t5 -s1:5+sm -o2 -d1 -Mh,d",
                generic = genericOob,
                retry = retryDeep,
                quicFakes = 8,
                voiceFakes = 12,
                hosts = hosts,
                voiceFake = FakePayloads.FAKE_DISCORD_UDP,
            ),
            kind = ProfileKind.DISCORD,
            badge = "Голос",
        ),
        FlowsealProfile(
            "limeflow_zf_autottl",
            "ZAPRET AUTOTTTL",
            "google fake + auto TTL",
            "Google-fake с авто-TTL: движок сам замеряет дистанцию до сервера UDP-пробой, -t8 только как запасной",
            profileArgs(
                youtube = "-Qr,d -l':${FakePayloads.FAKE_TLS_GOOGLE}' -z1:4:64 -t8 -s1+s -d3+s -r1+s",
                discord = "-Qr,d -l':${FakePayloads.FAKE_TLS_GOOGLE}' -z1:4:64 -t8 -s1:5+sm -o2 -d1 -Mh,d",
                generic = "-Qr,d -l':${FakePayloads.FAKE_TLS_GOOGLE}' -z1:4:64 -t8 -s1+s -d3+s",
                retry = "-o1 -d1 -s3+sm -r1+s -Qr -f-1 -z1:4:64 -t6",
                quicFakes = 10,
                voiceFakes = 8,
                hosts = hosts,
                quicFakePayload = "-l':${FakePayloads.FAKE_QUIC_GOOGLE}'",
                voiceFake = FakePayloads.FAKE_DISCORD_UDP,
            ),
            kind = ProfileKind.UNIVERSAL,
            badge = "AutoTTL",
        ),
        FlowsealProfile(
            "limeflow_discord",
            "LIMEFLOW DISCORD",
            "gateway split + STUN",
            "Discord: SNI vk/google, split и OOB, голос на 19294–19344 и 50000–50100",
            profileArgs(
                youtube = ytOob,
                discord = dcFakeChain,
                generic = genericOob,
                retry = retryDeep,
                quicFakes = 8,
                voiceFakes = 6,
                junkCount = 8,
                hosts = hosts,
            ),
            kind = ProfileKind.DISCORD,
            badge = "Голос",
        ),
        FlowsealProfile(
            "limeflow_discord_alt",
            "LIMEFLOW DISCORD ALT",
            "OOB + TLS record",
            "Запасной Discord, если gateway режет disorder: OOB со смещением SNI и короткий fake",
            profileArgs(
                youtube = ytOob,
                discord = dcOob,
                generic = genericOob,
                retry = retryOob,
                quicFakes = 6,
                voiceFakes = 8,
                junkCount = 6,
                junkMax = 192,
                hosts = hosts,
            ),
            kind = ProfileKind.DISCORD,
            badge = "ALT",
        ),
        FlowsealProfile(
            "limeflow_stream",
            "LIMEFLOW STREAM",
            "stable multisplit",
            "Ровный профиль для продолжительного видео и крупных CDN, в том числе на Wi-Fi",
            profileArgs(
                youtube = ytWifi,
                discord = dcOob,
                generic = "-d1 -s1+s -d3+s -s6+s -d9+s -s12+s -d15+s -s20+s",
                retry = retryDeep,
                quicFakes = 14,
                junkCount = 8,
                hosts = hosts,
            ),
            kind = ProfileKind.YOUTUBE,
            badge = "Стрим",
        ),
        FlowsealProfile(
            "limeflow_mobile",
            "LIMEFLOW MOBILE",
            "compact cellular chain",
            "Компактная цепочка для мобильной сети с меняющейся задержкой",
            profileArgs(
                youtube = ytWifi,
                discord = dcOob,
                generic = "-o1 -s1+s -r1+s",
                retry = retryOob,
                quicFakes = 8,
                voiceFakes = 5,
                junkCount = 6,
                junkMax = 192,
                hosts = hosts,
            ),
            kind = ProfileKind.UNIVERSAL,
            badge = "LTE",
        ),
        FlowsealProfile(
            "limeflow_rostelecom",
            "ROSTELECOM / TELE2",
            "OOB + TLS record",
            "OOB со смещением SNI и TLS-record — типовая связка для Ростелекома и Tele2",
            profileArgs(
                youtube = ytWifi,
                discord = "-o1 -o25+s -r1+s -Qr -f-1 -t8",
                generic = "-o1 -o25+s -r1+s",
                retry = retryDeep,
                hosts = hosts,
            ),
            kind = ProfileKind.OPERATOR,
            badge = "Wi-Fi",
        ),
        FlowsealProfile(
            "limeflow_mgts",
            "MGTS / BEELINE",
            "OOB + reverse record",
            "OOB с обратным TLS-record для МГТС и Билайна на Wi-Fi",
            profileArgs(
                youtube = ytWifi,
                discord = "-o1 -r-5+se -Qr -f-1 -t8",
                generic = "-o1 -r-5+se",
                retry = genericOob,
                hosts = hosts,
            ),
            kind = ProfileKind.OPERATOR,
            badge = "Wi-Fi",
        ),
        FlowsealProfile(
            "limeflow_mts",
            "MTS / MEGAFON",
            "disorder + drop SACK",
            "Disorder с игнорированием SACK и fake-повтором для МТС и Мегафона",
            profileArgs(
                youtube = ytWifi,
                discord = dcFakeChain,
                generic = "-s1 -q1 -Y -o1+s -r1+s",
                retry = retryDeep,
                hosts = hosts,
            ),
            kind = ProfileKind.OPERATOR,
            badge = "LTE",
        ),
        FlowsealProfile("general", "GENERAL", "smart chain 1", "Многопрофильная стратегия нового ядра", flat(0, hosts = hosts)),
        FlowsealProfile("alt", "ALT", "smart chain 2", "Глубокое TLS-разбиение", flat(1, hosts = hosts)),
        FlowsealProfile("alt2", "ALT2", "smart chain 3", "Split, disorder и TLS-record", flat(2, hosts = hosts)),
        FlowsealProfile("alt3", "ALT3", "smart chain 4", "Адаптивная цепочка протоколов", flat(3, hosts = hosts)),
        FlowsealProfile("alt4", "ALT4", "smart chain 5", "Длинный SNI multisplit", flat(4, hosts = hosts)),
        FlowsealProfile("alt5", "ALT5", "smart chain 6", "OOB и multidisorder", flat(5, hosts = hosts)),
        FlowsealProfile("alt6", "ALT6", "multidisorder", "Глубокое разбиение SNI", flat(6, hosts = hosts)),
        FlowsealProfile("alt7", "ALT7", "multisplit", "Несколько позиций SNI", flat(7, hosts = hosts)),
        FlowsealProfile("alt8", "ALT8", "fake + TLS record", "Комбинированный fake", flat(8, hosts = hosts)),
        FlowsealProfile("alt9", "ALT9", "multisplit", "Частое разбиение SNI", flat(9, hosts = hosts)),
        FlowsealProfile("alt10", "ALT10", "disorder + TLS record", "Альтернативная TTL-цепочка", flat(10, hosts = hosts)),
        FlowsealProfile("alt11", "ALT11", "fake + multisplit", "Обновлённый профиль для вашей сети", flat(11, hosts = hosts)),
        FlowsealProfile("alt12", "ALT12", "record + fake", "Экспериментальная комбинация", flat(12, hosts = hosts)),
        FlowsealProfile("exp", "EXP", "OOB + fake", "Расширенный экспериментальный профиль", flat(13, hosts = hosts)),
        FlowsealProfile("fake_auto", "FAKE TLS AUTO", "randomised fake", "Fake со случайными полями ClientHello", flat(14, hosts = hosts)),
        FlowsealProfile("fake_auto_alt", "FAKE TLS AUTO ALT", "record split", "Автоматическое TLS-разбиение", flat(15, hosts = hosts)),
        FlowsealProfile("fake_auto_alt2", "FAKE TLS AUTO ALT2", "multidisorder", "Автоматическая глубокая цепочка", flat(16, hosts = hosts)),
        FlowsealProfile("fake_auto_alt3", "FAKE TLS AUTO ALT3", "QUIC + record", "Профиль для HTTPS/QUIC", flat(17, hosts = hosts)),
        FlowsealProfile("simple_fake", "SIMPLE FAKE", "compact fake", "Компактный fake-профиль", flat(18, hosts = hosts)),
        FlowsealProfile("simple_fake_alt", "SIMPLE FAKE ALT", "compact mixed", "Fake, OOB и TLS-record", flat(19, hosts = hosts)),
        FlowsealProfile("simple_fake_alt2", "SIMPLE FAKE ALT2", "protocol groups", "Раздельная обработка протоколов", flat(20, hosts = hosts)),
    )

    private const val DEFAULT_ID = "limeflow_universal"

    val all: List<FlowsealProfile> get() = builtins()

    val default: FlowsealProfile get() = builtins().first { it.id == DEFAULT_ID }

    fun selected(preferences: SharedPreferences): FlowsealProfile {
        val id = preferences.getString("flowseal_profile", DEFAULT_ID)
        return catalog(preferences).firstOrNull { it.id == id } ?: default
    }

    fun select(preferences: SharedPreferences, profile: FlowsealProfile) {
        preferences.edit()
            .putString("flowseal_profile", profile.id)
            .putString("byedpi_cmd_args", profile.arguments)
            .putBoolean("byedpi_enable_cmd_settings", true)
            .apply()
    }

    fun refreshSelected(preferences: SharedPreferences) {
        select(preferences, selected(preferences))
    }

    fun catalog(preferences: SharedPreferences): List<FlowsealProfile> =
        builtins(BypassHosts.selection(preferences)) + customProfiles(preferences)

    fun saveCustom(
        preferences: SharedPreferences,
        existingId: String?,
        name: String,
        arguments: String,
    ): FlowsealProfile {
        val profiles = customProfiles(preferences).toMutableList()
        val profile = FlowsealProfile(
            id = existingId ?: "custom_${System.currentTimeMillis()}",
            name = name.trim().uppercase(),
            method = "пользовательская",
            description = "Собственная стратегия LimeFlow",
            arguments = arguments.trim(),
            custom = true,
            kind = ProfileKind.CUSTOM,
            badge = "Своя",
        )
        val index = profiles.indexOfFirst { it.id == profile.id }
        if (index >= 0) profiles[index] = profile else profiles.add(0, profile)
        persistCustom(preferences, profiles)
        return profile
    }

    fun deleteCustom(preferences: SharedPreferences, id: String) {
        persistCustom(preferences, customProfiles(preferences).filterNot { it.id == id })
        if (preferences.getString("flowseal_profile", null) == id) {
            select(preferences, default)
        }
    }

    private fun customProfiles(preferences: SharedPreferences): List<FlowsealProfile> = runCatching {
        val stored = JSONArray(preferences.getString(CUSTOM_KEY, "[]"))
        buildList {
            for (index in 0 until stored.length()) {
                // One corrupted entry must not discard every custom profile.
                runCatching {
                    val item = stored.getJSONObject(index)
                    val name = item.optString("name").trim()
                    val arguments = item.optString("arguments").trim()
                    val id = item.optString("id").ifEmpty { null } ?: return@runCatching
                    if (name.isEmpty() || arguments.isEmpty()) return@runCatching
                    add(
                        FlowsealProfile(
                            id = id,
                            name = name,
                            method = "пользовательская",
                            description = "Собственная стратегия LimeFlow",
                            arguments = arguments,
                            custom = true,
                            kind = ProfileKind.CUSTOM,
                            badge = "Своя",
                        )
                    )
                }
            }
        }
    }.getOrDefault(emptyList())

    private fun persistCustom(
        preferences: SharedPreferences,
        profiles: List<FlowsealProfile>,
    ) {
        val payload = JSONArray().apply {
            profiles.forEach { profile ->
                put(
                    JSONObject()
                        .put("id", profile.id)
                        .put("name", profile.name)
                        .put("arguments", profile.arguments)
                )
            }
        }
        preferences.edit().putString(CUSTOM_KEY, payload.toString()).apply()
    }

    private const val CUSTOM_KEY = "limeflow_custom_profiles"
}
