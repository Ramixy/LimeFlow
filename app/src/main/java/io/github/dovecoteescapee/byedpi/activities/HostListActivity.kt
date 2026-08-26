package io.github.dovecoteescapee.byedpi.activities

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import io.github.dovecoteescapee.byedpi.R
import io.github.dovecoteescapee.byedpi.data.BypassHost
import io.github.dovecoteescapee.byedpi.data.BypassHostKind
import io.github.dovecoteescapee.byedpi.data.BypassHosts
import io.github.dovecoteescapee.byedpi.data.FlowsealProfiles
import io.github.dovecoteescapee.byedpi.databinding.ActivityHostListBinding
import io.github.dovecoteescapee.byedpi.databinding.ItemHostBinding
import io.github.dovecoteescapee.byedpi.utility.applyLimeFlowPalette
import io.github.dovecoteescapee.byedpi.utility.getPreferences

class HostListActivity : AppCompatActivity() {
    private lateinit var binding: ActivityHostListBinding
    private lateinit var adapter: HostAdapter
    private var kindFilter: BypassHostKind? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        applyLimeFlowPalette()
        super.onCreate(savedInstanceState)
        binding = ActivityHostListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.alpha = 0f
        binding.root.translationY = 18f
        binding.root.animate().alpha(1f).translationY(0f).setDuration(320).start()

