package com.eklab.adblocker.rules

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class KnownAdDomainsTest {

    @Test
    fun listContainsEveryRequestedAdDomain() {
        assertThat(KnownAdDomains.DOMAINS).containsExactly(
            ".wunityads.unity3d.com",
            ".adsafeprotected.com",
            ".googleads.g.doubleclick.net",
            ".pubads.g.doubleclick.net",
            ".vungle.com",
            ".googleadservices.com",
            ".adjust.net.in",
            ".kwcdn.com",
            "adsmoloco.com",
            ".bidmachine.io",
            ".moloco.com",
            ".axon.ai",
            ".applovin.com",
            ".3lift.com",
            ".nefta.app",
            ".mtgglobals.com",
            ".saygames.io",
            ".safedk.com",
            ".tiktokcdn.com",
            ".ibyteimg.com",
            ".sng.link",
            ".pangle.io",
            ".byteoversea.com",
            ".adnxs.com",
            ".doubleverify.com",
            ".tagsrvcs.com",
            ".adskprod.azureedge.net",
            ".lazybumblebee.com",
            ".smartbid.ai",
            ".liftoff.io",
            ".inmobi.com",
            ".everstop.io",
            ".blueduckredapple.com",
            ".cloud.unity3d.com",
            ".applvn.com",
            ".amazon-adsystem.com",
            ".appsflyersdk.com",
            ".inmobicdn.net",
            ".kayzen.io",
            ".tenjin.io",
        ).inOrder()
    }

    @Test
    fun normalizeStripsDotsAndCase() {
        assertThat(KnownAdDomains.normalize(" .Ads.Example.COM. ")).isEqualTo("ads.example.com")
        assertThat(KnownAdDomains.normalize("adsmoloco.com")).isEqualTo("adsmoloco.com")
        assertThat(KnownAdDomains.normalize(".moloco.com")).isEqualTo("moloco.com")
        assertThat(KnownAdDomains.normalize(".")).isEmpty()
    }

    @Test
    fun molocoAndAdsmolocoDoNotCollapse() {
        assertThat(KnownAdDomains.normalize("adsmoloco.com"))
            .isNotEqualTo(KnownAdDomains.normalize(".moloco.com"))
    }
}
