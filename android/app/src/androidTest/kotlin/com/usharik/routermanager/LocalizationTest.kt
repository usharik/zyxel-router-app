package com.usharik.routermanager

import android.content.res.Configuration
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Каждая ошибка роутера на каждом языке: неверная подстановка (%d вместо %s) уронила бы приложение именно при ошибке. */
@RunWith(AndroidJUnit4::class)
class LocalizationTest {
    private val errors = listOf(
        RouterException.BadAddress, RouterException.InvalidCredentials, RouterException.Locked(60), RouterException.SessionExpired,
        RouterException.SessionRefreshed, RouterException.Unreachable, RouterException.BadResponse, RouterException.LoginUnreadable, RouterException.BadLoginData,
        RouterException.DuplicateSession, RouterException.TooManySessions, RouterException.LoginRejected("Wrong"),
        RouterException.NoData("rip", 404), RouterException.WriteRejected("paren_ctl", 500), RouterException.ProfileNotFound, RouterException.DeviceNotFound,
        RouterException.DeleteRejected(500), RouterException.LogUnavailable(503), RouterException.RebootRejected(500),
        RouterException.CertificateChanged("AB"), IllegalStateException("boom"),
    )

    @Test fun everyRouterErrorHasTextInEveryLanguage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (tag in listOf("en", "ru", "uk", "cs", "de")) {
            val res = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }).resources
            for (e in errors) {
                val text = routerErrorText(res, e)
                assertTrue("$tag ${e::class.simpleName}", text.isNotBlank())
                assertFalse("$tag ${e::class.simpleName}: подстановка не заменена", text.contains("%"))
            }
        }
    }
}
