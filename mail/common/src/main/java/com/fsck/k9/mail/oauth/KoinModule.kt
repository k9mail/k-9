package com.fsck.k9.mail.oauth

import com.fsck.k9.mail.oauth.authorizationserver.codegrantflow.OAuth2CodeGrantFlowManager
import org.koin.dsl.module

val oauth2Module = module {
    single<OAuth2TokenProvider> { K9OAuth2TokenProvider(get(), get()) }
    single { OAuth2CodeGrantFlowManager(get()) }
    single { OAuth2TokensStore(get()) }
}
