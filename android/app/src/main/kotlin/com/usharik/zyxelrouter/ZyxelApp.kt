package com.usharik.zyxelrouter

import android.app.Application
import java.io.File

/** Общие для всего процесса службы: аналитика, покупка «без рекламы», частота показа и сама реклама. */
class ZyxelApp : Application() {
    val purchases by lazy { PlayPurchases(this) }
    val pacing by lazy { AdPacingStore(this) }
    val ads by lazy { AdsController(this, purchases, pacing) }

    /**
     * Как ViewModel подключается к роутеру. Инструментальные тесты подменяют его до запуска Activity, чтобы
     * не обращаться к настоящему роутеру в сети эмулятора.
     */
    @Volatile var connectRouter: (address: String, pins: CertificatePins) -> RouterApi = ::RouterClient

    override fun onCreate() {
        super.onCreate()
        Telemetry.install(FirebaseSink.create(this))
        // QR-код сети с паролем внутри мог остаться, если процесс завершился при открытом окне «Поделиться».
        Thread { File(cacheDir, SHARED_CACHE_DIR).deleteRecursively() }.start()
    }

    companion object {
        /** Временные файлы для «Поделиться» (см. res/xml/file_paths.xml). */
        const val SHARED_CACHE_DIR = "shared"
    }
}
