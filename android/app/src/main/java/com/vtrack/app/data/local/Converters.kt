package com.vtrack.app.data.local

import androidx.room.TypeConverter
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Entities keep primitives only; list-shaped fields travel as JSON text. */
class Converters {

    @TypeConverter
    fun stringListToJson(value: List<String>?): String =
        if (value == null) "[]" else json.encodeToString(ListSerializer(String.serializer()), value)

    @TypeConverter
    fun jsonToStringList(value: String?): List<String> =
        if (value.isNullOrBlank()) emptyList()
        else runCatching { json.decodeFromString(ListSerializer(String.serializer()), value) }
            .getOrDefault(emptyList())

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
    }
}
