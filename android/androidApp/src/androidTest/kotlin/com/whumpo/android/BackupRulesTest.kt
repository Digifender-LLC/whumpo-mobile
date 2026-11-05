package com.whumpo.android

import android.content.pm.ApplicationInfo
import android.content.res.XmlResourceParser
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser

/** Protected state must never be eligible for backup or device transfer. */
@RunWith(AndroidJUnit4::class)
class BackupRulesTest {
    private val app get() = Fixtures.app

    @Test
    fun backupIsDisabled() {
        assertEquals(0, app.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
    }

    @Test
    fun dataExtractionRulesExcludeEveryDomainForCloudAndTransfer() {
        val excluded = readExcludes(app.resources.getXml(R.xml.data_extraction_rules))
        val required = setOf("root", "device_root", "external")
        assertEquals(required, excluded["cloud-backup"])
        assertEquals(required, excluded["device-transfer"])
    }

    @Test
    fun fullBackupContentExcludesEveryDomain() {
        val excluded = readExcludes(app.resources.getXml(R.xml.full_backup_content))
        assertEquals(setOf("root", "device_root", "external"), excluded["full-backup-content"])
    }

    /** Maps each section to the domains excluded at path "." and asserts there are no includes. */
    private fun readExcludes(parser: XmlResourceParser): Map<String, Set<String>> {
        val result = mutableMapOf<String, MutableSet<String>>()
        var section = ""
        parser.use {
            while (it.next() != XmlPullParser.END_DOCUMENT) {
                if (it.eventType != XmlPullParser.START_TAG) continue
                when (it.name) {
                    "include" -> throw AssertionError("Backup rules must not include anything")
                    "exclude" -> {
                        if (it.getAttributeValue(null, "path") == ".") {
                            result.getOrPut(section) { mutableSetOf() } += it.getAttributeValue(null, "domain")
                        }
                    }
                    else -> section = it.name
                }
            }
        }
        assertTrue(result.isNotEmpty())
        return result
    }
}
