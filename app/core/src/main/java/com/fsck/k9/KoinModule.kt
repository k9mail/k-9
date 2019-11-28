package com.fsck.k9

import android.content.Context
import com.fsck.k9.helper.Contacts
import com.fsck.k9.helper.ImageResizer
import com.fsck.k9.mail.power.PowerManager
import com.fsck.k9.mail.ssl.DefaultTrustedSocketFactory
import com.fsck.k9.mail.ssl.LocalKeyStore
import com.fsck.k9.mail.ssl.TrustManagerFactory
import com.fsck.k9.mail.ssl.TrustedSocketFactory
import com.fsck.k9.mailstore.LocalStoreProvider
import com.fsck.k9.power.TracingPowerManager
import org.koin.dsl.module

val mainModule = module {
    single { Preferences.getPreferences(get()) }
    single { get<Context>().resources }
    single { LocalStoreProvider() }
    single<PowerManager> { TracingPowerManager.getPowerManager(get()) }
    single { Contacts.getInstance(get()) }
    single { LocalKeyStore.createInstance(get()) }
    single { TrustManagerFactory.createInstance(get()) }
    single { LocalKeyStoreManager(get()) }
    single<TrustedSocketFactory> { DefaultTrustedSocketFactory(get(), get()) }
    single { Clock.INSTANCE }
    single { ImageResizer(get()) }
}
