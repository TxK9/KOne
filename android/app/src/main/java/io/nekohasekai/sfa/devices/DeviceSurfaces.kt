package io.nekohasekai.sfa.devices

/*
 * How the screens decide what each control can do, kept free of Android so it can be tested.
 */

/** Whether a surface can act: nothing is connected, the integration lacks it, or it is ready. */
enum class Availability { NotConnected, Unsupported, Ready }

/** The parts of an engine's page, each of which an adapter may or may not offer. */
enum class EngineFeature { Power, Status, Configuration, Mode, Nodes, Routing, Traffic, Logs }

/**
 * What each part of an engine's page can do. Without a connection everything is not connected;
 * with a connection that cannot drive this engine, everything is unavailable; otherwise each part
 * follows whether the adapter offers it.
 */
fun engineFeatures(connected: Boolean, adapter: EngineAdapter?): Map<EngineFeature, Availability> =
    EngineFeature.entries.associateWith { feature ->
        when {
            !connected -> Availability.NotConnected
            adapter == null -> Availability.Unsupported
            adapter.offers(feature) -> Availability.Ready
            else -> Availability.Unsupported
        }
    }

private fun EngineAdapter.offers(feature: EngineFeature): Boolean = when (feature) {
    EngineFeature.Power -> control != null
    EngineFeature.Status -> true
    EngineFeature.Configuration -> configuration != null
    EngineFeature.Mode -> modes != null
    EngineFeature.Nodes -> nodes != null
    EngineFeature.Routing -> routing != null
    EngineFeature.Traffic -> traffic != null
    EngineFeature.Logs -> logs != null
}

/**
 * One device's traffic from its engines' latest samples: rates add up, and a total or a count is
 * shown only when every engine that sent a sample measured it. Null when nothing was measured.
 */
fun combinedTraffic(samples: List<TrafficSnapshot>): TrafficSnapshot? {
    if (samples.isEmpty()) return null
    fun total(pick: (TrafficSnapshot) -> Long?): Long? = samples.map(pick).takeIf { null !in it }?.sumOf { it ?: 0L }
    val connections = samples.map { it.connections }.takeIf { null !in it }?.sumOf { it ?: 0 }
    return TrafficSnapshot(
        uploadRate = samples.sumOf { it.uploadRate },
        downloadRate = samples.sumOf { it.downloadRate },
        uploadTotal = total { it.uploadTotal },
        downloadTotal = total { it.downloadTotal },
        connections = connections,
        sampledAt = samples.maxOf { it.sampledAt },
    )
}

/** Device facts with the integration's report taking precedence over what the phone knew. */
fun DeviceInfo?.mergedWith(reported: DeviceInfo?): DeviceInfo? {
    if (this == null) return reported
    if (reported == null) return this
    return DeviceInfo(
        model = reported.model ?: model,
        manufacturer = reported.manufacturer ?: manufacturer,
        system = reported.system ?: system,
        software = reported.software ?: software,
        architecture = reported.architecture ?: architecture,
        extra = (reported.extra + extra).distinctBy { it.first },
    )
}
