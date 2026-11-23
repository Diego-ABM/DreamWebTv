package com.example.dreamweb.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.dreamweb.BrowserApp
import com.example.dreamweb.R
import com.example.dreamweb.databinding.ActivitySecuritySettingsBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Sección "Seguridad" (FR-004): lista las excepciones TLS por dominio con su
 * fecha de aceptación, permite revocar por ítem (con confirmación) y borrar
 * todas. Navegable con D-pad, mismo patrón ViewBinding + RecyclerView que
 * HomeActivity.
 */
class SecuritySettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySecuritySettingsBinding
    private lateinit var adapter: TlsExceptionAdapter

    private val store by lazy { (application as BrowserApp).container.tlsExceptionStore }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySecuritySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = TlsExceptionAdapter { host -> confirmRevoke(host) }
        binding.exceptionsRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@SecuritySettingsActivity)
            this.adapter = this@SecuritySettingsActivity.adapter
        }

        binding.clearAllButton.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Borrar excepciones TLS")
                .setMessage("¿Eliminar todas las excepciones de certificado guardadas? Los dominios volverán a pedir confirmación.")
                .setPositiveButton("Borrar todas") { _, _ ->
                    store.clear()
                    reload()
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        val items = store.list().entries
            .sortedBy { it.key }
            .map { it.key to it.value }
        adapter.submitList(items)
        binding.emptyText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun confirmRevoke(host: String) {
        AlertDialog.Builder(this)
            .setTitle("Revocar excepción")
            .setMessage("¿Revocar la excepción TLS de \"$host\"? La próxima conexión volverá a validar el certificado.")
            .setPositiveButton("Revocar") { _, _ ->
                store.remove(host)
                reload()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    class TlsExceptionAdapter(private val onClick: (String) -> Unit) :
        RecyclerView.Adapter<TlsExceptionAdapter.ViewHolder>() {

        private var items = listOf<Pair<String, Long>>()
        private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

        fun submitList(newList: List<Pair<String, Long>>) {
            items = newList
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_tls_exception, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.domainText.text = item.first
            holder.dateText.text = "aceptado el ${dateFormat.format(Date(item.second))} — pulsa para revocar"
            holder.itemView.setOnClickListener { onClick(item.first) }
        }

        override fun getItemCount() = items.size

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val domainText: TextView = view.findViewById(R.id.domainText)
            val dateText: TextView = view.findViewById(R.id.dateText)
        }
    }
}
