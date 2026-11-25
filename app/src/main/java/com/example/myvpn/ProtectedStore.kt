package com.example.myvpn

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject

internal class SavedSubscription(val label: String, val link: String, val sendDeviceId: Boolean, val nodes: List<ProxyNode>) {
    fun namedNodes(): List<ProxyNode> = nodes.map { node ->
        ProxyNode("$label | ${node.name}", node.protocol, node.host, node.port, node.credential, node.options, node.link)
    }
}

internal class ProtectedStore(context: Context) {
    private val file = EncryptedFile(context, "vpn-profile.enc", "myvpn.profile.aes.v1")

    @Synchronized fun read(): List<SavedSubscription> {
        val data = JSONArray(file.read() ?: return emptyList())
        return (0 until data.length()).map { index ->
            val row = data.getJSONObject(index)
            val links = row.getJSONArray("nodes")
            val nodes = (0 until links.length()).joinToString("\n") { links.getString(it) }
            SavedSubscription(row.getString("name"), row.getString("link"), row.getBoolean("deviceId"), SubscriptionParser.parse(nodes))
        }
    }

    @Synchronized fun write(subscriptions: List<SavedSubscription>) {
        val data = JSONArray()
        subscriptions.forEach { sub ->
            data.put(JSONObject().put("name", sub.label).put("link", sub.link)
                .put("deviceId", sub.sendDeviceId).put("nodes", JSONArray(sub.nodes.map { it.link })))
        }
        file.write(data.toString())
    }
}

internal class EncryptedFile(context: Context, name: String, private val alias: String) {
    private val file = AtomicFile(File(context.noBackupFilesDir, name))

    fun read(): String? {
        val bytes = try { file.readFully() } catch (error: java.io.FileNotFoundException) {
            if (file.baseFile.exists()) throw error
            return null
        }
        require(bytes.size > 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
    }

    fun write(value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        check(cipher.iv.size == 12)
        val bytes = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val output = file.startWrite()
        try {
            output.write(bytes)
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
            generateKey()
        }
    }
}
