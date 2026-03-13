package com.kmutt.weatherlogger

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.textfield.TextInputEditText
import java.net.URL

class SettingsFragment : Fragment() {

    private lateinit var etDbAddress:  TextInputEditText
    private lateinit var etDbPort:     TextInputEditText
    private lateinit var etDbName:     TextInputEditText
    private lateinit var etDbUser:     TextInputEditText
    private lateinit var etDbPass:     TextInputEditText
    private lateinit var btnTest:      Button
    private lateinit var btnSave:      Button

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        etDbAddress = view.findViewById(R.id.etDbAddress)
        etDbPort    = view.findViewById(R.id.etDbPort)
        etDbName    = view.findViewById(R.id.etDbName)
        etDbUser    = view.findViewById(R.id.etDbUser)
        etDbPass    = view.findViewById(R.id.etDbPass)
        btnTest     = view.findViewById(R.id.btnTestConnection)
        btnSave     = view.findViewById(R.id.btnSaveSettings)

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
        etDbPort.setText(prefs.getString("db_port", "3306"))
        etDbName.setText(prefs.getString("db_name", ""))
        etDbUser.setText(prefs.getString("db_user", ""))
        etDbPass.setText(prefs.getString("db_pass", ""))
    }

    private fun saveSettings() {
        val prefs = requireContext().getSharedPreferences("db_prefs", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString("db_address", etDbAddress.text.toString().trim())
            putString("db_port",    etDbPort.text.toString().trim())
            putString("db_name",    etDbName.text.toString().trim())
            putString("db_user",    etDbUser.text.toString().trim())
            putString("db_pass",    etDbPass.text.toString())
            apply()
        }
        Toast.makeText(requireContext(), "Settings saved", Toast.LENGTH_SHORT).show()
    }

    private fun testConnection() {
        val addr = etDbAddress.text.toString().trim()
        val port = etDbPort.text.toString().trim().ifEmpty { "3306" }
        if (addr.isEmpty()) {
            Toast.makeText(requireContext(), "Enter server address first", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(requireContext(), "Testing $addr:$port...", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val url = URL("http://$addr:$port")
                val conn = url.openConnection()
                conn.connectTimeout = 4000
                conn.readTimeout    = 4000
                conn.connect()
                requireActivity().runOnUiThread {
                    Toast.makeText(requireContext(), "Connection successful!", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                requireActivity().runOnUiThread {
                    Toast.makeText(requireContext(), "Connection failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }
}
