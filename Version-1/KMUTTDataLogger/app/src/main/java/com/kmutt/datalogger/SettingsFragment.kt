package com.kmutt.datalogger

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.textfield.TextInputEditText

class SettingsFragment : Fragment() {

    private lateinit var etDbAddress: TextInputEditText
    private lateinit var etDbPort:    TextInputEditText
    private lateinit var etDbUser:    TextInputEditText
    private lateinit var etDbPass:    TextInputEditText
    private lateinit var btnTest:     Button
    private lateinit var btnSave:     Button
    private lateinit var tvConnStatus: TextView

    private lateinit var databaseManager: DatabaseManager

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        databaseManager = (requireActivity() as MainActivity).databaseManager

        etDbAddress  = view.findViewById(R.id.etDbAddress)
        etDbPort     = view.findViewById(R.id.etDbPort)
        etDbUser     = view.findViewById(R.id.etDbUser)
        etDbPass     = view.findViewById(R.id.etDbPass)
        btnTest      = view.findViewById(R.id.btnTestConnection)
        btnSave      = view.findViewById(R.id.btnSaveSettings)
        tvConnStatus = view.findViewById(R.id.tvConnStatus)

        loadSettings()

        btnSave.setOnClickListener { saveSettings() }
        btnTest.setOnClickListener { testConnection() }
    }

    override fun onResume() {
        super.onResume()
        loadSettings()
    }

    private fun loadSettings() {
        val prefs = requireContext().getSharedPreferences("db_prefs", Context.MODE_PRIVATE)
        etDbAddress.setText(prefs.getString("db_address", ""))
        etDbPort.setText(prefs.getString("db_port", "3000"))
        etDbUser.setText(prefs.getString("db_user", ""))
        etDbPass.setText(prefs.getString("db_pass", ""))
    }

    private fun saveSettings() {
        val prefs = requireContext().getSharedPreferences("db_prefs", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString("db_address", etDbAddress.text.toString().trim())
            putString("db_port",    etDbPort.text.toString().trim().ifEmpty { "3000" })
            putString("db_user",    etDbUser.text.toString().trim())
            putString("db_pass",    etDbPass.text.toString())
            apply()
        }
        Toast.makeText(requireContext(), "Settings saved", Toast.LENGTH_SHORT).show()
        tvConnStatus.text = "Settings saved"
        tvConnStatus.setTextColor(android.graphics.Color.parseColor("#43A047"))
    }

    private fun testConnection() {
        saveSettings()
        tvConnStatus.text = "Testing..."
        tvConnStatus.setTextColor(android.graphics.Color.parseColor("#FB8C00"))
        databaseManager.testConnection(object : DatabaseManager.TestCallback {
            override fun onSuccess() {
                tvConnStatus.text = "Connection OK"
                tvConnStatus.setTextColor(android.graphics.Color.parseColor("#43A047"))
            }
            override fun onError(msg: String) {
                tvConnStatus.text = "Error: $msg"
                tvConnStatus.setTextColor(android.graphics.Color.parseColor("#E53935"))
            }
        })
    }
}
