package com.apc.kptcl.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.apc.kptcl.R
import com.apc.kptcl.databinding.FragmentWelcomeBinding


class WelcomeFragment : Fragment() {

    private var _binding: FragmentWelcomeBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentWelcomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupUI()
        setupClickListeners()
    }

    private fun setupUI() {
        // Get data from arguments
        val username = arguments?.getString("username") ?: ""
        val escom = arguments?.getString("escom") ?: "BESCOM"
        val role = arguments?.getString("role") ?: "Substation user"

        // Set user info
        binding.tvRole.text = role
        binding.tvEscom.text = escom

        // Set ESCOM logo based on selected ESCOM
        val logoResource = when (escom) {
            "BESCOM" -> R.drawable.bescom
            "HESCOM" -> R.drawable.hescom
            "GESCOM" -> R.drawable.gescom
            "MESCOM" -> R.drawable.mescom
            "CESC" -> R.drawable.cesc
            else -> R.drawable.kptcl
        }
        binding.ivEscomLogo.setImageResource(logoResource)
    }

    private fun setupClickListeners() {
        binding.toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }

        binding.btnProceed.setOnClickListener {
            // Navigate to dashboard/home
            findNavController().navigate(R.id.action_welcomeFragment_to_homeFragment)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
