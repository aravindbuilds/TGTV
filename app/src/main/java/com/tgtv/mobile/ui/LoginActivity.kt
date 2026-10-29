package com.tgtv.mobile.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tgtv.mobile.R
import com.tgtv.mobile.model.ChatSelection
import com.tgtv.mobile.model.CountryCodes
import com.tgtv.mobile.td.Credentials
import com.tgtv.mobile.td.TdLib
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.TdApi

/**
 * Telegram phone login: API credentials (prefilled from a previous run) ->
 * phone number -> login code -> optional 2FA password.
 * State machine driven purely by TdLib auth states.
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var apiPane: LinearLayout
    private lateinit var phonePane: LinearLayout
    private lateinit var codePane: LinearLayout
    private lateinit var passwordPane: LinearLayout
    private lateinit var botPane: LinearLayout
    private lateinit var progressPane: TextView

    private lateinit var apiId: EditText
    private lateinit var apiHash: EditText
    private lateinit var phone: EditText
    private lateinit var ccSelector: View
    private lateinit var ccLabel: TextView
    private lateinit var code: EditText
    private lateinit var password: EditText
    private lateinit var botToken: EditText
    private lateinit var status: TextView

    /** Selected dial code, shown in the country picker and prefixed on send. */
    private var dialCode: String = "+91"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        apiPane = findViewById(R.id.apiPane)
        phonePane = findViewById(R.id.phonePane)
        codePane = findViewById(R.id.codePane)
        passwordPane = findViewById(R.id.passwordPane)
        botPane = findViewById(R.id.botPane)
        progressPane = findViewById(R.id.progressPane)

        apiId = findViewById(R.id.apiIdInput)
        apiHash = findViewById(R.id.apiHashInput)
        phone = findViewById(R.id.phoneInput)
        ccSelector = findViewById(R.id.ccSelector)
        ccLabel = findViewById(R.id.ccLabel)
        code = findViewById(R.id.codeInput)
        password = findViewById(R.id.passwordInput)
        botToken = findViewById(R.id.botTokenInput)
        status = findViewById(R.id.loginStatus)

        apiId.setText(Credentials.apiId(this).takeIf { it > 0 }?.toString() ?: "")
        apiHash.setText(Credentials.apiHash(this) ?: "")
        // Split the saved full number back into dial code + national number.
        val saved = Credentials.phone(this) ?: ""
        if (saved.isNotBlank()) {
            val (dial, rest) = CountryCodes.split(saved)
            dialCode = dial
            phone.setText(rest)
        }
        ccLabel.text = dialCode
        ccSelector.setOnClickListener { pickCountry() }

        // Bot login: remember a previously entered token, offer the switch
        botToken.setText(Credentials.botToken(this) ?: "")
        findViewById<TextView>(R.id.botToggle).setOnClickListener {
            hideAll()
            botPane.visibility = View.VISIBLE
            status.text = null
        }
        findViewById<TextView>(R.id.botToggleBack).setOnClickListener {
            botPane.visibility = View.GONE
            show(1)
        }
        findViewById<Button>(R.id.botLoginButton).setOnClickListener {
            val token = botToken.text.toString().trim()
            // BotFather format: <bot id 6-12 digits>:<35ish char secret>
            if (!Regex("\\d{6,12}:[A-Za-z0-9_-]{30,}").matches(token)) {
                status.text = "That doesn't look like a BotFather token (e.g. 123456789:AAHf3...)"
                return@setOnClickListener
            }
            status.text = null
            Credentials.setBotToken(this, token)
            TdLib.start(this)
            showProgress("Signing in as bot...")
            TdLib.loginBot(token)
        }

        findViewById<Button>(R.id.saveApiButton).setOnClickListener {
            val id = apiId.text.toString().trim().toIntOrNull() ?: 0
            val hash = apiHash.text.toString().trim()
            if (id <= 0 || hash.length < 30) {
                status.text = "API ID (an integer) and API Hash (~32 chars) are required"
                return@setOnClickListener
            }
            Credentials.save(this, id, hash, fullPhone())
            TdLib.start(this)
            showProgress("Connecting to Telegram...")
        }
        findViewById<Button>(R.id.sendCodeButton).setOnClickListener {
            val digits = phone.text.toString().trim().filter { it.isDigit() }
            if (digits.length < 6 || digits.length > 14) {
                status.text = "Enter the number without the country code (6–14 digits)"
                return@setOnClickListener
            }
            val raw = dialCode + digits
            Credentials.save(this, Credentials.apiId(this), Credentials.apiHash(this).orEmpty(), raw)
            status.text = null
            showProgress("Requesting login code...")
            TdLib.requestPhoneCode(raw)
        }
        findViewById<Button>(R.id.confirmCodeButton).setOnClickListener {
            val c = code.text.toString().trim()
            if (c.isEmpty()) {
                status.text = "Enter the login code"
                return@setOnClickListener
            }
            showProgress("Verifying code...")
            TdLib.submitCode(c)
        }
        findViewById<Button>(R.id.loginPasswordButton).setOnClickListener {
            val pw = password.text.toString()
            if (pw.isEmpty()) {
                status.text = "Enter the 2FA password"
                return@setOnClickListener
            }
            showProgress("Checking password...")
            TdLib.submitPassword(pw)
        }
        findViewById<Button>(R.id.resendCodeButton).setOnClickListener {
            status.text = "Code re-sent"
            TdLib.resendPhoneCode()
        }
        findViewById<Button>(R.id.signOutButton).setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                TdLib.resetDb(applicationContext)
                Credentials.clear(applicationContext)
                runOnUiThread {
                    phonePane.visibility = View.GONE
                    codePane.visibility = View.GONE
                    passwordPane.visibility = View.GONE
                    botPane.visibility = View.GONE
                    botToken.setText("")
                    apiPane.visibility = if (Credentials.bakedApiPresent()) View.GONE else View.VISIBLE
                    apiId.setText("")
                    apiHash.setText("")
                    progressPane.visibility = View.GONE
                    status.text = null
                }
            }
        }

        observeAuth()
    }

    /** Composed phone in full international form: "+919876543210". */
    private fun fullPhone(): String = dialCode + phone.text.toString().trim().filter { it.isDigit() }

    /**
     * After sign-in: first run goes to the chat picker (choose which chats
     * feed the library), later runs straight to the home screen.
     */
    private fun openNextScreen() {
        val target = if (ChatSelection.isConfigured(this)) HomeActivity::class.java
        else ChatSelectActivity::class.java
        startActivity(Intent(this@LoginActivity, target))
        finish()
    }

    private fun pickCountry() {
        val labels = CountryCodes.entries.map { "${it.name}  (${it.dial})" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Country code")
            .setItems(labels) { _, which ->
                dialCode = CountryCodes.entries[which].dial
                ccLabel.text = dialCode
            }
            .setNeutralButton("Custom…") { _, _ -> customDialDialog() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** For codes not in the list, let the user type one (1–4 digits). */
    private fun customDialDialog() {
        val input = EditText(this).apply {
            hint = "+971"
            setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_PHONE
        }
        AlertDialog.Builder(this)
            .setTitle("Enter country code")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val raw = input.text.toString().trim().filter { it.isDigit() || it == '+' }
                if (raw.isEmpty()) return@setPositiveButton
                dialCode = if (raw.startsWith("+")) raw else "+$raw"
                ccLabel.text = dialCode
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun hideAll() {
        apiPane.visibility = View.GONE
        phonePane.visibility = View.GONE
        codePane.visibility = View.GONE
        passwordPane.visibility = View.GONE
        botPane.visibility = View.GONE
    }

    private fun showProgress(text: String) {
        hideAll()
        progressPane.visibility = View.VISIBLE
        progressPane.text = text
        status.text = null
    }

    private fun show(kind: Int) {
        hideAll()
        progressPane.visibility = View.GONE
        when (kind) {
            // Baked build: API values live inside the app; jump straight to phone entry
            0 -> if (Credentials.bakedApiPresent()) {
                TdLib.start(this)
                showProgress("Connecting to Telegram...")
            } else {
                apiPane.visibility = View.VISIBLE
            }
            1 -> phonePane.visibility = View.VISIBLE
            2 -> codePane.visibility = View.VISIBLE
            3 -> passwordPane.visibility = View.VISIBLE
        }
        status.text = null
    }

    private fun observeAuth() {
        lifecycleScope.launch {
            TdLib.authErrors.collect { status.text = it }
        }
        lifecycleScope.launch {
            TdLib.authState.collect { st ->
                when (st) {
                    is TdApi.AuthorizationStateWaitTdlibParameters ->
                        showProgress("Connecting to Telegram...")
                    is TdApi.AuthorizationStateWaitPhoneNumber -> show(1)
                    is TdApi.AuthorizationStateWaitCode -> show(2)
                    is TdApi.AuthorizationStateWaitPassword -> show(3)
                    is TdApi.AuthorizationStateReady -> {
                        progressPane.visibility = View.VISIBLE
                        progressPane.text = "Signed in - opening TGTV..."
                        openNextScreen()
                    }
                    else -> Unit
                }
            }
        }
        // Cold start with saved credentials but a dead client: restart TDLib
        lifecycleScope.launch(Dispatchers.IO) {
            if (Credentials.hasApi(applicationContext) && !TdLib.isStarted) {
                TdLib.start(applicationContext)
            }
            if (TdLib.isStarted) {
                // Wait briefly for the auth state; if Ready races before the
                // collector subscribes we may still be entering the login flow
                val ready = withTimeoutOrNull(8_000L) {
                    while (TdLib.authState.value !is TdApi.AuthorizationStateReady) {
                        kotlinx.coroutines.delay(250)
                    }
                    true
                }
                if (ready == true) {
                    runOnUiThread { openNextScreen() }
                }
            }
        }
    }
}
