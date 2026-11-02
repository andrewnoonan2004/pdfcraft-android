package com.andrewnoonan.pdfcraft.bridge

import kotlinx.serialization.json.Json

object PlatformEffectsParser {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    fun parse(jsonString: String): PlatformEffects {
        if (jsonString.isBlank()) {
            return PlatformEffects()
        }
        return runCatching {
            json.decodeFromString<PlatformEffects>(jsonString)
        }.getOrElse {
            PlatformEffects()
        }
    }
}
