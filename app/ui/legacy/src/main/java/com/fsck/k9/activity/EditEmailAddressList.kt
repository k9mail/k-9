package com.fsck.k9.activity

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.AdapterView.OnItemClickListener
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import com.fsck.k9.Account
import com.fsck.k9.Preferences
import com.fsck.k9.ui.R

enum class EmailAddressListType {
    MUTED_SENDERS, MUTE_IF_SENT_TO
}

interface EditEmailAddressListDialogListener {
    fun onModifyEmail(oldEmail: String?, newEmail: String?)
}

class EditEmailAddressList : K9ListActivity(), OnItemClickListener, EditEmailAddressListDialogListener {
    private lateinit var account: Account
    private lateinit var listType: EmailAddressListType
    private lateinit var arrayAdapter: ArrayAdapter<String>
    private val emailAddresses = HashSet<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setLayout(R.layout.list_content_simple)

        listView.isTextFilterEnabled = true
        listView.itemsCanFocus = false
        listView.choiceMode = ListView.CHOICE_MODE_NONE

        val accountUuid = intent.getStringExtra(EXTRA_ACCOUNT)
        account = Preferences.getPreferences(this).getAccount(accountUuid)

        listType = intent.getSerializableExtra(EXTRA_LIST_TYPE) as EmailAddressListType

        arrayAdapter = ArrayAdapter<String>(this, android.R.layout.simple_list_item_1)

        setListAdapter(arrayAdapter)
        setupClickListeners()
    }

    override fun onResume() {
        super.onResume()
        refreshView()
    }

    protected fun refreshView() {
        arrayAdapter.setNotifyOnChange(false)
        arrayAdapter.clear()
        emailAddresses.clear()
        val addresses = when (listType) {
            EmailAddressListType.MUTED_SENDERS -> account.mutedSenders
            EmailAddressListType.MUTE_IF_SENT_TO -> account.muteIfSentTo
        }
        for (emailAddress in addresses) {
            arrayAdapter.add(emailAddress)
            emailAddresses.add(emailAddress)
        }
        arrayAdapter.add("+ Add to list")
        arrayAdapter.notifyDataSetChanged()
    }

    override fun onModifyEmail(oldEmail: String?, newEmail: String?) {
        if (oldEmail == newEmail)
            return
        if (oldEmail != null) {
            arrayAdapter.remove(oldEmail)
            emailAddresses.remove(oldEmail)
        }
        if (newEmail != null) {
            arrayAdapter.add(newEmail)
            emailAddresses.add(newEmail)
        }
        arrayAdapter.sort(
            Comparator { o1, o2 ->
                when {
                    o1 == "+ Add to list" -> +1
                    o2 == "+ Add to list" -> -1
                    else -> o1.compareTo(o2)
                }
            }
        )
        arrayAdapter.notifyDataSetChanged()
        saveEmailAddresses()
    }

    override fun onItemClick(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
        val originalEmail = when (position) {
            arrayAdapter.count - 1 -> null
            else -> arrayAdapter.getItem(position)
        }
        var dialogFragment = EditEmailAddressDialogFragment.create(originalEmail)
        dialogFragment.show(supportFragmentManager, null)
    }

    protected fun setupClickListeners() {
        this.listView.onItemClickListener = this
    }

    private fun saveEmailAddresses() {
        when (listType) {
            EmailAddressListType.MUTED_SENDERS -> account.setMutedSenders(emailAddresses)
            EmailAddressListType.MUTE_IF_SENT_TO -> account.setMuteIfSentTo(emailAddresses)
        }
        Preferences.getPreferences(applicationContext).saveAccount(account)
    }

    override fun onBackPressed() {
        saveEmailAddresses()
        finish()
        super.onBackPressed()
    }

    public override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
    }

    public class EditEmailAddressDialogFragment : DialogFragment() {
        private lateinit var listener: EditEmailAddressListDialogListener

        override fun onAttach(context: Context) {
            super.onAttach(context)
            try {
                listener = context as EditEmailAddressListDialogListener
            } catch (e: ClassCastException) {
                throw ClassCastException(
                    context.toString() +
                        " must implement EditEmailAddressListDialogListener"
                )
            }
        }

        override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
            val arguments = this.arguments ?: error("Fragment arguments missing")
            val originalEmail = arguments.getString(EditEmailAddressDialogFragment.ARG_EMAIL_ADDRESS)

            return activity?.let {
                val builder = AlertDialog.Builder(it)
                val inflater = requireActivity().layoutInflater

                val view = inflater.inflate(R.layout.edit_email_address_dialog, null)
                val editEmailAddress = view.findViewById<TextView>(R.id.edit_email_address)
                if (originalEmail != null)
                    editEmailAddress.setText(originalEmail)
                builder.setView(view)
                    .setPositiveButton(
                        R.string.account_settings_edit_email_address_modify,
                        DialogInterface.OnClickListener { dialog, id ->
                            listener.onModifyEmail(originalEmail, editEmailAddress.text.toString())
                        }
                    )
                    .setNegativeButton(
                        R.string.account_settings_edit_email_address_remove,
                        DialogInterface.OnClickListener { dialog, id ->
                            listener.onModifyEmail(originalEmail, null)
                        }
                    )
                builder.create()
            } ?: throw IllegalStateException("Activity cannot be null")
        }

        companion object {
            private const val ARG_EMAIL_ADDRESS = "accountUuid"

            fun create(
                originalEmail: String?
            ) = EditEmailAddressDialogFragment().apply {
                arguments = bundleOf(
                    EditEmailAddressDialogFragment.ARG_EMAIL_ADDRESS to originalEmail,
                )
            }
        }
    }

    companion object {
        fun start(activity: Activity, accountUuid: String, list: EmailAddressListType) {
            val intent = Intent(activity, EditEmailAddressList::class.java)
            intent.putExtra(EditEmailAddressList.EXTRA_ACCOUNT, accountUuid)
            intent.putExtra(EditEmailAddressList.EXTRA_LIST_TYPE, list)
            activity.startActivity(intent)
        }

        const val EXTRA_ACCOUNT = "com.fsck.k9.EditEmailAddressList_account"
        const val EXTRA_LIST_TYPE = "com.fsck.k9.EditEmailAddressList_list_type"
    }
}
