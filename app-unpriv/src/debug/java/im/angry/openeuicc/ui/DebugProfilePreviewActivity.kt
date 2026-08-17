package im.angry.openeuicc.ui

import android.os.Bundle
import im.angry.easyeuicc.R
import im.angry.openeuicc.common.R as CommonR
import im.angry.openeuicc.core.EuiccChannel
import im.angry.openeuicc.core.EuiccChannelManager
import im.angry.openeuicc.util.UnprivilegedEuiccContextMarker
import im.angry.openeuicc.util.activityToolbarInsetHandler
import im.angry.openeuicc.util.newInstanceEuicc
import im.angry.openeuicc.util.setupRootViewSystemBarInsets
import net.typeblog.lpac_jni.LocalProfileInfo
import net.typeblog.lpac_jni.ProfileClass

/** ADB-only screen for exercising the production profile card without eUICC access. */
class DebugProfilePreviewActivity : BaseEuiccAccessActivity(), UnprivilegedEuiccContextMarker {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_debug_profile_preview)
        setSupportActionBar(findViewById(CommonR.id.toolbar))
        setupRootViewSystemBarInsets(
            window.decorView.rootView,
            arrayOf(this::activityToolbarInsetHandler),
            consume = false
        )
    }

    override fun onInit() {
        if (supportFragmentManager.findFragmentById(R.id.debug_profile_preview_container) == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.debug_profile_preview_container, DebugProfilePreviewFragment.newInstance())
                .commit()
        }
    }
}

class DebugProfilePreviewFragment : EuiccManagementFragment() {
    companion object {
        fun newInstance(): DebugProfilePreviewFragment = newInstanceEuicc(
            DebugProfilePreviewFragment::class.java,
            EuiccChannelManager.USB_CHANNEL_ID,
            0,
            EuiccChannel.SecureElementId.DEFAULT
        )
    }

    private var profiles = listOf(
        previewProfile(
            iccid = "8944101234567890123",
            name = "Vodafone UK",
            nickname = "Personal UK",
            provider = "Vodafone",
            enabled = true
        ),
        previewProfile(
            iccid = "8985201234567890123",
            name = "Travel Connect",
            nickname = "Japan Trip",
            provider = "1GLOBAL",
            enabled = false
        ),
        previewProfile(
            iccid = "8931440400000000001",
            name = "Global Data",
            nickname = "",
            provider = "BetterRoaming",
            enabled = false
        )
    )

    override suspend fun doRefresh() = renderProfiles(profiles)

    override fun enableOrDisableProfile(iccid: String, enable: Boolean) {
        profiles = profiles.map { profile ->
            when {
                profile.iccid == iccid -> profile.copy(
                    state = if (enable) LocalProfileInfo.State.Enabled else LocalProfileInfo.State.Disabled
                )
                enable && profile.profileClass == ProfileClass.Operational ->
                    profile.copy(state = LocalProfileInfo.State.Disabled)
                else -> profile
            }
        }
        view?.post { onEuiccProfilesChanged() }
    }
}

private fun previewProfile(
    iccid: String,
    name: String,
    nickname: String,
    provider: String,
    enabled: Boolean
) = LocalProfileInfo(
    iccid = iccid,
    state = if (enabled) LocalProfileInfo.State.Enabled else LocalProfileInfo.State.Disabled,
    name = name,
    nickName = nickname,
    providerName = provider,
    isdpAID = "A0000005591010FFFFFFFF8900000100",
    profileClass = ProfileClass.Operational,
    mccMnc = when (provider) {
        "China Mobile" -> "46000"
        "Singtel" -> "52501"
        "Vodafone" -> "23415"
        "1GLOBAL" -> "44010"
        else -> ""
    }
)
