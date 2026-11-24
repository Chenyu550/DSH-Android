package io.github.jackwener.dsh.core.platform.adb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.math.BigInteger

/**
 * #71: SPAKE2 must match BoringSSL's bit for bit (adbd runs BoringSSL). The vectors below come from
 * BoringSSL's own spake25519.c (tag 0.20240913.0) driven by a tiny C harness with a fixed RNG:
 * the private keys are BoringSSL's ctx->private_key, names are adb's "adb pair client\0" / "adb pair server\0".
 */
class Spake2Test {
    private fun hex(s: String) = Spake2.hex(s)
    private fun priv(h: String) = Ed25519.scalarLE(hex(h))

    private val alicePriv = priv("08876206dbd389daf6bfe6dcb78f6dabb148ae807c267ccffc932b932f453b0b")
    private val bobPriv = priv("d8d30bd9d1f5b877856beb312b9a2f6b519a038f5937bc4bf7fa2f7d5b49fc16")

    private fun check(password: String, aliceMsg: String, bobMsg: String, key: String) {
        val alice = Spake2(Spake2.Role.Alice, AdbPairing.CLIENT_NAME, AdbPairing.SERVER_NAME, alicePriv)
        val bob = Spake2(Spake2.Role.Bob, AdbPairing.SERVER_NAME, AdbPairing.CLIENT_NAME, bobPriv)
        assertArrayEquals(hex(aliceMsg), alice.generateMessage(password.toByteArray()))
        assertArrayEquals(hex(bobMsg), bob.generateMessage(password.toByteArray()))
        assertArrayEquals(hex(key), alice.processMessage(hex(bobMsg)))
        assertArrayEquals(hex(key), bob.processMessage(hex(aliceMsg)))
    }

    @Test fun matchesBoringSslForASixDigitCode() = check("123456",
        "67dc2e7d76f61659a6b1f93fc46175f95dc6c90036b3e196ad6709b5bdfce915",
        "d553a7d2afaca12d92fdd5f8f7f7b8fa09136086c3ff00ed6e06ad1c51c5a0da",
        "0729f91730706b86116a7482d1c4d4ce8cc7688a8e224f4a4d7b6538c3d4156536f4b6af5438c38ab99f05949a7085b26ea9402f42bf27daa6d57e56713cfc88")

    @Test fun matchesBoringSslForALongPassword() = check("654321-exported-keying-material-0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLM",
        "bc8e43780c4d324da9572a4ee3b7a75488ef09f6d498207e2de7e9c2a94b2898",
        "d3b5604d6e5cb8b327ac04f9e375cec7ef505ee4f30c4763a4f7442b2f571867",
        "ab1c64697139b2bce1e4e5f0930058da374f20b6db05f4f61f61324099f5af48985aeacc807a562b747a9d34c0637ac2f94cb70c6c27243ae4db89c85fbb11e6")

    @Test fun fixedPointsDecodeToBoringSslsCoordinates() {
        fun x(p: Ed25519.Point) = p.x * p.z.modInverse(Ed25519.P) % Ed25519.P
        assertEquals(BigInteger("31406539342727633121250288103050113562375374900226415211311216773867585644232"), x(Spake2.M))
        assertEquals(BigInteger("49918732221787544735331783592030787422991506689877079631459872391322455579424"), x(Spake2.N))
        // RFC 8032 test 1: the base point encodes as 5866…66 and has order l.
        assertArrayEquals(hex("5866666666666666666666666666666666666666666666666666666666666666"), Ed25519.encode(Ed25519.B))
        assertArrayEquals(Ed25519.encode(Ed25519.IDENTITY), Ed25519.encode(Ed25519.mul(Ed25519.B, Ed25519.L)))
    }

    @Test fun randomKeysAgreeOnlyWithTheSamePassword() {
        val a = Spake2(Spake2.Role.Alice, AdbPairing.CLIENT_NAME, AdbPairing.SERVER_NAME)
        val b = Spake2(Spake2.Role.Bob, AdbPairing.SERVER_NAME, AdbPairing.CLIENT_NAME)
        val am = a.generateMessage("111111".toByteArray()); val bm = b.generateMessage("111111".toByteArray())
        assertArrayEquals(a.processMessage(bm), b.processMessage(am))
        val c = Spake2(Spake2.Role.Alice, AdbPairing.CLIENT_NAME, AdbPairing.SERVER_NAME)
        val d = Spake2(Spake2.Role.Bob, AdbPairing.SERVER_NAME, AdbPairing.CLIENT_NAME)
        val cm = c.generateMessage("111111".toByteArray()); val dm = d.generateMessage("222222".toByteArray())
        assertFalse(c.processMessage(dm).contentEquals(d.processMessage(cm)))
    }
}
