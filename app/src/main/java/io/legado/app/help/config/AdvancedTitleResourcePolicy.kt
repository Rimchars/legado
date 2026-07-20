package io.legado.app.help.config

import com.google.gson.stream.JsonReader
import java.io.File
import java.io.StringReader

internal object AdvancedTitleResourcePolicy {

    data class References(
        val images: Set<String>,
        val fonts: Set<String>
    )

    fun validate(root: File, resources: List<PackageResource>, json: String) {
        val references = references(json)
        references.images.forEach { reference ->
            val file = PackageResourcePolicy.resolve(
                root,
                resources,
                reference,
                PackageResourcePolicy.TYPE_IMAGE
            )
            requireNotNull(file) { "advanced title image is missing: $reference" }
            PackageResourcePolicy.validateResolvedFile(
                file,
                PackageResourcePolicy.TYPE_IMAGE,
                reference
            )
        }
        references.fonts.forEach { reference ->
            val file = PackageResourcePolicy.resolve(
                root,
                resources,
                reference,
                PackageResourcePolicy.TYPE_FONT
            )
            requireNotNull(file) { "advanced title font is missing: $reference" }
            PackageResourcePolicy.validateResolvedFile(
                file,
                PackageResourcePolicy.TYPE_FONT,
                reference
            )
        }
    }

    fun references(json: String): References {
        val images = linkedSetOf<String>()
        val fonts = linkedSetOf<String>()
        JsonReader(StringReader(json)).use { reader ->
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "assets" -> readAssets(reader, images)
                    "fonts" -> readFonts(reader, fonts)
                    else -> reader.skipValue()
                }
            }
        }
        return References(images, fonts)
    }

    private fun readAssets(reader: JsonReader, output: MutableSet<String>) {
        reader.beginArray()
        while (reader.hasNext()) {
            var directory = ""
            var path = ""
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "u" -> directory = reader.nextString()
                    "p" -> path = reader.nextString()
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            if (path.isBlank() || path.startsWith("data:image", ignoreCase = true)) continue
            output += if (path.startsWith(PackageResourcePolicy.ALIAS_PREFIX, ignoreCase = true)) {
                path
            } else {
                directory + path
            }
        }
        reader.endArray()
    }

    private fun readFonts(reader: JsonReader, output: MutableSet<String>) {
        reader.beginObject()
        while (reader.hasNext()) {
            if (reader.nextName() != "list") {
                reader.skipValue()
                continue
            }
            reader.beginArray()
            while (reader.hasNext()) {
                reader.beginObject()
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "fFamily", "fName" -> reader.nextString()
                            .takeIf {
                                it.startsWith(PackageResourcePolicy.ALIAS_PREFIX, ignoreCase = true)
                            }
                            ?.let(output::add)
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
            }
            reader.endArray()
        }
        reader.endObject()
    }
}
