package com.eklab.adblocker.rules

/**
 * Curated HOST_SUFFIX values for common ad / tracking CDNs.
 * Leading-dot form matches the domain itself and every subdomain.
 */
object KnownAdDomains {

    val DOMAINS: List<String> = listOf(
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
    )

    /** Lowercases and strips a leading/trailing dot so `.Ads.com` and `ads.com` collide. */
    fun normalize(value: String): String =
        value.trim().trimEnd('.').removePrefix(".").lowercase()
}
