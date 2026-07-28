package io.github.dovecoteescapee.byedpi.activities

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.widget.doAfterTextChanged
import io.github.dovecoteescapee.byedpi.R
import io.github.dovecoteescapee.byedpi.data.FlowsealProfile
import io.github.dovecoteescapee.byedpi.data.FlowsealProfiles
import io.github.dovecoteescapee.byedpi.data.PositionAnchor
import io.github.dovecoteescapee.byedpi.data.StrategyBuildError
import io.github.dovecoteescapee.byedpi.data.StrategyCommandBuilder
import io.github.dovecoteescapee.byedpi.data.StrategyDraft
import io.github.dovecoteescapee.byedpi.data.StrategyMethod
import io.github.dovecoteescapee.byedpi.databinding.ActivityStrategyBuilderBinding
import io.github.dovecoteescapee.byedpi.utility.applyLimeFlowPalette
import io.github.dovecoteescapee.byedpi.utility.getPreferences

class StrategyBuilderActivity : AppCompatActivity() {
    private lateinit var binding: ActivityStrategyBuilderBinding
    private var selectedMethod = StrategyMethod.NONE
    private var selectedAnchor = PositionAnchor.ABSOLUTE
    private var selectedSecondaryMethod = StrategyMethod.NONE
    private var selectedSecondaryAnchor = PositionAnchor.ABSOLUTE
    private var existingId: String? = null

    private val methods by lazy {
        listOf(
            getString(R.string.strategy_method_none) to StrategyMethod.NONE,
            getString(R.string.strategy_method_split) to StrategyMethod.SPLIT,
            getString(R.string.strategy_method_disorder) to StrategyMethod.DISORDER,
            getString(R.string.strategy_method_fake) to StrategyMethod.FAKE,
            getString(R.string.strategy_method_oob) to StrategyMethod.OOB,
            getString(R.string.strategy_method_disoob) to StrategyMethod.DISOOB,
        )
    }

    private val anchors by lazy {
        listOf(
            getString(R.string.strategy_anchor_absolute) to PositionAnchor.ABSOLUTE,
            getString(R.string.strategy_anchor_sni) to PositionAnchor.SNI,
            getString(R.string.strategy_anchor_host) to PositionAnchor.HTTP_HOST,
            getString(R.string.strategy_anchor_middle) to PositionAnchor.MIDDLE,
            getString(R.string.strategy_anchor_end) to PositionAnchor.END,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyStoredTheme()
        applyLimeFlowPalette()
        super.onCreate(savedInstanceState)

        binding = ActivityStrategyBuilderBinding.inflate(layoutInflater)
        setContentView(binding.root)
        existingId = intent.getStringExtra(EXTRA_PROFILE_ID)

        binding.builderName.setText(intent.getStringExtra(EXTRA_PROFILE_NAME).orEmpty())
        binding.builderAdvanced.setText(intent.getStringExtra(EXTRA_PROFILE_ARGUMENTS).orEmpty())
        binding.builderTlsPosition.setText("1")

        configureDropdowns()
        configureListeners()
        updatePreview()

        binding.builderBack.setOnClickListener { finish() }
        binding.builderSave.setOnClickListener { saveStrategy() }
    }

    private fun configureDropdowns() {
        binding.builderMethod.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, methods.map { it.first })
        )
        binding.builderMethod.setText(methods.first().first, false)
        binding.builderMethod.setOnItemClickListener { _, _, position, _ ->
            selectedMethod = methods[position].second
            binding.builderPositionsLayout.isEnabled = selectedMethod != StrategyMethod.NONE
            updatePreview()
        }

