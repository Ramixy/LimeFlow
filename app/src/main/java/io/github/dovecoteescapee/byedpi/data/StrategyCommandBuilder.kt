package io.github.dovecoteescapee.byedpi.data

data class StrategyDraft(
    val method: StrategyMethod = StrategyMethod.NONE,
    val positions: String = "",
    val anchor: PositionAnchor = PositionAnchor.ABSOLUTE,
    val secondaryMethod: StrategyMethod = StrategyMethod.NONE,
    val secondaryPositions: String = "",
    val secondaryAnchor: PositionAnchor = PositionAnchor.ABSOLUTE,
    val tlsRecordEnabled: Boolean = false,
    val tlsRecordPosition: String = "1",
    val hostMixedCase: Boolean = false,
    val domainMixedCase: Boolean = false,
    val removeHostSpaces: Boolean = false,
    val fakeTtl: String = "",
    val fakeOffset: String = "",
    val fakeSni: String = "",
    val oobData: String = "",
    val tlsMinor: String = "",
    val udpFakeCount: String = "",
    val protocolTls: Boolean = false,
    val protocolHttp: Boolean = false,
    val protocolUdp: Boolean = false,
    val protocolIpv4: Boolean = false,
    val portFilter: String = "",
    val requestRounds: String = "",
    val timeoutSeconds: String = "",
    val tcpFastOpen: Boolean = false,
    val dropSack: Boolean = false,
    val advancedArguments: String = "",
)

enum class StrategyMethod(val flag: String) {
    NONE(""),
    SPLIT("-s"),
    DISORDER("-d"),
    FAKE("-f"),
    OOB("-o"),
    DISOOB("-q"),
}

enum class PositionAnchor(val suffix: String) {
    ABSOLUTE(""),
    SNI("+s"),
    HTTP_HOST("+h"),
    MIDDLE("+m"),
    END("+e"),
}

data class StrategyBuildResult(
    val command: String = "",
    val error: StrategyBuildError? = null,
) {
    val isValid: Boolean get() = error == null
}

enum class StrategyBuildError {
    METHOD_POSITION_REQUIRED,
    INVALID_POSITION,
    SECONDARY_METHOD_POSITION_REQUIRED,
    INVALID_SECONDARY_POSITION,
    INVALID_TLS_POSITION,
    INVALID_FAKE_TTL,
    INVALID_FAKE_OFFSET,
    INVALID_FAKE_SNI,
    INVALID_OOB_DATA,
    INVALID_TLS_MINOR,
    INVALID_UDP_COUNT,
    INVALID_PORT_FILTER,
    INVALID_REQUEST_ROUNDS,
    INVALID_TIMEOUT,
    INVALID_ADVANCED_ARGUMENTS,
    EMPTY_COMMAND,
}

object StrategyCommandBuilder {
    private val positionPattern =
        Regex("""^-?\d+(?::\d+:\d+)?(?:\+[shnme]+)?$""")
    private val rangePattern = Regex("""^\d+(?:-\d+)?$""")
    private val fakeSniPattern = Regex("""^[A-Za-z0-9.*?#_-]+$""")
    private val escapedBytePattern = Regex("""^\\(?:[0nrt]|x[0-9A-Fa-f]{2})$""")

