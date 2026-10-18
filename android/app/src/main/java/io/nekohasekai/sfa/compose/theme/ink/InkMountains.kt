package io.nekohasekai.sfa.compose.theme.ink

/**
 * One peak of a ridge, in the painting's own units: x across (0 left, 1 right), heights as a
 * share of the painting's height. Its profile is a * (1 - |d|^round)^power over the half-widths
 * left and right of the summit: power above 1 gives the concave flanks of a spire, below 1 the
 * full shoulders of a dome; a large round flattens the top into a pillar or a mesa.
 */
internal class Peak(
    val x: Float,
    val height: Float,
    val left: Float,
    val right: Float = left,
    val power: Float = 1.2f,
    val round: Float = 1.5f,
)

/** A ridge line and the ink under it, from its foot (base, measured from the top) up. */
internal class Range(
    val peaks: List<Peak>,
    val base: Float,
    val ink: Float,
    val blur: Float,
    val roughness: Float,
    val seed: Int,
    val texture: Texture = Texture.None,
    /** Snow from the summit down to this share of the mountain's height, left as bare paper. */
    val snow: Float = 0f,
    /** Whether lesser peaks are added down the flanks; off for a smooth dome or a mesa's clean edge. */
    val shoulders: Boolean = true,
    /** How much ink stays down to the foot (0 fades out into mist, 1 stands solid on its ground). */
    val body: Float = 0f,
)

/** The buildings and works of people the scenes hold, each drawn by its own outline. */
internal enum class Landmark { Liberty, GoldenGate, EmpireState, Skyline, Capitol, Rushmore, Niagara }

/**
 * A landmark placed in a scene: centred at x, standing on base (both in the painting's units),
 * height as a share of the painting's height. Far ones are pale and soft, in the back layer.
 */
internal class Placed(val landmark: Landmark, val x: Float, val base: Float, val height: Float, val far: Boolean, val ink: Float)

/** How rock faces are drawn: hemp-fibre strokes for earth, axe-cut wedges for granite, beds for strata. */
internal enum class Texture { None, Hemp, Axe, Strata, Columns, Dots }

/** What only one mountain has: its trees, its building, its water, its clouds. */
internal enum class Feature { Pines, Gate, HangingTemple, Waterfall, CloudSea, SheerFace }

/**
 * One painting: pale far ranges and landmarks behind, the subject in front (a mountain, a statue,
 * a bridge), its own features, still water where there is a harbour or a bay, and the seal with
 * its name on the open side of the sky.
 */
internal class InkMountain(
    val name: String,
    val motto: String,
    val far: List<Range>,
    val near: List<Range>,
    val features: Set<Feature>,
    val sealOnLeft: Boolean,
    val landmarks: List<Placed> = emptyList(),
    /** The water line, from the top, for scenes on a harbour or a bay. */
    val water: Float? = null,
    /** Ground nearer than the landmarks, painted last: the hill the painter stands on. */
    val foreground: List<Range> = emptyList(),
)

/**
 * The Five Great Mountains and three famous ones, each drawn by its own character, taking turns
 * with four American scenes painted the same way: Statue of Liberty, Golden Gate Bridge, Half Dome and Mount Rainier.
 */