        adapter = HostAdapter(
            onChecked = { host, enabled ->
                BypassHosts.setEnabled(getPreferences(), host.domain, enabled)
                persistStrategy()
                updateCount()
            },
            onRemoveCustom = { host ->
                BypassHosts.removeCustom(getPreferences(), host.domain)
                reload()
            },
        )
        binding.hostsList.layoutManager = LinearLayoutManager(this)
        binding.hostsList.adapter = adapter
        binding.hostsBack.setOnClickListener { finish() }
        binding.hostAddButton.setOnClickListener { addHost() }
        binding.hostAddInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                addHost()
                true
            } else {
                false
            }
        }
        binding.hostSelectAll.setOnClickListener {
            BypassHosts.setAllEnabled(getPreferences(), true)
            persistStrategy()
            reload()
        }
        binding.hostSelectNone.setOnClickListener {
            BypassHosts.setAllEnabled(getPreferences(), false)
            persistStrategy()
            reload()
        }
        binding.hostChipAll.setOnClickListener { setKindFilter(null) }
        binding.hostChipYoutube.setOnClickListener { setKindFilter(BypassHostKind.YOUTUBE) }
        binding.hostChipYoutube.setOnLongClickListener {
            BypassHosts.setKindEnabled(getPreferences(), BypassHostKind.YOUTUBE, true)
            persistStrategy()
            reload()
            true
        }
        binding.hostChipDiscord.setOnClickListener { setKindFilter(BypassHostKind.DISCORD) }
        binding.hostChipDiscord.setOnLongClickListener {
            BypassHosts.setKindEnabled(getPreferences(), BypassHostKind.DISCORD, true)
            persistStrategy()
            reload()
            true
        }
        binding.hostChipOther.setOnClickListener { setKindFilter(BypassHostKind.GENERAL) }
        binding.hostChipOther.setOnLongClickListener {
            BypassHosts.setKindEnabled(getPreferences(), BypassHostKind.GENERAL, true)
            persistStrategy()
            reload()
            true
        }
        binding.hostSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun afterTextChanged(s: Editable?) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                adapter.filter(s?.toString().orEmpty())
            }
        })
        setKindFilter(null)
        reload()
    }

    private fun addHost() {
        val raw = binding.hostAddInput.text?.toString().orEmpty()
        val added = BypassHosts.addCustom(getPreferences(), raw)
        if (added == null) {
            Toast.makeText(this, R.string.host_list_invalid, Toast.LENGTH_SHORT).show()
            return
        }
        binding.hostAddInput.setText("")
        persistStrategy()
        reload()
    }

    private fun persistStrategy() {
        val preferences = getPreferences()
        BypassHosts.writeHostFile(this, preferences)
        FlowsealProfiles.refreshSelected(preferences)
    }

    private fun reload() {
        adapter.submit(BypassHosts.catalog(getPreferences()))
        updateCount()
    }

    private fun updateCount() {
        val enabled = BypassHosts.enabledCount(getPreferences())
        val total = BypassHosts.catalog(getPreferences()).size
        binding.hostCount.text = getString(R.string.host_list_selected, enabled, total)
    }

    private fun setKindFilter(kind: BypassHostKind?) {
        kindFilter = kind
        adapter.setKindFilter(kind)
        styleChip(binding.hostChipAll, kind == null)
        styleChip(binding.hostChipYoutube, kind == BypassHostKind.YOUTUBE)
        styleChip(binding.hostChipDiscord, kind == BypassHostKind.DISCORD)
        styleChip(binding.hostChipOther, kind == BypassHostKind.GENERAL)
    }

    private fun styleChip(chip: MaterialButton, selected: Boolean) {
        val primary = MaterialColors.getColor(chip, com.google.android.material.R.attr.colorPrimary)
        val onPrimary = MaterialColors.getColor(chip, com.google.android.material.R.attr.colorOnPrimary)
        val surface = ContextCompat.getColor(this, R.color.app_surface)
        val muted = ContextCompat.getColor(this, R.color.app_text)
        chip.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (selected) primary else surface
        )
        chip.setTextColor(if (selected) onPrimary else muted)
    }

    private inner class HostAdapter(
        private val onChecked: (BypassHost, Boolean) -> Unit,
        private val onRemoveCustom: (BypassHost) -> Unit,
    ) : RecyclerView.Adapter<HostAdapter.Holder>() {
        private var all = emptyList<Pair<BypassHost, Boolean>>()
        private var shown = emptyList<Pair<BypassHost, Boolean>>()
        private var query = ""
        private var kind: BypassHostKind? = null

        fun submit(hosts: List<Pair<BypassHost, Boolean>>) {
            all = hosts
            rebuild()
        }

        fun filter(value: String) {
            query = value.trim().lowercase()
            rebuild()
        }

        fun setKindFilter(value: BypassHostKind?) {
            kind = value
            rebuild()
        }

        private fun rebuild() {
            shown = all.filter { (host, _) ->
                val kindOk = when (kind) {
                    null -> true
                    BypassHostKind.GENERAL ->
                        host.kind == BypassHostKind.GENERAL || host.kind == BypassHostKind.CUSTOM
                    else -> host.kind == kind
                }
                val queryOk = query.isEmpty() || host.domain.contains(query)
                kindOk && queryOk
            }
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(ItemHostBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount(): Int = shown.size

        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(shown[position])

        inner class Holder(
            private val item: ItemHostBinding,
        ) : RecyclerView.ViewHolder(item.root) {
            fun bind(entry: Pair<BypassHost, Boolean>) {
                val (host, enabled) = entry
                item.hostDomain.text = host.domain
                item.hostKind.setText(
                    when (host.kind) {
                        BypassHostKind.YOUTUBE -> R.string.youtube
                        BypassHostKind.DISCORD -> R.string.discord
                        BypassHostKind.CUSTOM -> R.string.host_kind_custom
                        BypassHostKind.GENERAL -> R.string.host_group_other
                    }
                )
                item.hostChecked.setOnCheckedChangeListener(null)
                item.hostChecked.isChecked = enabled
                item.hostChecked.setOnCheckedChangeListener { _, checked ->
                    all = all.map { entry ->
                        if (entry.first.domain == host.domain) entry.first to checked else entry
                    }
                    shown = shown.map { entry ->
                        if (entry.first.domain == host.domain) entry.first to checked else entry
                    }
                    onChecked(host, checked)
                }
                item.root.setOnClickListener {
                    item.hostChecked.isChecked = !item.hostChecked.isChecked
                }
                item.root.setOnLongClickListener {
                    if (!host.builtin) {
                        onRemoveCustom(host)
                        true
                    } else {
                        false
                    }
                }
            }
        }
    }
}