    fun build(draft: StrategyDraft): StrategyBuildResult {
        val parts = mutableListOf<String>()

        val protocols = buildString {
            if (draft.protocolTls) append('t')
            if (draft.protocolHttp) append('h')
            if (draft.protocolUdp) append('u')
            if (draft.protocolIpv4) append('i')
        }
        if (protocols.isNotEmpty()) parts += "-K$protocols"

        draft.portFilter.trim().takeIf { it.isNotEmpty() }?.let { value ->
            if (!validRange(value, 1..65535)) {
                return StrategyBuildResult(error = StrategyBuildError.INVALID_PORT_FILTER)
            }
            parts += "-V$value"
        }

        draft.requestRounds.trim().takeIf { it.isNotEmpty() }?.let { value ->
            if (!validRange(value, 1..999)) {
                return StrategyBuildResult(error = StrategyBuildError.INVALID_REQUEST_ROUNDS)
            }
            parts += "-R$value"
        }

        draft.timeoutSeconds.trim().takeIf { it.isNotEmpty() }?.let { value ->
            val timeout = value.toDoubleOrNull()
            if (timeout == null || timeout < 0.1 || timeout > 120.0) {
                return StrategyBuildResult(error = StrategyBuildError.INVALID_TIMEOUT)
            }
            parts += "-T$value"
        }

        if (draft.tcpFastOpen) parts += "-F"

        appendMethod(
            parts,
            draft.method,
            draft.positions,
            draft.anchor,
            StrategyBuildError.METHOD_POSITION_REQUIRED,
            StrategyBuildError.INVALID_POSITION,
        )?.let { return StrategyBuildResult(error = it) }

        appendMethod(
            parts,
            draft.secondaryMethod,
            draft.secondaryPositions,
            draft.secondaryAnchor,
            StrategyBuildError.SECONDARY_METHOD_POSITION_REQUIRED,
            StrategyBuildError.INVALID_SECONDARY_POSITION,
        )?.let { return StrategyBuildResult(error = it) }

        if (draft.tlsRecordEnabled) {
            val position = draft.tlsRecordPosition.trim()
            if (!positionPattern.matches(position)) {
                return StrategyBuildResult(error = StrategyBuildError.INVALID_TLS_POSITION)
            }
            parts += "-r$position"
        }

        val httpModifiers = buildList {
            if (draft.hostMixedCase) add("h")
            if (draft.domainMixedCase) add("d")
            if (draft.removeHostSpaces) add("r")
        }
        if (httpModifiers.isNotEmpty()) {
            parts += "-M${httpModifiers.joinToString(",")}"
        }

        draft.fakeTtl.trim().takeIf { it.isNotEmpty() }?.let { value ->
            val ttl = value.toIntOrNull()
            if (ttl == null || ttl !in 1..255) {
                return StrategyBuildResult(error = StrategyBuildError.INVALID_FAKE_TTL)
            }
            parts += "-t$ttl"
        }

        draft.fakeOffset.trim().takeIf { it.isNotEmpty() }?.let { value ->
            if (!positionPattern.matches(value)) {
                return StrategyBuildResult(error = StrategyBuildError.INVALID_FAKE_OFFSET)
            }
            parts += "-O$value"
        }

        draft.fakeSni.trim().takeIf { it.isNotEmpty() }?.let { value ->
            if (value.length > 253 || !fakeSniPattern.matches(value)) {
                return StrategyBuildResult(error = StrategyBuildError.INVALID_FAKE_SNI)
            }
            parts += "-n$value"
        }

        draft.oobData.trim().takeIf { it.isNotEmpty() }?.let { value ->
            if (value.length != 1 && !escapedBytePattern.matches(value)) {
                return StrategyBuildResult(error = StrategyBuildError.INVALID_OOB_DATA)
            }
            parts += "-e$value"
        }

        draft.tlsMinor.trim().takeIf { it.isNotEmpty() }?.let { value ->
            val minor = value.toIntOrNull()
            if (minor == null || minor !in 0..255) {
                return StrategyBuildResult(error = StrategyBuildError.INVALID_TLS_MINOR)
            }
            parts += "-m$minor"
        }

        draft.udpFakeCount.trim().takeIf { it.isNotEmpty() }?.let { value ->
            val count = value.toIntOrNull()
            if (count == null || count !in 0..100) {
                return StrategyBuildResult(error = StrategyBuildError.INVALID_UDP_COUNT)
            }
            if (count > 0) parts += "-a$count"
        }

        if (draft.dropSack) parts += "-Y"

        val advanced = draft.advancedArguments.trim()
        if (advanced.isNotEmpty()) {
            if (!advanced.startsWith("-") || advanced == "-") {
                return StrategyBuildResult(error = StrategyBuildError.INVALID_ADVANCED_ARGUMENTS)
            }
            parts += advanced
        }

        if (parts.isEmpty()) {
            return StrategyBuildResult(error = StrategyBuildError.EMPTY_COMMAND)
        }
        return StrategyBuildResult(command = parts.joinToString(" "))
    }

    private fun splitPositions(value: String): List<String> =
        value.trim()
            .split(Regex("[,;\\s]+"))
            .filter { it.isNotEmpty() }

    private fun appendMethod(
        parts: MutableList<String>,
        method: StrategyMethod,
        positionsValue: String,
        anchor: PositionAnchor,
        requiredError: StrategyBuildError,
        invalidError: StrategyBuildError,
    ): StrategyBuildError? {
        if (method == StrategyMethod.NONE) return null
        val positions = splitPositions(positionsValue)
        if (positions.isEmpty()) return requiredError
        if (positions.any { !positionPattern.matches(it) }) return invalidError
        positions.forEach { position ->
            val anchored = if ('+' in position) position else position + anchor.suffix
            parts += method.flag + anchored
        }
        return null
    }

    private fun validRange(value: String, bounds: IntRange): Boolean {
        if (!rangePattern.matches(value)) return false
        val values = value.split('-').mapNotNull(String::toIntOrNull)
        if (values.isEmpty() || values.any { it !in bounds }) return false
        return values.size == 1 || values[0] <= values[1]
    }
}
