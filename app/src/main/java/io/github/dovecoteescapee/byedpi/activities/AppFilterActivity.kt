package io.github.dovecoteescapee.byedpi.activities

import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.dovecoteescapee.byedpi.R
import io.github.dovecoteescapee.byedpi.databinding.ActivityAppFilterBinding
import io.github.dovecoteescapee.byedpi.databinding.ItemFilterAppBinding
import io.github.dovecoteescapee.byedpi.utility.getPreferences
import io.github.dovecoteescapee.byedpi.utility.applyLimeFlowPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppFilterActivity : AppCompatActivity() {
    private lateinit var binding: ActivityAppFilterBinding
    private lateinit var adapter: AppsAdapter
    private val selected = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        applyLimeFlowPalette()
        super.onCreate(savedInstanceState)
        ensureTelegramExcludedByDefault(getPreferences())
        binding = ActivityAppFilterBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.alpha = 0f
        binding.root.translationY = 22f
        binding.root.animate().alpha(1f).translationY(0f).setDuration(380).start()

        selected += getPreferences().getStringSet(FILTER_PACKAGES, emptySet()).orEmpty()
        adapter = AppsAdapter(selected) { packageName, checked ->
            if (checked) selected += packageName else selected -= packageName
            savePackages()
            updateCount()
        }
        binding.appsList.layoutManager = LinearLayoutManager(this)
        binding.appsList.adapter = adapter
        binding.filterBack.setOnClickListener { finish() }
        binding.filterMode.check(
            when (getPreferences().getString(FILTER_MODE, MODE_ALL)) {
                MODE_EXCLUDE -> R.id.filter_exclude
                MODE_INCLUDE -> R.id.filter_include
                else -> R.id.filter_all
            }
        )
        binding.filterMode.setOnCheckedChangeListener { _, id ->
            val mode = when (id) {
                R.id.filter_exclude -> MODE_EXCLUDE
                R.id.filter_include -> MODE_INCLUDE
                else -> MODE_ALL
            }
            getPreferences().edit().putString(FILTER_MODE, mode).apply()
            updateCount()
        }
        binding.filterSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun afterTextChanged(s: Editable?) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                adapter.filter(s?.toString().orEmpty())
            }
        })
        updateCount()
        lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) { loadApps() }
            adapter.submit(apps)
            updateCount()
        }
    }

    private fun loadApps(): List<AppEntry> {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(
            launcherIntent,
            PackageManager.MATCH_ALL,
        ).mapNotNull { resolve ->
            val info = resolve.activityInfo?.applicationInfo ?: return@mapNotNull null
            if (info.packageName == packageName) return@mapNotNull null
            AppEntry(
                packageName = info.packageName,
                name = info.loadLabel(packageManager).toString(),
                info = info,
            )
        }.distinctBy { it.packageName }
            .sortedBy { it.name.lowercase() }
    }

    private fun savePackages() {
        getPreferences().edit().putStringSet(FILTER_PACKAGES, selected.toSet()).apply()
    }

    private fun updateCount() {
        binding.filterCount.text = getString(R.string.app_filter_selected, selected.size)
    }

    private data class AppEntry(
        val packageName: String,
        val name: String,
        val info: ApplicationInfo,
    )

    private inner class AppsAdapter(
        private val selected: Set<String>,
        private val onChecked: (String, Boolean) -> Unit,
    ) : RecyclerView.Adapter<AppsAdapter.Holder>() {
        private var all = emptyList<AppEntry>()
        private var shown = emptyList<AppEntry>()
        private var query = ""

        fun submit(apps: List<AppEntry>) {
            all = apps
            rebuild()
        }

        fun filter(value: String) {
            query = value.trim().lowercase()
            rebuild()
        }

        private fun rebuild() {
            shown = if (query.isEmpty()) all else all.filter {
                it.name.lowercase().contains(query) ||
                    it.packageName.lowercase().contains(query)
            }
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(ItemFilterAppBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount(): Int = shown.size

        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(shown[position])

        inner class Holder(
            private val item: ItemFilterAppBinding,
        ) : RecyclerView.ViewHolder(item.root) {
            fun bind(app: AppEntry) {
                item.appName.text = app.name
                item.appPackage.text = app.packageName
                item.appIcon.setImageDrawable(app.info.loadIcon(packageManager))
                item.appChecked.setOnCheckedChangeListener(null)
                item.appChecked.isChecked = app.packageName in selected
                item.appChecked.setOnCheckedChangeListener { _, checked ->
                    onChecked(app.packageName, checked)
                }
                item.root.setOnClickListener {
                    item.appChecked.isChecked = !item.appChecked.isChecked
                }
                item.root.translationY = 14f
                item.root.animate().translationY(0f).setDuration(220).start()
            }
        }
    }

    companion object {
        const val FILTER_MODE = "limeflow_app_filter_mode"
        const val FILTER_PACKAGES = "limeflow_app_filter_packages"
        const val MODE_ALL = "all"
        const val MODE_EXCLUDE = "exclude"
        const val MODE_INCLUDE = "include"
        private const val FILTER_DEFAULTS_INSTALLED = "limeflow_app_filter_defaults_v1"

        val TELEGRAM_PACKAGES = setOf(
            "org.telegram.messenger",
            "org.telegram.messenger.beta",
            "org.telegram.messenger.web",
            "org.thunderdog.challegram",
        )

        fun ensureTelegramExcludedByDefault(preferences: SharedPreferences) {
            if (preferences.getBoolean(FILTER_DEFAULTS_INSTALLED, false)) return

            val hasUserConfiguration =
                preferences.contains(FILTER_MODE) || preferences.contains(FILTER_PACKAGES)
            val editor = preferences.edit().putBoolean(FILTER_DEFAULTS_INSTALLED, true)
            if (!hasUserConfiguration) {
                editor
                    .putString(FILTER_MODE, MODE_EXCLUDE)
                    .putStringSet(FILTER_PACKAGES, TELEGRAM_PACKAGES)
            }
            editor.apply()
        }
    }
}
