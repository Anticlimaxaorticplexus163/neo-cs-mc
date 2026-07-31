package gg.earu.chatsounds.util

import java.security.MessageDigest

/** Lowercase hex SHA-1, matching GMod's util.SHA1 — cache keys must stay compatible in spirit. */
fun sha1Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }

fun sha1Hex(str: String): String = sha1Hex(str.toByteArray(Charsets.UTF_8))