internal object InkMountains {
    val all: List<InkMountain> = listOf(
        InkMountain(
            name = "Mount Tai", motto = "Crown of the Five Peaks",
            far = listOf(
                Range(listOf(Peak(0.12f, 0.22f, 0.24f), Peak(0.56f, 0.28f, 0.32f, 0.36f), Peak(0.94f, 0.21f, 0.22f)), 0.70f, 0.16f, 0.012f, 0.03f, 11),
                Range(listOf(Peak(0.82f, 0.36f, 0.22f, 0.28f, 1.2f), Peak(0.14f, 0.30f, 0.22f, 0.20f, 1.2f)), 0.82f, 0.32f, 0.005f, 0.04f, 12, Texture.Hemp),
            ),
            near = listOf(
                Range(listOf(Peak(0.70f, 0.48f, 0.16f, 0.22f, 1.3f)), 0.88f, 0.50f, 0.003f, 0.04f, 14, Texture.Hemp),
                Range(
                    listOf(Peak(0.42f, 0.66f, 0.30f, 0.34f, 1.45f, 1.4f), Peak(0.27f, 0.44f, 0.12f, 0.12f, 1.3f), Peak(0.56f, 0.50f, 0.10f, 0.14f, 1.3f)),
                    0.93f, 0.86f, 0.0018f, 0.04f, 13, Texture.Hemp,
                ),
            ),
            features = setOf(Feature.Pines, Feature.Gate),
            sealOnLeft = false,
        ),
        InkMountain(
            name = "Statue of Liberty", motto = "Liberty Enlightening the World",
            far = listOf(
                // The far shore under the haze, low and pale.
                Range(listOf(Peak(0.18f, 0.045f, 0.30f, power = 0.8f, round = 2f), Peak(0.86f, 0.035f, 0.26f, power = 0.8f, round = 2f)), 0.80f, 0.12f, 0.012f, 0.02f, 91),
            ),
            near = listOf(
                // Liberty Island, its trees in dots round the fort.
                Range(listOf(Peak(0.68f, 0.035f, 0.24f, 0.26f, 0.6f, 3f)), 0.885f, 0.42f, 0.003f, 0.02f, 93, Texture.Dots),
            ),
            features = emptySet(),
            sealOnLeft = true,
            landmarks = listOf(
                Placed(Landmark.Skyline, 0.30f, 0.80f, 0.13f, far = true, ink = 0.30f),
                Placed(Landmark.EmpireState, 0.25f, 0.80f, 0.21f, far = true, ink = 0.42f),
                Placed(Landmark.Liberty, 0.68f, 0.865f, 0.62f, far = false, ink = 0.92f),
            ),
            water = 0.80f,
        ),
        InkMountain(
            name = "Mount Hua", motto = "A mountain of striking heights",
            far = listOf(
                Range(listOf(Peak(0.20f, 0.26f, 0.24f), Peak(0.80f, 0.24f, 0.30f)), 0.70f, 0.15f, 0.012f, 0.03f, 21),
                Range(listOf(Peak(0.18f, 0.42f, 0.10f, 0.12f, 1.6f, 1.2f), Peak(0.30f, 0.34f, 0.08f, 0.09f, 1.6f, 1.2f)), 0.84f, 0.34f, 0.005f, 0.035f, 22, Texture.Axe),
            ),
            near = listOf(
                Range(listOf(Peak(0.84f, 0.52f, 0.09f, 0.12f, 1.7f, 1.2f), Peak(0.40f, 0.46f, 0.09f, 0.08f, 1.7f, 1.2f)), 0.90f, 0.52f, 0.003f, 0.035f, 24, Texture.Axe),
                Range(
                    listOf(
                        Peak(0.60f, 0.70f, 0.12f, 0.13f, 1.05f, 2.3f), Peak(0.47f, 0.58f, 0.09f, 0.09f, 1.05f, 2.3f),
                        Peak(0.72f, 0.60f, 0.09f, 0.12f, 1.05f, 2.3f), Peak(0.60f, 0.42f, 0.24f, 0.26f, 0.9f, 2.4f),
                    ),
                    0.93f, 0.9f, 0.0016f, 0.05f, 23, Texture.Axe,
                ),
            ),
            features = setOf(Feature.Pines),
            sealOnLeft = true,
        ),
        InkMountain(
            name = "Golden Gate Bridge", motto = "Golden Gate in the mist",
            far = listOf(
                // The hills of the far shore across the strait.
                Range(
                    listOf(Peak(0.52f, 0.035f, 0.12f, power = 0.9f, round = 2f), Peak(0.72f, 0.05f, 0.14f, power = 0.9f, round = 2f), Peak(0.93f, 0.07f, 0.14f, power = 0.9f, round = 2f)),
                    0.585f, 0.18f, 0.008f, 0.02f, 101,
                ),
            ),
            near = emptyList(),
            features = emptySet(),
            sealOnLeft = false,
            landmarks = listOf(
                Placed(Landmark.Capitol, 0.92f, 0.515f, 0.045f, far = true, ink = 0.32f),
                Placed(Landmark.GoldenGate, 0.24f, 0.62f, 0.44f, far = false, ink = 0.86f),
            ),
            water = 0.585f,
            // The Marin headland the bridge is seen from, dark in the corner.
            foreground = listOf(
                Range(listOf(Peak(0.0f, 0.19f, 0.30f, 0.30f, 1.0f, 1.8f), Peak(0.28f, 0.09f, 0.14f, 0.20f, 1.1f, 1.8f)), 1.0f, 0.78f, 0.002f, 0.05f, 104, Texture.Hemp),
            ),
        ),
        InkMountain(
            name = "Mount Huang", motto = "A landscape beyond compare",
            far = listOf(
                Range(listOf(Peak(0.18f, 0.32f, 0.07f, 0.08f, 1.5f), Peak(0.80f, 0.36f, 0.08f, 0.07f, 1.5f), Peak(0.92f, 0.28f, 0.07f, 0.07f, 1.4f)), 0.72f, 0.16f, 0.010f, 0.03f, 61),
                Range(listOf(Peak(0.14f, 0.42f, 0.07f, 0.08f, 1.6f, 1.6f), Peak(0.86f, 0.46f, 0.08f, 0.07f, 1.6f, 1.6f)), 0.82f, 0.34f, 0.005f, 0.03f, 62, Texture.Axe),
            ),
            near = listOf(
                Range(
                    listOf(
                        Peak(0.47f, 0.68f, 0.08f, 0.085f, 1.1f, 2.2f), Peak(0.35f, 0.58f, 0.075f, 0.08f, 1.1f, 2.2f),
                        Peak(0.59f, 0.56f, 0.075f, 0.09f, 1.1f, 2.2f), Peak(0.70f, 0.47f, 0.07f, 0.08f, 1.1f, 2.2f),
                        Peak(0.24f, 0.44f, 0.07f, 0.08f, 1.1f, 2.2f),
                    ),
                    0.88f, 0.88f, 0.0016f, 0.05f, 63, Texture.Axe,
                ),
            ),
            features = setOf(Feature.Pines, Feature.CloudSea),
            sealOnLeft = false,
        ),
        InkMountain(
            name = "Half Dome", motto = "Yosemite",
            far = listOf(
                // Grand Canyon: the canyon's far rim in level beds, a temple standing off it in tiers.
                Range(
                    listOf(
                        Peak(0.14f, 0.08f, 0.16f, 0.12f, 0.3f, 10f), Peak(0.40f, 0.13f, 0.03f, 0.035f, 0.3f, 10f), Peak(0.40f, 0.10f, 0.06f, 0.07f, 0.3f, 10f),
                        Peak(0.40f, 0.06f, 0.12f, 0.12f, 0.35f, 8f), Peak(0.64f, 0.085f, 0.10f, 0.08f, 0.3f, 10f), Peak(0.78f, 0.15f, 0.03f, 0.03f, 0.3f, 10f),
                        Peak(0.78f, 0.11f, 0.06f, 0.06f, 0.3f, 10f), Peak(0.78f, 0.065f, 0.11f, 0.12f, 0.35f, 8f), Peak(0.95f, 0.095f, 0.10f, 0.16f, 0.3f, 10f),
                    ),
                    0.68f, 0.26f, 0.006f, 0.012f, 111, Texture.Strata, shoulders = false, body = 0.6f,
                ),
                // Monument Valley: the Mittens, sheer blocks with their thumbs, on their slopes of scree.
                Range(
                    listOf(
                        Peak(0.16f, 0.125f, 0.055f, 0.06f, 0.25f, 12f), Peak(0.09f, 0.11f, 0.008f, 0.008f, 0.4f, 6f), Peak(0.15f, 0.065f, 0.13f, 0.13f, 1.4f, 1.6f),
                        Peak(0.33f, 0.12f, 0.06f, 0.05f, 0.25f, 12f), Peak(0.395f, 0.105f, 0.008f, 0.008f, 0.4f, 6f), Peak(0.33f, 0.06f, 0.13f, 0.13f, 1.4f, 1.6f),
                    ),
                    0.77f, 0.40f, 0.003f, 0.012f, 112, Texture.Strata, shoulders = false, body = 1f,
                ),
            ),
            near = listOf(
                Range(
                    listOf(
                        // The dome, cut sheer on the side toward us; the talus under the face; the ridge running off.
                        Peak(0.52f, 0.50f, 0.022f, 0.38f, 0.32f, 2.8f), Peak(0.505f, 0.26f, 0.32f, 0.04f, 1.1f, 1.5f), Peak(0.90f, 0.24f, 0.14f, 0.14f, 1.0f, 1.8f),
                    ),
                    0.85f, 0.86f, 0.0018f, 0.012f, 113, Texture.Strata, shoulders = false,
                ),
            ),
            features = setOf(Feature.SheerFace),
            sealOnLeft = true,
        ),
        InkMountain(
            name = "Mount Heng (South)", motto = "Grace of the Southern Peak",
            far = listOf(
                Range(listOf(Peak(0.18f, 0.22f, 0.20f, power = 0.8f, round = 2f), Peak(0.50f, 0.27f, 0.24f, power = 0.8f, round = 2f), Peak(0.86f, 0.23f, 0.20f, power = 0.8f, round = 2f)), 0.70f, 0.15f, 0.012f, 0.02f, 31),
                Range(listOf(Peak(0.30f, 0.33f, 0.17f, power = 0.75f, round = 2f), Peak(0.72f, 0.35f, 0.20f, power = 0.75f, round = 2f)), 0.81f, 0.30f, 0.005f, 0.025f, 32, Texture.Dots),
            ),
            near = listOf(
                Range(
                    listOf(
                        Peak(0.20f, 0.36f, 0.14f, 0.17f, 0.8f, 2f), Peak(0.46f, 0.47f, 0.18f, 0.20f, 0.85f, 2f),
                        Peak(0.71f, 0.40f, 0.14f, 0.15f, 0.8f, 2f), Peak(0.92f, 0.30f, 0.13f, 0.13f, 0.8f, 2f),
                    ),
                    0.93f, 0.74f, 0.0022f, 0.03f, 33, Texture.Dots,
                ),
            ),
            features = setOf(Feature.CloudSea),
            sealOnLeft = false,
        ),
        InkMountain(
            name = "Mount Rainier", motto = "A solitary snowy peak",
            far = listOf(
                Range(listOf(Peak(0.15f, 0.10f, 0.20f), Peak(0.85f, 0.12f, 0.22f)), 0.69f, 0.14f, 0.012f, 0.03f, 121),
                // A far ridge to the west with Mount Rushmore's four heads cut in its granite.
                Range(listOf(Peak(0.13f, 0.13f, 0.11f, 0.14f, 1.0f, 2.2f), Peak(0.88f, 0.10f, 0.14f, 0.12f, 1.1f, 2f)), 0.79f, 0.30f, 0.005f, 0.03f, 122, Texture.Dots),
            ),
            near = listOf(
                Range(
                    listOf(Peak(0.50f, 0.38f, 0.52f, 0.56f, 1.35f, 1.8f), Peak(0.49f, 0.40f, 0.15f, 0.17f, 0.8f, 2.0f), Peak(0.74f, 0.30f, 0.035f, 0.05f, 1.2f, 1.6f)),
                    0.81f, 0.80f, 0.0018f, 0.025f, 123, Texture.Hemp, snow = 0.55f, shoulders = false,
                ),
                // Forest at the mountain's foot, the river coming out of it to the falls.
                Range(listOf(Peak(0.20f, 0.06f, 0.30f, 0.25f, 0.8f, 2f), Peak(0.62f, 0.07f, 0.30f, 0.30f, 0.8f, 2f), Peak(0.95f, 0.05f, 0.20f, power = 0.8f, round = 2f)), 0.86f, 0.62f, 0.002f, 0.04f, 124, Texture.Dots),
            ),
            features = emptySet(),
            sealOnLeft = true,
            landmarks = listOf(
                Placed(Landmark.Rushmore, 0.13f, 0.68f, 0.07f, far = true, ink = 0.38f),
                Placed(Landmark.Niagara, 0.50f, 0.875f, 0.035f, far = false, ink = 0.85f),
            ),
        ),
        InkMountain(
            name = "Mount Heng (North)", motto = "Pillar of the North",
            far = listOf(
                Range(listOf(Peak(0.30f, 0.24f, 0.30f), Peak(0.80f, 0.26f, 0.26f)), 0.70f, 0.15f, 0.012f, 0.03f, 41),
                Range(listOf(Peak(0.74f, 0.38f, 0.17f, 0.24f, 1.3f)), 0.82f, 0.30f, 0.005f, 0.04f, 42, Texture.Hemp),
            ),
            near = listOf(
                Range(
                    listOf(Peak(0.30f, 0.64f, 0.022f, 0.44f, 0.75f, 1.2f), Peak(0.60f, 0.46f, 0.13f, 0.20f, 1.35f)),
                    0.93f, 0.86f, 0.0018f, 0.04f, 43, Texture.Axe,
                ),
            ),
            features = setOf(Feature.HangingTemple, Feature.Pines),
            sealOnLeft = false,
        ),
        InkMountain(
            name = "Mount Song", motto = "A summit rising high",
            far = listOf(
                Range(listOf(Peak(0.25f, 0.22f, 0.30f), Peak(0.75f, 0.25f, 0.30f)), 0.70f, 0.15f, 0.012f, 0.025f, 51),
                Range(listOf(Peak(0.86f, 0.34f, 0.15f, 0.20f, 1.0f, 2f)), 0.82f, 0.30f, 0.005f, 0.03f, 52, Texture.Hemp),
            ),
            near = listOf(
                Range(
                    listOf(Peak(0.50f, 0.50f, 0.40f, 0.40f, 0.85f, 2.6f), Peak(0.36f, 0.56f, 0.10f, 0.12f, 1.3f), Peak(0.62f, 0.54f, 0.09f, 0.12f, 1.3f)),
                    0.93f, 0.82f, 0.0018f, 0.04f, 53, Texture.Strata,
                ),
            ),
            features = setOf(Feature.Pines),
            sealOnLeft = true,
        ),
        InkMountain(
            name = "Mount Lu", motto = "A waterfall from the heights",
            far = listOf(
                Range(listOf(Peak(0.22f, 0.24f, 0.26f, power = 0.9f, round = 2f), Peak(0.76f, 0.27f, 0.28f, power = 0.9f, round = 2f)), 0.70f, 0.15f, 0.012f, 0.025f, 71),
            ),
            near = listOf(
                Range(listOf(Peak(0.86f, 0.44f, 0.14f, 0.16f, 1.1f, 2f)), 0.90f, 0.48f, 0.003f, 0.035f, 74, Texture.Hemp),
                Range(
                    listOf(Peak(0.35f, 0.58f, 0.21f, 0.13f, 1.15f, 1.8f), Peak(0.62f, 0.62f, 0.12f, 0.20f, 1.15f, 1.8f)),
                    0.93f, 0.86f, 0.0018f, 0.04f, 73, Texture.Hemp,
                ),
            ),
            features = setOf(Feature.Waterfall, Feature.CloudSea),
            sealOnLeft = true,
        ),
        InkMountain(
            name = "Mount Yandang", motto = "A mountain by the sea",
            far = listOf(
                Range(listOf(Peak(0.28f, 0.24f, 0.28f), Peak(0.78f, 0.26f, 0.26f)), 0.70f, 0.15f, 0.012f, 0.03f, 81),
                Range(listOf(Peak(0.82f, 0.40f, 0.05f, 0.05f, 0.45f, 5f), Peak(0.20f, 0.34f, 0.05f, 0.05f, 0.45f, 5f)), 0.84f, 0.30f, 0.005f, 0.03f, 82, Texture.Columns),
            ),
            near = listOf(
                Range(
                    listOf(
                        Peak(0.30f, 0.50f, 0.05f, 0.045f, 0.4f, 5f), Peak(0.41f, 0.62f, 0.055f, 0.05f, 0.4f, 5f),
                        Peak(0.52f, 0.44f, 0.045f, 0.05f, 0.4f, 5f), Peak(0.64f, 0.66f, 0.06f, 0.055f, 0.4f, 5f),
                        Peak(0.76f, 0.48f, 0.05f, 0.05f, 0.4f, 5f), Peak(0.52f, 0.24f, 0.42f, 0.42f, 1.0f, 2f),
                    ),
                    0.93f, 0.86f, 0.0018f, 0.03f, 83, Texture.Columns,
                ),
            ),
            features = setOf(Feature.Pines),
            sealOnLeft = true,
        ),
    )
}
