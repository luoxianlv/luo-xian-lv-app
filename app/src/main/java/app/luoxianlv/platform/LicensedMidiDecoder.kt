package app.luoxianlv.platform

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

internal fun ByteArray.startsWithMidi() =
    size >= 4 && copyOfRange(0, 4).contentEquals(byteArrayOf(0x4d, 0x54, 0x68, 0x64))

internal fun ByteArray.looksLikeLicense() = toString(Charsets.UTF_8).trimStart().startsWith('{')

/** Decrypt licensed MIDI in memory; the caller owns downloading and compiling. */
internal class LicensedMidiDecoder(private val download: (String) -> ByteArray) {
    fun decode(license: JSONObject): ByteArray {
        require(license.optString("alg") == "aes-256-gcm") { "许可证算法不支持" }
        val session = license.getJSONObject("session")
        require(session.optString("alg") == "aes-256-gcm-session") { "许可证会话算法不支持" }
        val decode = { key: String -> Base64.decode(key, Base64.DEFAULT) }
        val sessionKey = decode(session.getString("key"))
        val sessionNonce = decode(session.getString("nonce"))
        val wrappedDek = decode(session.getString("wrappedDek"))
        val songNonce = decode(license.getString("nonce"))
        val songAad = decode(license.getString("aad"))
        val sessionAad =
            "${license.getString(
                "songId"
            )}\u0000${license.getInt("version")}\u0000${session.getInt("expiresIn")}"
                .toByteArray()
        val dek = aesGcm(sessionKey, sessionNonce, sessionAad, wrappedDek)
        val encrypted = download(license.getJSONObject("download").getString("url"))
        val midi = aesGcm(dek, songNonce, songAad, encrypted)
        require(midi.startsWithMidi()) { "许可证解密结果不是 MIDI" }
        return midi
    }

    private fun aesGcm(
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
        ciphertext: ByteArray,
    ): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext)
    }
}
