package org.jetbrains.kotlin.gradle.plugin.android

import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.io.Input
import com.esotericsoftware.kryo.io.Output
import com.esotericsoftware.kryo.serializers.JavaSerializer
import de.javakaffee.kryoserializers.guava.ImmutableListSerializer
import de.javakaffee.kryoserializers.guava.ImmutableMapSerializer
import de.javakaffee.kryoserializers.guava.ImmutableMultimapSerializer
import de.javakaffee.kryoserializers.guava.ImmutableSetSerializer
import org.gradle.api.internal.AsmBackedClassGenerator
import org.objenesis.strategy.StdInstantiatorStrategy
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.*

class LintObjectsSerializationFacade {
    private val kryo = createKryo()
    private val classGenerator = AsmBackedClassGenerator()

    private fun createKryo() = Kryo().apply {
        instantiatorStrategy = Kryo.DefaultInstantiatorStrategy(StdInstantiatorStrategy())

        register(EnumMap::class.java, JavaSerializer())
        register(File::class.java, JavaSerializer())
        setDefaultSerializer(NameFilteredFieldSerializer.Factory)
        ImmutableListSerializer.registerSerializers(this)
        ImmutableSetSerializer.registerSerializers(this)
        ImmutableMapSerializer.registerSerializers(this)
        ImmutableMultimapSerializer.registerSerializers(this)
    }

    fun serialize(obj: Any): ByteArray {
        val bos = ByteArrayOutputStream()
        val output = Output(bos)
        kryo.writeObject(output, obj)
        output.close()
        return bos.toByteArray()
    }

    fun deserialize(bytes: ByteArray, rawClassName: String): Any? {
        val clazz = when {
            rawClassName.endsWith("_Decorated") -> {
                val classNameWithoutDecorated = rawClassName.substringBeforeLast("_Decorated")
                val originalClass = Class.forName(classNameWithoutDecorated, true, javaClass.classLoader)
                classGenerator.generate(originalClass)
            }
            else -> Class.forName(rawClassName, false, javaClass.classLoader)
        }

        return kryo.readObject(Input(ByteArrayInputStream(bytes)), clazz)
    }
}

class LintObjectsMapper(newClassLoader: ClassLoader) {
    private val ourWorld = LintObjectsSerializationFacade()

    private val otherWorld = Class.forName(
            LintObjectsSerializationFacade::class.java.name, true, newClassLoader).newInstance()
    private val deserializeMethod = otherWorld.javaClass.methods
            .single { it.name == LintObjectsSerializationFacade::deserialize.name }

    fun map(obj: Any?): Any? = if (obj == null) null else mapNotNull(obj)

    fun mapNotNull(obj: Any): Any {
        val rawClassName = obj.javaClass.let { it.canonicalName ?: it.name }

        try {
            val bytes = ourWorld.serialize(obj)
            return deserializeMethod.invoke(otherWorld, bytes, rawClassName)
        } catch (e: Throwable) {
            throw RuntimeException("Can't map $rawClassName", e)
        }
    }
}