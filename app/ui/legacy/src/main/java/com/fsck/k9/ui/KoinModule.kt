package com.fsck.k9.ui

import android.content.Context
import com.fsck.k9.ui.base.ThemeProvider
import com.fsck.k9.ui.helper.DisplayHtmlUiFactory
import com.fsck.k9.ui.helper.HtmlSettingsProvider
import com.fsck.k9.ui.helper.HtmlToSpanned
import com.fsck.k9.ui.helper.SizeFormatter
import org.koin.dsl.module

val uiModule = module {
    single { HtmlToSpanned() }
    single<ThemeProvider> { K9ThemeProvider() }
    single { HtmlSettingsProvider(get()) }
    single { DisplayHtmlUiFactory(get()) }
    factory { (context: Context) -> SizeFormatter(context.resources) }
}
