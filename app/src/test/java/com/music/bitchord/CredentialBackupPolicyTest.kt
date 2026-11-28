package com.music.bitchord

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CredentialBackupPolicyTest {

    private fun rules(path: String): String = File("src/main/res/xml/$path").readText()

    /** Every credential or Keystore-bound store, and the party identity. */
    private val excluded = listOf(
        "bitchord_tester.xml",
        "bitchord_tester_secure.xml",
        "bitchord_addon_auth.xml",
        "bitchord_addon_auth_secure.xml",
        "bitchord_listen_together.xml",
        "bitchord_party_secret.xml",
        "bitchord_party_secret_plain.xml",
    )

    @Test
    fun `addon token and tester credentials are excluded from backups`() {
        val backup = rules("backup_rules.xml")
        excluded.forEach { name -> assertTrue("$name missing from backup_rules", backup.contains(name)) }
    }

    @Test
    fun `legacy and current extraction rules exclude addon credentials`() {
        val extraction = rules("data_extraction_rules.xml")
        excluded.forEach { name -> assertTrue("$name missing from data_extraction_rules", extraction.contains(name)) }
    }

    @Test
    fun `settings stay in backup now that their credentials live in AuthStore`() {
        // AppSettings moves every credential into the encrypted AuthStore
        // (moveSecretsOutOfSettings), so the settings file itself is safe to
        // back up — excluding it would cost every user their settings on a
        // device transfer.
        listOf("backup_rules.xml", "data_extraction_rules.xml").forEach { file ->
            assertFalse("bitchord_settings.xml must stay in $file", rules(file).contains("bitchord_settings.xml"))
        }
    }
}
