package com.slack.circuit.fragmentreplace

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.slack.circuit.foundation.CircuitCompositionLocals
import com.slack.circuit.foundation.CircuitContent
import com.slack.circuit.tacos.OrderTacosScreen
import com.slack.circuit.tacos.ui.theme.TacoTheme

class MainFragment : Fragment() {

    private val dummyViewModel: MiniViewModal by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        Toast.makeText(
            requireContext(),
            "${dummyViewModel.text.value}",
            Toast.LENGTH_SHORT
        )
            .show()

        val circuit = (requireActivity() as MainActivity).circuit
        return ComposeView(requireContext()).apply {
            setContent {
                TacoTheme {
                    CircuitCompositionLocals(circuit) {
                        CircuitContent(OrderTacosScreen)
                        LaunchedEffect(Unit) {
                            dummyViewModel.updateText("Circuit state wasn't restored, back to first screen screen")
                        }
                    }
                }
            }
        }
    }
}