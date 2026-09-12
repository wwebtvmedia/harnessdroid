package com.ai.harnessdroid.memory

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Quantization format for embeddings crossing the Binder and stored on disk:
 * per vector, [float32 scale][int8 x dim] little-endian, with
 * scale = maxAbs / 127 (int8 range). ~8x smaller than float32 and far below
 * the 512 KB per-Binder-transaction limit.
 *
 * This is byte-identical to LLMProvider's com.tree4five.gguf.EmbeddingCodec:
 * the two apps exchange vectors through ILLMService and must agree exactly.
 */
object EmbeddingCodec {

    /** Bytes occupied by one packed vector of the given dimension. */
    fun bytesPerVector(dim: Int): Int = 4 + dim

    /** Packs a single vector into [float32 scale][int8 x dim]. */
    fun encodeOne(vector: FloatArray): ByteArray {
        val out = ByteArray(bytesPerVector(vector.size))
        val buf = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        val scale = scaleOf(vector)
        buf.putFloat(scale)
        for (x in vector) buf.put(quantize(x, scale))
        return out
    }

    /** Unpacks a stream of packed vectors into a flat [count * dim] array. */
    fun decodeFlat(bytes: ByteArray, dim: Int): FloatArray {
        require(dim > 0) { "dim must be positive" }
        val perVector = bytesPerVector(dim)
        require(bytes.size % perVector == 0) {
            "byte size ${bytes.size} is not a multiple of $perVector"
        }
        val count = bytes.size / perVector
        val out = FloatArray(count * dim)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (t in 0 until count) {
            val scale = buf.float
            val base = t * dim
            for (d in 0 until dim) out[base + d] = buf.get() * scale
        }
        return out
    }

    /**
     * Quantizes one vector; returns the raw int8 payload (WITHOUT the scale
     * header) plus the scale, for storage where scale is kept in its own
     * field (VectorStore JSON).
     */
    fun quantize(vector: FloatArray): Pair<ByteArray, Float> {
        val scale = scaleOf(vector)
        val out = ByteArray(vector.size)
        for (i in vector.indices) out[i] = quantize(vector[i], scale)
        return out to scale
    }

    /** Rebuilds a vector from a raw int8 payload and its scale. */
    fun dequantize(q8: ByteArray, scale: Float): FloatArray {
        val out = FloatArray(q8.size)
        for (i in q8.indices) out[i] = q8[i] * scale
        return out
    }

    fun encodeToBase64(vector: FloatArray): String =
        Base64.getEncoder().encodeToString(encodeOne(vector))

    fun decodeFromBase64(b64: String, dim: Int): FloatArray =
        decodeFlat(Base64.getDecoder().decode(b64), dim)

    /** Cosine similarity in [-1, 1]; 0 for zero-length inputs. */
    fun cosine(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "vector dims differ: ${a.size} vs ${b.size}" }
        var dot = 0f
        var na = 0f
        var nb = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        if (na == 0f || nb == 0f) return 0f
        return (dot / sqrt(na * nb)).coerceIn(-1f, 1f)
    }

    /** Euclidean distance between two vectors. */
    fun l2(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "vector dims differ: ${a.size} vs ${b.size}" }
        var sum = 0f
        for (i in a.indices) {
            val d = a[i] - b[i]
            sum += d * d
        }
        return sqrt(sum)
    }

    /** Element-wise mean of a non-empty list of vectors. */
    fun mean(vectors: List<FloatArray>): FloatArray {
        require(vectors.isNotEmpty()) { "mean of empty list" }
        val dim = vectors[0].size
        val out = FloatArray(dim)
        for (v in vectors) {
            require(v.size == dim) { "vector dims differ: ${v.size} vs $dim" }
            for (i in 0 until dim) out[i] = out[i] + v[i]
        }
        val n = vectors.size
        for (i in 0 until dim) out[i] = out[i] / n
        return out
    }

    private fun scaleOf(vector: FloatArray): Float {
        var maxAbs = 0f
        for (x in vector) {
            val a = abs(x)
            if (a > maxAbs) maxAbs = a
        }
        return if (maxAbs == 0f) 1f else maxAbs / 127f
    }

    // Same truncation-toward-zero as the provider: both sides must produce
    // identical int8 payloads for identical float input.
    private fun quantize(x: Float, scale: Float): Byte =
        ((x / scale).toInt().coerceIn(-127, 127)).toByte()
}
