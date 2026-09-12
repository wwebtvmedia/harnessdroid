package com.ai.harnessdroid.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

class EmbeddingCodecTest {

    private fun sampleVector(dim: Int, seed: Int = 42): FloatArray {
        val out = FloatArray(dim)
        var x = seed.toLong()
        for (i in 0 until dim) {
            x = x * 6364136223846793005L + 1442695040888963407L
            out[i] = ((x ushr 33) % 2000 - 1000) / 1000f   // [-1, 1]
        }
        return out
    }

    @Test
    fun roundtripKeepsVectorWithinQuantizationNoise() {
        val vector = sampleVector(896)
        val (q8, scale) = EmbeddingCodec.quantize(vector)
        val restored = EmbeddingCodec.dequantize(q8, scale)

        var sumSq = 0f
        for (i in vector.indices) {
            val d = vector[i] - restored[i]
            sumSq += d * d
        }
        val rms = sqrt(sumSq / vector.size)
        // int8 across the [-1,1] range: quantization step is ~1/127, so the
        // RMS error must stay far below the ~2% budget.
        assertTrue("rms=$rms", rms < 0.02f)
    }

    @Test
    fun packedLayoutMatchesProviderFormat() {
        // The provider packs [float32 scale][int8 x dim] little-endian with
        // scale = maxAbs / 127; harnessDroid must produce identical bytes.
        val vector = floatArrayOf(0.5f, -1f, 0f, 127f, -254f)
        val expectedScale = 254f / 127f

        val packed = EmbeddingCodec.encodeOne(vector)
        assertEquals(4 + vector.size, packed.size)

        val buf = ByteBuffer.wrap(packed).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(expectedScale, buf.float, 1e-6f)
        // 0.5/(2)=0.25 -> 0 ; -1/2=-0.5 -> 0 (truncation toward zero) ;
        // 0 -> 0 ; 127/2=63.5 -> 63 ; -254/2=-127
        assertEquals(0, buf.get().toInt())
        assertEquals(0, buf.get().toInt())
        assertEquals(0, buf.get().toInt())
        assertEquals(63, buf.get().toInt())
        assertEquals(-127, buf.get().toInt())
    }

    @Test
    fun decodeFlatRoundTripsMultipleVectors() {
        val dim = 8
        val vectors = listOf(sampleVector(dim, 1), sampleVector(dim, 2), sampleVector(dim, 3))
        val packed = vectors.flatMap { EmbeddingCodec.encodeOne(it).toList() }.toByteArray()
        assertEquals(3 * EmbeddingCodec.bytesPerVector(dim), packed.size)

        val flat = EmbeddingCodec.decodeFlat(packed, dim)
        assertEquals(3 * dim, flat.size)
        vectors.forEachIndexed { t, v ->
            val restored = flat.copyOfRange(t * dim, (t + 1) * dim)
            for (i in 0 until dim) assertEquals(v[i], restored[i], 0.02f)
        }
    }

    @Test
    fun base64HelpersRoundTrip() {
        val vector = sampleVector(1536)
        val b64 = EmbeddingCodec.encodeToBase64(vector)
        val restored = EmbeddingCodec.decodeFromBase64(b64, 1536)
        assertEquals(1536, restored.size)
        for (i in 0 until 1536) assertEquals(vector[i], restored[i], 0.02f)
    }

    @Test
    fun cosineAndL2Behave() {
        val a = floatArrayOf(1f, 0f, 0f)
        val b = floatArrayOf(1f, 0f, 0f)
        val c = floatArrayOf(-1f, 0f, 0f)
        assertEquals(1f, EmbeddingCodec.cosine(a, b), 1e-6f)
        assertEquals(-1f, EmbeddingCodec.cosine(a, c), 1e-6f)
        assertEquals(2f, EmbeddingCodec.l2(a, c), 1e-6f)
        // Zero vector must not produce NaN.
        assertEquals(0f, EmbeddingCodec.cosine(a, floatArrayOf(0f, 0f, 0f)), 1e-6f)
    }

    @Test
    fun meanAveragesElementWise() {
        val mean = EmbeddingCodec.mean(listOf(floatArrayOf(1f, 3f), floatArrayOf(3f, 5f)))
        assertEquals(2f, mean[0], 1e-6f)
        assertEquals(4f, mean[1], 1e-6f)
    }
}
