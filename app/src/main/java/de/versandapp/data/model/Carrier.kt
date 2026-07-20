package de.versandapp.data.model

/**
 * Alle unterstützten Versanddienstleister.
 *
 * [brandColor]/[onBrandColor] steuern das farbige Badge in der UI.
 * Echte Logos sind Markenzeichen der jeweiligen Unternehmen – wenn Lizenz/Assets
 * vorhanden sind, können sie als Drawable hinterlegt und im CarrierBadge
 * anstelle des Kürzels angezeigt werden.
 */
enum class Carrier(
    val displayName: String,
    val shortCode: String,
    val brandColor: Long,
    val onBrandColor: Long,
    private val trackingUrlTemplate: String?,
) {
    DHL(
        displayName = "DHL",
        shortCode = "DHL",
        brandColor = 0xFFFFCC00,
        onBrandColor = 0xFFD40511,
        trackingUrlTemplate = "https://www.dhl.de/de/privatkunden/pakete-empfangen/verfolgen.html?piececode=%s",
    ),
    DEUTSCHE_POST(
        displayName = "Deutsche Post",
        shortCode = "Post",
        brandColor = 0xFFFFCC00,
        onBrandColor = 0xFF000000,
        trackingUrlTemplate = "https://www.deutschepost.de/de/s/sendungsverfolgung.html?piececode=%s",
    ),
    HERMES(
        displayName = "Hermes",
        shortCode = "HER",
        brandColor = 0xFF0091CD,
        onBrandColor = 0xFFFFFFFF,
        trackingUrlTemplate = "https://www.myhermes.de/empfangen/sendungsverfolgung/sendungsinformation/#%s",
    ),
    DPD(
        displayName = "DPD",
        shortCode = "DPD",
        brandColor = 0xFFDC0032,
        onBrandColor = 0xFFFFFFFF,
        trackingUrlTemplate = "https://tracking.dpd.de/status/de_DE/parcel/%s",
    ),
    GLS(
        displayName = "GLS",
        shortCode = "GLS",
        brandColor = 0xFF061AB1,
        onBrandColor = 0xFFFFD100,
        trackingUrlTemplate = "https://gls-group.eu/DE/de/paketverfolgung?match=%s",
    ),
    UPS(
        displayName = "UPS",
        shortCode = "UPS",
        brandColor = 0xFF351C15,
        onBrandColor = 0xFFFFB500,
        trackingUrlTemplate = "https://www.ups.com/track?loc=de_DE&tracknum=%s",
    ),
    FEDEX(
        displayName = "FedEx",
        shortCode = "FDX",
        brandColor = 0xFF4D148C,
        onBrandColor = 0xFFFF6600,
        trackingUrlTemplate = "https://www.fedex.com/fedextrack/?trknbr=%s",
    ),
    AMAZON(
        displayName = "Amazon Logistics",
        shortCode = "AMZ",
        brandColor = 0xFF232F3E,
        onBrandColor = 0xFFFF9900,
        trackingUrlTemplate = null,
    ),
    OTHER(
        displayName = "Sonstige",
        shortCode = "?",
        brandColor = 0xFF607D8B,
        onBrandColor = 0xFFFFFFFF,
        trackingUrlTemplate = null,
    );

    fun trackingUrl(trackingNumber: String): String? =
        trackingUrlTemplate?.format(trackingNumber)
}