        binding.builderAnchor.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, anchors.map { it.first })
        )
        binding.builderAnchor.setText(anchors.first().first, false)
        binding.builderAnchor.setOnItemClickListener { _, _, position, _ ->
            selectedAnchor = anchors[position].second
            updatePreview()
        }

        binding.builderSecondaryMethod.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, methods.map { it.first })
        )
        binding.builderSecondaryMethod.setText(methods.first().first, false)
        binding.builderSecondaryMethod.setOnItemClickListener { _, _, position, _ ->
            selectedSecondaryMethod = methods[position].second
            binding.builderSecondaryPositionsLayout.isEnabled =
                selectedSecondaryMethod != StrategyMethod.NONE
            updatePreview()
        }

        binding.builderSecondaryAnchor.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, anchors.map { it.first })
        )
        binding.builderSecondaryAnchor.setText(anchors.first().first, false)
        binding.builderSecondaryAnchor.setOnItemClickListener { _, _, position, _ ->
            selectedSecondaryAnchor = anchors[position].second
            updatePreview()
        }
        binding.builderPositionsLayout.isEnabled = false
        binding.builderSecondaryPositionsLayout.isEnabled = false
    }

    private fun configureListeners() {
        listOf(
            binding.builderPositions,
            binding.builderSecondaryPositions,
            binding.builderTlsPosition,
            binding.builderFakeTtl,
            binding.builderFakeOffset,
            binding.builderFakeSni,
            binding.builderOobData,
            binding.builderTlsMinor,
            binding.builderUdpCount,
            binding.builderPortFilter,
            binding.builderRequestRounds,
            binding.builderTimeout,
            binding.builderAdvanced,
        ).forEach { field ->
            field.doAfterTextChanged { updatePreview() }
        }

        listOf(
            binding.builderTlsRecord,
            binding.builderHostMixedCase,
            binding.builderDomainMixedCase,
            binding.builderRemoveSpaces,
            binding.builderProtocolTls,
            binding.builderProtocolHttp,
            binding.builderProtocolUdp,
            binding.builderProtocolIpv4,
            binding.builderTcpFastOpen,
            binding.builderDropSack,
        ).forEach { toggle ->
            toggle.setOnCheckedChangeListener { _, _ -> updatePreview() }
        }
    }

    private fun draft(): StrategyDraft = StrategyDraft(
        method = selectedMethod,
        positions = binding.builderPositions.text?.toString().orEmpty(),
        anchor = selectedAnchor,
        secondaryMethod = selectedSecondaryMethod,
        secondaryPositions = binding.builderSecondaryPositions.text?.toString().orEmpty(),
        secondaryAnchor = selectedSecondaryAnchor,
        tlsRecordEnabled = binding.builderTlsRecord.isChecked,
        tlsRecordPosition = binding.builderTlsPosition.text?.toString().orEmpty(),
        hostMixedCase = binding.builderHostMixedCase.isChecked,
        domainMixedCase = binding.builderDomainMixedCase.isChecked,
        removeHostSpaces = binding.builderRemoveSpaces.isChecked,
        fakeTtl = binding.builderFakeTtl.text?.toString().orEmpty(),
        fakeOffset = binding.builderFakeOffset.text?.toString().orEmpty(),
        fakeSni = binding.builderFakeSni.text?.toString().orEmpty(),
        oobData = binding.builderOobData.text?.toString().orEmpty(),
        tlsMinor = binding.builderTlsMinor.text?.toString().orEmpty(),
        udpFakeCount = binding.builderUdpCount.text?.toString().orEmpty(),
        protocolTls = binding.builderProtocolTls.isChecked,
        protocolHttp = binding.builderProtocolHttp.isChecked,
        protocolUdp = binding.builderProtocolUdp.isChecked,
        protocolIpv4 = binding.builderProtocolIpv4.isChecked,
        portFilter = binding.builderPortFilter.text?.toString().orEmpty(),
        requestRounds = binding.builderRequestRounds.text?.toString().orEmpty(),
        timeoutSeconds = binding.builderTimeout.text?.toString().orEmpty(),
        tcpFastOpen = binding.builderTcpFastOpen.isChecked,
        dropSack = binding.builderDropSack.isChecked,
        advancedArguments = binding.builderAdvanced.text?.toString().orEmpty(),
    )

    private fun updatePreview() {
        binding.builderTlsPositionLayout.isEnabled = binding.builderTlsRecord.isChecked
        val result = StrategyCommandBuilder.build(draft())
        binding.builderPreview.text = if (result.isValid) {
            result.command
        } else {
            getString(errorMessage(result.error))
        }
        binding.builderPreview.setTextColor(
            getColor(
                if (result.isValid) R.color.app_text else R.color.app_error
            )
        )
    }

    private fun saveStrategy() {
        val name = binding.builderName.text?.toString().orEmpty().trim()
        binding.builderNameLayout.error =
            if (name.isEmpty()) getString(R.string.custom_strategy_invalid_name) else null

        val result = StrategyCommandBuilder.build(draft())
        binding.builderPositionsLayout.error = null
        binding.builderSecondaryPositionsLayout.error = null
        binding.builderTlsPositionLayout.error = null
        binding.builderFakeTtlLayout.error = null
        binding.builderFakeOffsetLayout.error = null
        binding.builderFakeSniLayout.error = null
        binding.builderOobDataLayout.error = null
        binding.builderTlsMinorLayout.error = null
        binding.builderUdpCountLayout.error = null
        binding.builderPortFilterLayout.error = null
        binding.builderRequestRoundsLayout.error = null
        binding.builderTimeoutLayout.error = null
        binding.builderAdvancedLayout.error = null

        result.error?.let { error ->
            val message = getString(errorMessage(error))
            when (error) {
                StrategyBuildError.METHOD_POSITION_REQUIRED,
                StrategyBuildError.INVALID_POSITION -> binding.builderPositionsLayout.error = message
                StrategyBuildError.SECONDARY_METHOD_POSITION_REQUIRED,
                StrategyBuildError.INVALID_SECONDARY_POSITION ->
                    binding.builderSecondaryPositionsLayout.error = message
                StrategyBuildError.INVALID_TLS_POSITION -> binding.builderTlsPositionLayout.error = message
                StrategyBuildError.INVALID_FAKE_TTL -> binding.builderFakeTtlLayout.error = message
                StrategyBuildError.INVALID_FAKE_OFFSET -> binding.builderFakeOffsetLayout.error = message
                StrategyBuildError.INVALID_FAKE_SNI -> binding.builderFakeSniLayout.error = message
                StrategyBuildError.INVALID_OOB_DATA -> binding.builderOobDataLayout.error = message
                StrategyBuildError.INVALID_TLS_MINOR -> binding.builderTlsMinorLayout.error = message
                StrategyBuildError.INVALID_UDP_COUNT -> binding.builderUdpCountLayout.error = message
                StrategyBuildError.INVALID_PORT_FILTER -> binding.builderPortFilterLayout.error = message
                StrategyBuildError.INVALID_REQUEST_ROUNDS ->
                    binding.builderRequestRoundsLayout.error = message
                StrategyBuildError.INVALID_TIMEOUT -> binding.builderTimeoutLayout.error = message
                StrategyBuildError.INVALID_ADVANCED_ARGUMENTS,
                StrategyBuildError.EMPTY_COMMAND -> binding.builderAdvancedLayout.error = message
            }
        }
        if (name.isEmpty() || !result.isValid) return

        val saved = FlowsealProfiles.saveCustom(
            preferences = getPreferences(),
            existingId = existingId,
            name = name,
            arguments = result.command,
        )
        FlowsealProfiles.select(getPreferences(), saved)
        setResult(RESULT_OK)
        finish()
    }

    private fun errorMessage(error: StrategyBuildError?): Int = when (error) {
        StrategyBuildError.METHOD_POSITION_REQUIRED -> R.string.strategy_error_position_required
        StrategyBuildError.INVALID_POSITION -> R.string.strategy_error_position
        StrategyBuildError.SECONDARY_METHOD_POSITION_REQUIRED ->
            R.string.strategy_error_secondary_position_required
        StrategyBuildError.INVALID_SECONDARY_POSITION ->
            R.string.strategy_error_secondary_position
        StrategyBuildError.INVALID_TLS_POSITION -> R.string.strategy_error_tls_position
        StrategyBuildError.INVALID_FAKE_TTL -> R.string.strategy_error_fake_ttl
        StrategyBuildError.INVALID_FAKE_OFFSET -> R.string.strategy_error_fake_offset
        StrategyBuildError.INVALID_FAKE_SNI -> R.string.strategy_error_fake_sni
        StrategyBuildError.INVALID_OOB_DATA -> R.string.strategy_error_oob_data
        StrategyBuildError.INVALID_TLS_MINOR -> R.string.strategy_error_tls_minor
        StrategyBuildError.INVALID_UDP_COUNT -> R.string.strategy_error_udp_count
        StrategyBuildError.INVALID_PORT_FILTER -> R.string.strategy_error_port_filter
        StrategyBuildError.INVALID_REQUEST_ROUNDS -> R.string.strategy_error_request_rounds
        StrategyBuildError.INVALID_TIMEOUT -> R.string.strategy_error_timeout
        StrategyBuildError.INVALID_ADVANCED_ARGUMENTS -> R.string.strategy_error_advanced
        StrategyBuildError.EMPTY_COMMAND, null -> R.string.strategy_error_empty
    }

    private fun applyStoredTheme() {
        val mode = when (getPreferences().getString("app_theme", "system")) {
            "light" -> AppCompatDelegate.MODE_NIGHT_NO
            "dark" -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    companion object {
        private const val EXTRA_PROFILE_ID = "profile_id"
        private const val EXTRA_PROFILE_NAME = "profile_name"
        private const val EXTRA_PROFILE_ARGUMENTS = "profile_arguments"

        fun intent(context: Context, profile: FlowsealProfile? = null): Intent =
            Intent(context, StrategyBuilderActivity::class.java).apply {
                profile?.let {
                    putExtra(EXTRA_PROFILE_ID, it.id)
                    putExtra(EXTRA_PROFILE_NAME, it.name)
                    putExtra(EXTRA_PROFILE_ARGUMENTS, it.arguments)
                }
            }
    }
}
