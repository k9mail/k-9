package com.fsck.k9.ui.settings.export

import com.fsck.k9.ui.R
import kotlinx.android.synthetic.main.settings_export_account_list_item.*

private const val GENERAL_SETTINGS_ID = 0L
private const val PASSWORD_ID = 1L
private const val ACCOUNT_ITEMS_ID_OFFSET = 2L

class GeneralSettingsItem : CheckBoxItem(GENERAL_SETTINGS_ID) {
    override val type = R.id.settings_export_list_general_item
    override val layoutRes = R.layout.settings_export_general_list_item
}

class PasswordItem : CheckBoxItem(PASSWORD_ID) {
    override val type = R.id.settings_export_list_password_item
    override val layoutRes = R.layout.settings_export_password_item
}

class AccountItem(account: SettingsListItem.Account) : CheckBoxItem(account.accountNumber + ACCOUNT_ITEMS_ID_OFFSET) {
    private val displayName = account.displayName
    private val email = account.email

    override val type = R.id.settings_export_list_account_item
    override val layoutRes = R.layout.settings_export_account_list_item

    override fun bindView(holder: CheckBoxViewHolder, payloads: MutableList<Any>) {
        super.bindView(holder, payloads)
        holder.accountDisplayName.text = displayName
        holder.accountEmail.text = email
    }
}
