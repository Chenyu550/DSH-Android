package io.github.jackwener.dsh.core.platform.adb

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * SPAKE2 over edwards25519, byte-for-byte BoringSSL's `SPAKE2_*` (crypto/curve25519/spake25519.c),
 * which adb's pairing uses (pairing_auth.cpp: client = Alice "adb pair client\0", server = Bob).
 * Plain BigInteger arithmetic with complete twisted-Edwards formulas: the protocol runs once per
 * pairing, so clarity beats speed here, and exact (unreduced) scalars keep BoringSSL's cofactor
 * handling intact. Checked against vectors produced by BoringSSL itself (Spake2Test).
 */
class Spake2 internal constructor(
    private val role: Role,
    private val myName: ByteArray,
    private val theirName: ByteArray,
    /** 8·(r mod l), as BoringSSL keeps it (multiplying by the cofactor clears small-order parts). */
    private val privateKey: BigInteger,
) {
    enum class Role { Alice, Bob }

    constructor(role: Role, myName: ByteArray, theirName: ByteArray, random: SecureRandom = SecureRandom()) :
        this(role, myName, theirName, ByteArray(64).also(random::nextBytes).let { Ed25519.scalarLE(it).mod(Ed25519.L).shiftLeft(3) })

    private var passwordScalar: BigInteger? = null
    private var passwordHash: ByteArray? = null
    private var myMsg: ByteArray? = null

    /** Our 32-byte message for [password]: B·private + (M or N)·h(password). */
    fun generateMessage(password: ByteArray): ByteArray {
        check(myMsg == null) { "message already generated" }
        val hash = MessageDigest.getInstance("SHA-512").digest(password)
        var s = Ed25519.scalarLE(hash).mod(Ed25519.L)
        // BoringSSL's fix for its missing left_shift_3: add l, 2l, 4l as needed so s is a multiple of 8.
        if (s.testBit(0)) s += Ed25519.L
        if (s.testBit(1)) s += Ed25519.L.shiftLeft(1)
        if (s.testBit(2)) s += Ed25519.L.shiftLeft(2)
        passwordScalar = s
        passwordHash = hash
        val mask = Ed25519.mul(if (role == Role.Alice) M else N, s)
        return Ed25519.encode(Ed25519.add(Ed25519.mul(Ed25519.B, privateKey), mask)).also { myMsg = it }
    }

    /** The shared 64-byte key from the peer's message; throws on a message that is not a curve point. */
    fun processMessage(theirMsg: ByteArray): ByteArray {
        val mine = checkNotNull(myMsg) { "generateMessage first" }
        require(theirMsg.size == 32) { "SPAKE2 message must be 32 bytes" }
        val qStar = Ed25519.decode(theirMsg) ?: throw IllegalArgumentException("SPAKE2 message is not a curve point")
        val peersMask = Ed25519.mul(if (role == Role.Alice) N else M, passwordScalar!!)
        val dh = Ed25519.encode(Ed25519.mul(Ed25519.add(qStar, Ed25519.negate(peersMask)), privateKey))
        val sha = MessageDigest.getInstance("SHA-512")
        fun put(b: ByteArray) {
            var len = b.size.toLong()
            repeat(8) { sha.update((len and 0xff).toByte()); len = len ushr 8 }
            sha.update(b)
        }
        if (role == Role.Alice) { put(myName); put(theirName); put(mine); put(theirMsg) }
        else { put(theirName); put(myName); put(theirMsg); put(mine) }
        put(dh); put(passwordHash!!)
        return sha.digest()
    }

    companion object {
        /** BoringSSL's fixed points (spake25519.c: "edwards25519 point generation seed (M)" / "(N)"). */
        internal val M = Ed25519.decode(hex("5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e"))!!
        internal val N = Ed25519.decode(hex("10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778"))!!

        internal fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}

/** edwards25519 points in extended coordinates (X:Y:Z:T), x = X/Z, y = Y/Z, xy = T/Z. */
internal object Ed25519 {
    val P: BigInteger = BigInteger.TWO.pow(255) - BigInteger.valueOf(19)
    val L: BigInteger = BigInteger.TWO.pow(252) + BigInteger("27742317777372353535851937790883648493")
    private val D: BigInteger = BigInteger.valueOf(-121665).mod(P) * BigInteger.valueOf(121666).modInverse(P) % P
    private val D2 = D.shiftLeft(1).mod(P)
    private val SQRT_M1: BigInteger = BigInteger.TWO.modPow((P - BigInteger.ONE).shiftRight(2), P)

    class Point(val x: BigInteger, val y: BigInteger, val z: BigInteger, val t: BigInteger)

    val IDENTITY = Point(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)
    val B: Point = run {
        val y = BigInteger.valueOf(4) * BigInteger.valueOf(5).modInverse(P) % P
        decode(encodeAffine(recoverX(y, false)!!, y))!!
    }

    fun scalarLE(b: ByteArray) = BigInteger(1, b.reversedArray())

    /** Complete addition for a = -1 (add-2008-hwcd-3): valid for every pair, small-order points included. */
    fun add(p: Point, q: Point): Point {
        val a = (p.y - p.x) * (q.y - q.x) % P
        val b = (p.y + p.x) * (q.y + q.x) % P
        val c = p.t * D2 % P * q.t % P
        val d = p.z.shiftLeft(1) * q.z % P
        val e = b - a; val f = d - c; val g = d + c; val h = b + a
        return Point((e * f).mod(P), (g * h).mod(P), (f * g).mod(P), (e * h).mod(P))
    }

    fun negate(p: Point) = Point((-p.x).mod(P), p.y, p.z, (-p.t).mod(P))

    /** [p]·[k] for any non-negative k (no reduction mod l: the exact integer, like BoringSSL). */
    fun mul(p: Point, k: BigInteger): Point {
        var r = IDENTITY
        for (i in k.bitLength() - 1 downTo 0) {
            r = add(r, r)
            if (k.testBit(i)) r = add(r, p)
        }
        return r
    }

    fun encode(p: Point): ByteArray {
        val zi = p.z.modInverse(P)
        return encodeAffine(p.x * zi % P, p.y * zi % P)
    }

    private fun encodeAffine(x: BigInteger, y: BigInteger): ByteArray {
        val out = ByteArray(32)
        val be = y.toByteArray()
        for (i in 0 until minOf(32, be.size)) out[i] = be[be.size - 1 - i]
        if (x.testBit(0)) out[31] = (out[31].toInt() or 0x80).toByte()
        return out
    }

    /** RFC 8032 point decoding; null when [b] is not on the curve. */
    fun decode(b: ByteArray): Point? {
        if (b.size != 32) return null
        val sign = (b[31].toInt() and 0x80) != 0
        val yb = b.copyOf().also { it[31] = (it[31].toInt() and 0x7f).toByte() }
        val y = scalarLE(yb)
        if (y >= P) return null
        val x = recoverX(y, sign) ?: return null
        return Point(x, y, BigInteger.ONE, x * y % P)
    }

    private fun recoverX(y: BigInteger, sign: Boolean): BigInteger? {
        val y2 = y * y % P
        val u = (y2 - BigInteger.ONE).mod(P)
        val v = (D * y2 + BigInteger.ONE) % P
        val x2 = u * v.modInverse(P) % P
        if (x2.signum() == 0) return if (sign) null else BigInteger.ZERO
        var x = x2.modPow((P + BigInteger.valueOf(3)).shiftRight(3), P)
        if ((x * x - x2).mod(P).signum() != 0) x = x * SQRT_M1 % P
        if ((x * x - x2).mod(P).signum() != 0) return null
        if (x.testBit(0) != sign) x = P - x
        return x
    }
}
