package com.lagradost.cloudstream3.companion.ui

import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.textview.MaterialTextView
import com.lagradost.cloudstream3.R

/** Phone device picker shared by the toolbar and result play routing. */
object CompanionDevicePicker {
    fun show(context: Context) {
        val dialog = BottomSheetDialog(context)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                resources.getDimensionPixelSize(R.dimen.companion_screen_padding),
                resources.getDimensionPixelSize(R.dimen.companion_screen_padding),
                resources.getDimensionPixelSize(R.dimen.companion_screen_padding),
                resources.getDimensionPixelSize(R.dimen.companion_screen_padding),
            )
        }
        root.addView(MaterialTextView(context).apply {
            text = context.getString(R.string.connect_to_tv)
            setTextSize(
                android.util.TypedValue.COMPLEX_UNIT_PX,
                resources.getDimension(R.dimen.companion_picker_title_size),
            )
        })
        CompanionUiBridge.devices.value.forEach { device ->
            root.addView(MaterialButton(context).apply {
                text = device.name
                isEnabled = !device.connected
                setOnClickListener {
                    if (device.paired) {
                        CompanionUiBridge.connectTv(device.id)
                        dialog.dismiss()
                    } else {
                        dialog.dismiss()
                        showPairDialog(context, device.address)
                    }
                }
            })
        }
        root.addView(MaterialButton(context).apply {
            text = context.getString(R.string.connect_to_tv_add_address)
            setOnClickListener {
                dialog.dismiss()
                showAddressDialog(context)
            }
        })
        root.addView(MaterialButton(context).apply {
            text = context.getString(R.string.connect_to_tv_pair_new)
            setOnClickListener {
                dialog.dismiss()
                showPairDialog(context)
            }
        })
        dialog.setContentView(root)
        dialog.show()
    }

    private fun showAddressDialog(context: Context) {
        val input = EditText(context).apply {
            hint = context.getString(R.string.connect_to_tv_address_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.connect_to_tv_add_address)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val address = input.text.toString().trim()
                if (isAddress(address)) CompanionUiBridge.pairTv(address)
                else android.widget.Toast.makeText(
                    context,
                    R.string.connect_to_tv_address_invalid,
                    Toast.LENGTH_SHORT,
                ).show()
            }
            .show()
    }

    private fun showPairDialog(context: Context, initialAddress: String? = null) {
        val address = EditText(context).apply {
            hint = context.getString(R.string.connect_to_tv_address_hint)
            setText(initialAddress.orEmpty())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val pin = EditText(context).apply {
            hint = context.getString(R.string.connect_to_tv_pin)
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(address)
            addView(pin)
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.connect_to_tv_pair_new)
            .setView(content)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val host = address.text.toString().trim()
                val code = pin.text.toString().trim()
                if (isAddress(host) && code.isNotBlank()) {
                    CompanionUiBridge.pairTv(host, code)
                } else {
                    Toast.makeText(
                        context,
                        R.string.connect_to_tv_address_invalid,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
            .show()
    }

    private fun isAddress(address: String): Boolean {
        if (address.isBlank() || address.any { it.isWhitespace() }) return false
        return runCatching {
            val normalized = if (address.startsWith("[")) address else "[$address]"
            java.net.URI("companion://$normalized").host != null
        }.getOrNull() == true || runCatching {
            val colon = address.lastIndexOf(':')
            colon > 0 && address.substring(colon + 1).toInt() in 1..65535
        }.getOrDefault(false)
    }
}
