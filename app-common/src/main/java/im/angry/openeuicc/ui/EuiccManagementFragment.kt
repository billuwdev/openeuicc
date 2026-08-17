package im.angry.openeuicc.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import im.angry.openeuicc.common.R
import im.angry.openeuicc.core.EuiccChannel
import im.angry.openeuicc.service.EuiccChannelManagerService
import im.angry.openeuicc.service.EuiccChannelManagerService.Companion.waitDone
import im.angry.openeuicc.ui.wizard.DownloadWizardActivity
import im.angry.openeuicc.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.typeblog.lpac_jni.LocalProfileInfo
import net.typeblog.lpac_jni.ProfileClass
import java.util.Locale

open class EuiccManagementFragment : Fragment(), EuiccProfilesChangedListener,
    EuiccChannelFragmentMarker {
    companion object {
        const val TAG = "EuiccManagementFragment"

        private val callingCodeByIso = mapOf(
            "CN" to "86", "HK" to "852", "MO" to "853", "TW" to "886",
            "US" to "1", "CA" to "1", "GB" to "44", "DE" to "49", "FR" to "33",
            "IT" to "39", "ES" to "34", "PT" to "351", "NL" to "31", "BE" to "32",
            "CH" to "41", "AT" to "43", "JP" to "81", "KR" to "82", "SG" to "65",
            "MY" to "60", "TH" to "66", "VN" to "84", "ID" to "62", "PH" to "63",
            "IN" to "91", "AU" to "61", "NZ" to "64", "AE" to "971", "TR" to "90",
            "BR" to "55", "MX" to "52", "RU" to "7"
        )

        private fun countryIsoFromMccMnc(value: String): String? = when (value.take(3)) {
            "460", "461" -> "CN"; "454" -> "HK"; "455" -> "MO"; "466" -> "TW"
            "440", "441" -> "JP"; "450" -> "KR"; "525" -> "SG"; "502" -> "MY"
            "520" -> "TH"; "404", "405", "406" -> "IN"; "234", "235" -> "GB"
            "310", "311", "312", "313", "314", "315", "316" -> "US"
            "302" -> "CA"; "262" -> "DE"; "208" -> "FR"; "505" -> "AU"
            else -> null
        }

        fun newInstance(
            slotId: Int,
            portId: Int,
            seId: EuiccChannel.SecureElementId
        ): EuiccManagementFragment =
            newInstanceEuicc(EuiccManagementFragment::class.java, slotId, portId, seId)
    }

    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var fab: FloatingActionButton
    private lateinit var profileList: RecyclerView
    private var logicalSlotId: Int = -1
    private lateinit var eid: String
    private var enabledProfile: LocalProfileInfo? = null

    private val adapter = EuiccProfileAdapter()

    // Marker for when this fragment might enter an invalid state
    // e.g. after a failed enable / disable operation
    private var invalid = false

    // Subscribe to settings we care about outside of coroutine contexts while initializing
    // This gives us access to the "latest" state without having to launch coroutines
    private lateinit var disableSafeguardFlow: StateFlow<Boolean>

    private lateinit var unfilteredProfileListFlow: StateFlow<Boolean>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_euicc, container, false)

        swipeRefresh = view.requireViewById(R.id.swipe_refresh)
        fab = view.requireViewById(R.id.fab)
        profileList = view.requireViewById(R.id.profile_list)

        val origFabMarginRight = (fab.layoutParams as ViewGroup.MarginLayoutParams).rightMargin
        val origFabMarginBottom = (fab.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin

        setupRootViewSystemBarInsets(
            view, arrayOf(
                mainViewPaddingInsetHandler(profileList),
                { insets ->
                    fab.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                        rightMargin = origFabMarginRight + insets.right
                        bottomMargin = origFabMarginBottom + insets.bottom
                    }
                }
            ))

        profileList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(view: RecyclerView, newState: Int) =
                if (newState == RecyclerView.SCROLL_STATE_IDLE) fab.show() else fab.hide()
        })

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        swipeRefresh.setOnRefreshListener { refresh() }
        profileList.adapter = adapter
        profileList.layoutManager =
            LinearLayoutManager(view.context, LinearLayoutManager.VERTICAL, false)

        fab.setOnClickListener {
            val intent = DownloadWizardActivity.newIntent(requireContext(), logicalSlotId, seId)
            startActivity(intent)
        }
    }

    override fun onStart() {
        super.onStart()
        refresh()
    }

    override fun onEuiccProfilesChanged() {
        refresh()
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        inflater.inflate(R.menu.fragment_euicc, menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        super.onPrepareOptionsMenu(menu)
        menu.findItem(R.id.show_notifications).isVisible =
            logicalSlotId != -1
        menu.findItem(R.id.euicc_info).isVisible =
            logicalSlotId != -1
        menu.findItem(R.id.euicc_memory_reset).isVisible =
            enabledProfile == null
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.show_notifications -> {
            Intent(requireContext(), NotificationsActivity::class.java).apply {
                putExtra("logicalSlotId", logicalSlotId)
                putExtra("seId", seId)
                startActivity(this)
            }
            true
        }

        R.id.euicc_info -> {
            Intent(requireContext(), EuiccInfoActivity::class.java).apply {
                putExtra("logicalSlotId", logicalSlotId)
                putExtra("seId", seId)
                startActivity(this)
            }
            true
        }

        R.id.euicc_memory_reset -> {
            EuiccMemoryResetFragment.newInstance(slotId, portId, seId, eid)
                .show(childFragmentManager, EuiccMemoryResetFragment.TAG)
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    protected open suspend fun onCreateFooterViews(
        parent: ViewGroup,
        profiles: List<LocalProfileInfo>
    ): List<View> =
        if (profiles.isEmpty()) {
            val view = layoutInflater.inflate(R.layout.footer_no_profile, parent, false)
            listOf(view)
        } else {
            listOf()
        }

    private fun refresh() {
        if (invalid) return
        swipeRefresh.isRefreshing = true

        lifecycleScope.launch {
            doRefresh()
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    protected open suspend fun doRefresh() {
        ensureEuiccChannelManager()
        euiccChannelManagerService.waitForForegroundTask()

        val profiles = withEuiccChannel { channel ->
            logicalSlotId = channel.logicalSlotId
            eid = channel.lpa.eID
            euiccChannelManager.notifyEuiccProfilesChanged(channel.logicalSlotId)
            if (ensureUnfilteredProfileListFlow().value)
                channel.lpa.profiles
            else
                channel.lpa.profiles.operational
        }

        renderProfiles(profiles)
    }

    private suspend fun ensureUnfilteredProfileListFlow(): StateFlow<Boolean> {
        if (!::unfilteredProfileListFlow.isInitialized) {
            unfilteredProfileListFlow =
                preferenceRepository.unfilteredProfileListFlow.stateIn(lifecycleScope)
        }
        return unfilteredProfileListFlow
    }

    @SuppressLint("NotifyDataSetChanged")
    protected suspend fun renderProfiles(profiles: List<LocalProfileInfo>) {
        if (!::disableSafeguardFlow.isInitialized) {
            disableSafeguardFlow =
                preferenceRepository.disableSafeguardFlow.stateIn(lifecycleScope)
        }
        ensureUnfilteredProfileListFlow()
        enabledProfile = profiles.enabled

        withContext(Dispatchers.Main) {
            adapter.profiles = profiles
            adapter.footerViews = onCreateFooterViews(profileList, profiles)
            adapter.notifyDataSetChanged()
            swipeRefresh.isRefreshing = false
        }
    }

    private suspend fun showSwitchFailureText() = withContext(Dispatchers.Main) {
        val resId = R.string.toast_profile_enable_failed
        Toast.makeText(context, resId, Toast.LENGTH_LONG).show()
    }

    protected open fun enableOrDisableProfile(iccid: String, enable: Boolean) {
        swipeRefresh.isRefreshing = true
        fab.isEnabled = false

        lifecycleScope.launch {
            ensureEuiccChannelManager()
            euiccChannelManagerService.waitForForegroundTask()

            val err = euiccChannelManagerService
                .launchProfileSwitchTask(
                    slotId, portId, seId, iccid, enable,
                    reconnectTimeoutMillis = 30 * 1000
                )
                .stateFlow.waitDone()

            when (err) {
                null -> {}
                is EuiccChannelManagerService.SwitchingProfilesRefreshException -> {
                    // This is only really fatal for internal eSIMs
                    if (!isUsb) {
                        withContext(Dispatchers.Main) {
                            AlertDialog.Builder(requireContext(), R.style.AlertDialogTheme)
                                .setMessage(R.string.profile_switch_did_not_refresh)
                                .setPositiveButton(android.R.string.ok) { _, _ -> requireActivity().finish() }
                                .setOnDismissListener { _ -> requireActivity().finish() }
                                .show()
                        }
                    }
                }

                is TimeoutCancellationException -> {
                    withContext(Dispatchers.Main) {
                        // Prevent this Fragment from being used again
                        invalid = true
                        // Timed out waiting for SIM to come back online, we can no longer assume that the LPA is still valid
                        AlertDialog.Builder(requireContext(), R.style.AlertDialogTheme)
                            .setMessage(appContainer.customizableTextProvider.profileSwitchingTimeoutMessage)
                            .setPositiveButton(android.R.string.ok) { _, _ -> requireActivity().finish() }
                            .setOnDismissListener { _ -> requireActivity().finish() }
                            .show()
                    }
                }

                else -> showSwitchFailureText()
            }

            refresh()
            fab.isEnabled = true
        }
    }

    protected open fun populatePopupWithProfileActions(
        popup: PopupMenu,
        profile: LocalProfileInfo
    ) {
        popup.inflate(R.menu.profile_options)
        if (!profile.isEnabled) return
        popup.menu.findItem(R.id.enable).isVisible = false
        popup.menu.findItem(R.id.delete).isVisible = false

        // We hide the disable option by default to avoid "bricking" some cards that won't get
        // recognized again by the phone's modem. However, we don't have that worry if we are
        // accessing it through a USB card reader, or when the user explicitly opted in
        if (!isUsb && !disableSafeguardFlow.value) return
        popup.menu.findItem(R.id.disable).isVisible = true
    }

    sealed class ViewHolder(root: View) : RecyclerView.ViewHolder(root) {
        enum class Type(val value: Int) {
            PROFILE(0),
            FOOTER(1);

            companion object {
                fun fromInt(value: Int) =
                    entries.first { it.value == value }
            }
        }
    }

    inner class FooterViewHolder : ViewHolder(FrameLayout(requireContext())) {
        init {
            itemView.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        fun attach(view: View) {
            view.parent?.let { (it as ViewGroup).removeView(view) }
            (itemView as FrameLayout).addView(view)
        }

        fun detach() {
            (itemView as FrameLayout).removeAllViews()
        }
    }

    inner class ProfileViewHolder(private val root: View) : ViewHolder(root) {
        private val card: MaterialCardView = root.requireViewById(R.id.profile_card)
        private val providerAvatar: TextView = root.requireViewById(R.id.provider_avatar)
        private val providerIcon: ImageView = root.requireViewById(R.id.provider_icon)
        private val lineDetails: TextView = root.requireViewById(R.id.profile_line_details)
        private val iccid: TextView = root.requireViewById(R.id.iccid)
        private val name: TextView = root.requireViewById(R.id.name)
        private val state: TextView = root.requireViewById(R.id.state)
        private val provider: TextView = root.requireViewById(R.id.provider)
        private val profileClassLabel: TextView = root.requireViewById(R.id.profile_class_label)
        private val profileClass: TextView = root.requireViewById(R.id.profile_class)
        private val profileMenu: ImageButton = root.requireViewById(R.id.profile_menu)
        private val profileSwitch: MaterialSwitch = root.requireViewById(R.id.profile_switch)
        private val profileSeqNumber: TextView = root.requireViewById(R.id.profile_sequence_number)
        private var bindingSwitch = false
        private var iccidRevealed = false

        init {
            iccid.setOnClickListener {
                iccidRevealed = !iccidRevealed
                updateIccidText()
            }

            iccid.setOnLongClickListener {
                requireContext().getSystemService(ClipboardManager::class.java)!!
                    .setPrimaryClip(ClipData.newPlainText("iccid", profile.iccid))
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) Toast
                    .makeText(requireContext(), R.string.toast_iccid_copied, Toast.LENGTH_SHORT)
                    .show()
                true
            }

            profileMenu.setOnClickListener {
                showOptionsMenu()
            }

            card.setOnClickListener {
                showOptionsMenu()
            }

            profileSwitch.setOnCheckedChangeListener { _, checked ->
                if (!bindingSwitch) onSwitchChanged(checked)
            }
        }

        private lateinit var profile: LocalProfileInfo
        private var canEnable: Boolean = false
        private var canDisable: Boolean = false

        fun setProfile(profile: LocalProfileInfo) {
            this.profile = profile
            name.text = profile.displayName
            providerAvatar.text = profile.providerName
                .firstOrNull(Char::isLetterOrDigit)
                ?.uppercaseChar()
                ?.toString()
                ?: "?"
            bindProviderIcon(profile)
            bindLineDetails(profile)

            state.setText(
                if (profile.isEnabled) {
                    R.string.profile_state_enabled
                } else {
                    R.string.profile_state_disabled
                }
            )
            provider.text = profile.providerName
            bindingSwitch = true
            profileSwitch.isChecked = profile.isEnabled
            bindingSwitch = false
            profileClassLabel.isVisible = unfilteredProfileListFlow.value
            profileClass.isVisible = unfilteredProfileListFlow.value
            profileSeqNumber.isVisible = unfilteredProfileListFlow.value
            profileClass.setText(
                when (profile.profileClass) {
                    ProfileClass.Testing -> R.string.profile_class_testing
                    ProfileClass.Provisioning -> R.string.profile_class_provisioning
                    ProfileClass.Operational -> R.string.profile_class_operational
                }
            )
            iccidRevealed = false
            updateIccidText()
            card.contentDescription = root.context.getString(
                R.string.profile_card_accessibility,
                profile.displayName,
                profile.providerName,
                root.context.getString(
                    if (profile.isEnabled) R.string.profile_state_enabled
                    else R.string.profile_state_disabled
                )
            )
        }

        private fun bindProviderIcon(profile: LocalProfileInfo) {
            val bitmap = runCatching {
                if (profile.icon.isBlank()) return@runCatching null
                val bytes = Base64.decode(profile.icon, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.getOrNull()
            providerIcon.isVisible = bitmap != null
            providerAvatar.isVisible = bitmap == null
            providerIcon.setImageBitmap(bitmap)
        }

        @SuppressLint("MissingPermission")
        private fun bindLineDetails(profile: LocalProfileInfo) {
            val subscription = runCatching {
                appContainer.subscriptionManager.activeSubscriptionInfoList
                    ?.firstOrNull {
                        it.iccId?.filter(Char::isDigit) == profile.iccid.filter(Char::isDigit)
                    }
            }.getOrNull()
            val countryIso = subscription?.countryIso?.takeIf(String::isNotBlank)
                ?: countryIsoFromMccMnc(profile.mccMnc)
            val number = subscription?.let { info ->
                runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        appContainer.subscriptionManager.getPhoneNumber(info.subscriptionId)
                    } else {
                        @Suppress("DEPRECATION") info.number
                    }
                }.getOrNull()?.takeIf(String::isNotBlank)
            }
            val parts = mutableListOf(
                number ?: root.context.getString(R.string.profile_phone_unavailable)
            )
            countryIso?.let { iso ->
                val country = Locale("", iso).getDisplayCountry(Locale.getDefault())
                callingCodeByIso[iso.uppercase(Locale.ROOT)]?.let { code ->
                    parts += root.context.getString(
                        R.string.profile_country_calling_code, country, code
                    )
                } ?: run { if (country.isNotBlank()) parts += country }
            }
            profile.mccMnc.takeIf(String::isNotBlank)?.let {
                parts += root.context.getString(R.string.profile_mcc_mnc, it)
            }
            lineDetails.text = parts.joinToString("  ·  ")
            lineDetails.isVisible = true
        }

        fun setProfileSequenceNumber(index: Int) {
            profileSeqNumber.text = root.context.getString(
                R.string.profile_sequence_number_format,
                index,
            )
        }

        fun setEnabledProfile(enabledProfile: LocalProfileInfo?) {
            // cannot cross profile class enable profile
            // e.g: testing -> operational or operational -> testing
            canEnable = enabledProfile == null ||
                enabledProfile.profileClass == profile.profileClass
            canDisable = profile.isEnabled && (isUsb || disableSafeguardFlow.value)
            profileSwitch.isEnabled = if (profile.isEnabled) canDisable else canEnable
        }

        private fun updateIccidText() {
            iccid.text = if (iccidRevealed) {
                profile.iccid
            } else {
                root.context.getString(
                    R.string.profile_iccid_masked,
                    profile.iccid.takeLast(4)
                )
            }
        }

        private fun onSwitchChanged(checked: Boolean) {
            if (invalid || swipeRefresh.isRefreshing || checked == profile.isEnabled) {
                restoreSwitchState()
                return
            }

            if (checked && !canEnable) {
                Toast.makeText(
                    requireContext(),
                    R.string.toast_profile_enable_cross_class,
                    Toast.LENGTH_LONG
                ).show()
                restoreSwitchState()
                return
            }

            if (!checked && !canDisable) {
                restoreSwitchState()
                return
            }

            profileSwitch.isEnabled = false
            enableOrDisableProfile(profile.iccid, checked)
        }

        private fun restoreSwitchState() {
            bindingSwitch = true
            profileSwitch.isChecked = profile.isEnabled
            bindingSwitch = false
        }

        private fun showOptionsMenu() {
            // Prevent users from doing multiple things at once
            if (invalid || swipeRefresh.isRefreshing) return

            PopupMenu(root.context, profileMenu).apply {
                setOnMenuItemClickListener(::onMenuItemClicked)
                populatePopupWithProfileActions(this, profile)
                show()
            }
        }

        private fun onMenuItemClicked(item: MenuItem): Boolean =
            when (item.itemId) {
                R.id.enable -> {
                    if (canEnable) {
                        enableOrDisableProfile(profile.iccid, true)
                    } else {
                        val resId = R.string.toast_profile_enable_cross_class
                        Toast.makeText(requireContext(), resId, Toast.LENGTH_LONG)
                            .show()
                    }
                    true
                }

                R.id.disable -> {
                    enableOrDisableProfile(profile.iccid, false)
                    true
                }

                R.id.rename -> {
                    ProfileRenameFragment.newInstance(
                        slotId,
                        portId,
                        seId,
                        profile.iccid,
                        profile.displayName
                    )
                        .show(childFragmentManager, ProfileRenameFragment.TAG)
                    true
                }

                R.id.delete -> {
                    ProfileDeleteFragment.newInstance(
                        slotId,
                        portId,
                        seId,
                        profile.iccid,
                        profile.displayName
                    )
                        .show(childFragmentManager, ProfileDeleteFragment.TAG)
                    true
                }

                else -> false
            }
    }

    inner class EuiccProfileAdapter : RecyclerView.Adapter<ViewHolder>() {
        var profiles: List<LocalProfileInfo> = listOf()
        var footerViews: List<View> = listOf()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
            when (ViewHolder.Type.fromInt(viewType)) {
                ViewHolder.Type.PROFILE -> {
                    val view = LayoutInflater.from(parent.context)
                        .inflate(R.layout.euicc_profile, parent, false)
                    ProfileViewHolder(view)
                }

                ViewHolder.Type.FOOTER -> {
                    FooterViewHolder()
                }
            }

        override fun getItemViewType(position: Int): Int =
            when {
                position < profiles.size -> {
                    ViewHolder.Type.PROFILE.value
                }

                position >= profiles.size && position < profiles.size + footerViews.size -> {
                    ViewHolder.Type.FOOTER.value
                }

                else -> -1
            }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            when (holder) {
                is ProfileViewHolder -> {
                    holder.setProfile(profiles[position])
                    holder.setEnabledProfile(profiles.enabled)
                    holder.setProfileSequenceNumber(position + 1)
                }

                is FooterViewHolder -> {
                    holder.attach(footerViews[position - profiles.size])
                }
            }
        }

        override fun onViewRecycled(holder: ViewHolder) {
            if (holder is FooterViewHolder) {
                holder.detach()
            }
        }

        override fun getItemCount(): Int = profiles.size + footerViews.size
    }
}
