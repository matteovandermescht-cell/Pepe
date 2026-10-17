package com.example.testbot

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class BotEngine(
    private val captureScreenshot: suspend () -> Bitmap?,
    private val performGesture: (path: List<PointF>, durationMillis: Long) -> Boolean
) {
    private enum class Decision {
        ZONE,
        BUSH,
        SEEK_BUSH,
        NO_SAFE_BUSH,
        UNKNOWN,
        INVALID_BITMAP
    }

    private data class RegionMetrics(
        val edgeDensity: Float,
        val normalizedVariance: Float,
        val toxicGreenRatio: Float
    ) {
        val isBushTexture: Boolean
            get() = edgeDensity >= BUSH_MIN_EDGE_DENSITY &&
                normalizedVariance >= BUSH_MIN_NORMALIZED_VARIANCE

        val isFlatToxicZone: Boolean
            get() = toxicGreenRatio >= ZONE_MIN_GREEN_RATIO &&
                edgeDensity <= ZONE_MAX_EDGE_DENSITY

        val bushScore: Float
            get() = edgeDensity * (normalizedVariance / VARIANCE_SCORE_SCALE).coerceAtMost(1f)
    }

    private data class Sector(
        val directionX: Float,
        val directionY: Float,
        val metrics: RegionMetrics
    )

    private data class Analysis(
        val decision: Decision,
        val center: RegionMetrics? = null,
        val target: Sector? = null,
        val sectorCount: Int = 0
    )

    private var loopJob: Job? = null
    private var rotationPhase = 0f

    fun start(scope: CoroutineScope) {
        if (loopJob?.isActive == true) return

        loopJob = scope.launch {
            while (isActive) {
                val tickStartedAt = SystemClock.uptimeMillis()
                try {
                    val bitmap = try {
                        withTimeout(CAPTURE_TIMEOUT_MS) { captureScreenshot() }
                    } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                        Log.w(TAG, "Screenshot-Aufnahme hat das Zeitlimit überschritten", timeout)
                        null
                    }

                    if (bitmap != null) {
                        try {
                            val analysis = analyze(bitmap)
                            act(analysis)
                            logAnalysis(analysis)
                        } finally {
                            if (!bitmap.isRecycled) bitmap.recycle()
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.e(TAG, "Bot-Iteration fehlgeschlagen", error)
                }

                val remaining = TICK_INTERVAL_MS - (SystemClock.uptimeMillis() - tickStartedAt)
                if (remaining > 0) delay(remaining)
            }
        }
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
    }

    private fun analyze(bitmap: Bitmap): Analysis {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
            return Analysis(Decision.INVALID_BITMAP)
        }

        val center = sampleRegion(bitmap, PLAYER_X, PLAYER_Y)
            ?: return Analysis(Decision.INVALID_BITMAP)
        val sectors = sampleSectors(bitmap)

        if (center.isFlatToxicZone) {
            val safeBush = sectors
                .filter { it.metrics.isBushTexture && !it.metrics.isFlatToxicZone }
                .maxByOrNull { it.metrics.bushScore }
            return if (safeBush != null) {
                Analysis(Decision.ZONE, center, safeBush, SECTOR_COUNT)
            } else {
                Analysis(Decision.NO_SAFE_BUSH, center, sectorCount = SECTOR_COUNT)
            }
        }

        val nearbyZone = sectors
            .filter { it.metrics.isFlatToxicZone }
            .maxByOrNull { it.metrics.toxicGreenRatio }
        if (nearbyZone != null) {
            val safeBush = sectors
                .filter { it.metrics.isBushTexture && !it.metrics.isFlatToxicZone }
                .maxByOrNull { it.metrics.bushScore }
            return if (safeBush != null) {
                Analysis(Decision.ZONE, center, safeBush, sectors.size)
            } else {
                Analysis(Decision.NO_SAFE_BUSH, center, sectorCount = sectors.size)
            }
        }

        if (center.isBushTexture) {
            return Analysis(Decision.BUSH, center, sectorCount = sectors.size)
        }

        val safeBush = sectors
            .filter { it.metrics.isBushTexture && !it.metrics.isFlatToxicZone }
            .maxByOrNull { it.metrics.bushScore }

        return if (safeBush != null) {
            Analysis(Decision.SEEK_BUSH, center, safeBush, sectors.size)
        } else {
            Analysis(Decision.UNKNOWN, center, sectorCount = sectors.size)
        }
    }

    private fun sampleSectors(bitmap: Bitmap): List<Sector> =
        SECTOR_DIRECTIONS.mapNotNull { (directionX, directionY) ->
            val metrics = sampleRegion(
                bitmap,
                PLAYER_X + (directionX * SECTOR_OFFSET).toInt(),
                PLAYER_Y + (directionY * SECTOR_OFFSET).toInt()
            ) ?: return@mapNotNull null
            Sector(directionX, directionY, metrics)
        }

    private fun sampleRegion(bitmap: Bitmap, centerX: Int, centerY: Int): RegionMetrics? {
        val luminance = Array(GRID_SIZE) { FloatArray(GRID_SIZE) }
        val toxicGreen = Array(GRID_SIZE) { BooleanArray(GRID_SIZE) }
        val hsv = FloatArray(3)
        var luminanceSum = 0.0
        var luminanceSquaredSum = 0.0
        var greenCount = 0

        for (gridY in 0 until GRID_SIZE) {
            val virtualY = centerY + (gridY - GRID_CENTER) * GRID_SPACING
            val pixelY = virtualY * bitmap.height / VIRTUAL_HEIGHT
            if (pixelY !in 0 until bitmap.height) return null

            for (gridX in 0 until GRID_SIZE) {
                val virtualX = centerX + (gridX - GRID_CENTER) * GRID_SPACING
                val pixelX = virtualX * bitmap.width / VIRTUAL_WIDTH
                if (pixelX !in 0 until bitmap.width) return null

                val color = try {
                    bitmap.getPixel(pixelX, pixelY)
                } catch (error: RuntimeException) {
                    Log.e(TAG, "Screenshot-Pixel konnte nicht gelesen werden", error)
                    return null
                }

                val red = Color.red(color)
                val green = Color.green(color)
                val blue = Color.blue(color)
                val gray = (0.299f * red + 0.587f * green + 0.114f * blue)
                luminance[gridY][gridX] = gray
                luminanceSum += gray
                luminanceSquaredSum += gray * gray

                Color.RGBToHSV(red, green, blue, hsv)
                val isToxicGreen = hsv[0] in ZONE_HUE_MIN..ZONE_HUE_MAX &&
                    hsv[1] >= ZONE_MIN_SATURATION &&
                    hsv[2] >= ZONE_MIN_VALUE
                toxicGreen[gridY][gridX] = isToxicGreen
                if (isToxicGreen) greenCount++
            }
        }

        var edgeCount = 0
        var edgeSamples = 0
        for (y in 1 until GRID_SIZE - 1) {
            for (x in 1 until GRID_SIZE - 1) {
                val gx =
                    -luminance[y - 1][x - 1] + luminance[y - 1][x + 1] +
                        -2f * luminance[y][x - 1] + 2f * luminance[y][x + 1] +
                        -luminance[y + 1][x - 1] + luminance[y + 1][x + 1]
                val gy =
                    -luminance[y - 1][x - 1] - 2f * luminance[y - 1][x] - luminance[y - 1][x + 1] +
                        luminance[y + 1][x - 1] + 2f * luminance[y + 1][x] + luminance[y + 1][x + 1]
                if (kotlin.math.abs(gx) + kotlin.math.abs(gy) >= SOBEL_EDGE_THRESHOLD) {
                    edgeCount++
                }
                edgeSamples++
            }
        }

        val sampleCount = GRID_SIZE * GRID_SIZE
        val mean = luminanceSum / sampleCount
        val variance = (luminanceSquaredSum / sampleCount - mean * mean).coerceAtLeast(0.0)
        var interiorGreenCount = 0
        var interiorSamples = 0
        for (y in 1 until GRID_SIZE - 1) {
            for (x in 1 until GRID_SIZE - 1) {
                if (toxicGreen[y][x]) interiorGreenCount++
                interiorSamples++
            }
        }

        return RegionMetrics(
            edgeDensity = edgeCount.toFloat() / edgeSamples,
            normalizedVariance = (variance / MAX_GRAY_VARIANCE).toFloat(),
            toxicGreenRatio = interiorGreenCount.toFloat() / interiorSamples
        )
    }

    private fun act(analysis: Analysis) {
        when (analysis.decision) {
            Decision.BUSH -> {
                performGesture(buildCircularJoystickPath(rotationPhase), GESTURE_DURATION_MS)
                rotationPhase = (rotationPhase + ROTATION_STEP_RADIANS) % FULL_ROTATION_RADIANS
            }

            Decision.ZONE, Decision.SEEK_BUSH -> {
                val target = analysis.target
                if (target == null) {
                    Log.w(TAG, "Zone erkannt, aber kein sicherer Buschsektor gefunden")
                } else {
                    performGesture(buildDirectionalPath(target.directionX, target.directionY), GESTURE_DURATION_MS)
                }
            }

            Decision.NO_SAFE_BUSH -> {
                Log.w(TAG, "Zone erkannt; in den erfassten Sektoren wurde kein sicherer Busch gefunden")
            }

            Decision.UNKNOWN, Decision.INVALID_BITMAP -> {
                Unit
            }
        }
    }

    private fun buildDirectionalPath(directionX: Float, directionY: Float): List<PointF> {
        val targetX = (JOYSTICK_X + directionX * JOYSTICK_RADIUS)
            .coerceIn(0f, VIRTUAL_WIDTH.toFloat())
        val targetY = (JOYSTICK_Y + directionY * JOYSTICK_RADIUS)
            .coerceIn(0f, VIRTUAL_HEIGHT.toFloat())
        return listOf(PointF(JOYSTICK_X, JOYSTICK_Y), PointF(targetX, targetY))
    }

    private fun buildCircularJoystickPath(phase: Float): List<PointF> {
        val points = ArrayList<PointF>(CIRCLE_PATH_POINTS + 1)
        points += PointF(JOYSTICK_X, JOYSTICK_Y)
        for (step in 0..CIRCLE_PATH_POINTS) {
            val angle = phase + FULL_ROTATION_RADIANS * step / CIRCLE_PATH_POINTS
            points += PointF(
                (JOYSTICK_X + JOYSTICK_RADIUS * kotlin.math.cos(angle))
                    .coerceIn(0f, VIRTUAL_WIDTH.toFloat()),
                (JOYSTICK_Y + JOYSTICK_RADIUS * kotlin.math.sin(angle))
                    .coerceIn(0f, VIRTUAL_HEIGHT.toFloat())
            )
        }
        return points
    }

    private fun logAnalysis(analysis: Analysis) {
        val center = analysis.center
        Log.d(
            TAG,
            "decision=${analysis.decision}, edges=${center?.edgeDensity}, " +
                "variance=${center?.normalizedVariance}, toxicGreen=${center?.toxicGreenRatio}, " +
                "sectors=${analysis.sectorCount}, target=${analysis.target?.directionX},${analysis.target?.directionY}"
        )
    }

    private companion object {
        const val TAG = "BotEngine"
        const val VIRTUAL_WIDTH = 1280
        const val VIRTUAL_HEIGHT = 720
        const val PLAYER_X = 640
        const val PLAYER_Y = 380
        const val JOYSTICK_X = 150f
        const val JOYSTICK_Y = 600f
        const val JOYSTICK_RADIUS = 95f
        const val GRID_SIZE = 15
        const val GRID_CENTER = GRID_SIZE / 2
        const val GRID_SPACING = 4
        const val SECTOR_OFFSET = 105
        const val SOBEL_EDGE_THRESHOLD = 150f
        const val MAX_GRAY_VARIANCE = 16_256.25
        const val VARIANCE_SCORE_SCALE = 0.12f
        const val BUSH_MIN_EDGE_DENSITY = 0.16f
        const val BUSH_MIN_NORMALIZED_VARIANCE = 0.025f
        const val ZONE_HUE_MIN = 65f
        const val ZONE_HUE_MAX = 165f
        const val ZONE_MIN_SATURATION = 0.38f
        const val ZONE_MIN_VALUE = 0.28f
        const val ZONE_MIN_GREEN_RATIO = 0.28f
        const val ZONE_MAX_EDGE_DENSITY = 0.20f
        const val SECTOR_COUNT = 8
        const val CIRCLE_PATH_POINTS = 16
        const val ROTATION_STEP_RADIANS = 0.5f
        const val FULL_ROTATION_RADIANS = (2.0 * Math.PI).toFloat()
        const val CAPTURE_TIMEOUT_MS = 2_000L
        const val GESTURE_DURATION_MS = 450L
        const val TICK_INTERVAL_MS = 500L
        val SECTOR_DIRECTIONS = listOf(
            1f to 0f,
            0.7071f to 0.7071f,
            0f to 1f,
            -0.7071f to 0.7071f,
            -1f to 0f,
            -0.7071f to -0.7071f,
            0f to -1f,
            0.7071f to -0.7071f
        )
    }
}
