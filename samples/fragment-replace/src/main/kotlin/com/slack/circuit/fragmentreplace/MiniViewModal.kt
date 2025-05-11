package com.slack.circuit.fragmentreplace

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class MiniViewModal : ViewModel() {

    private val _text = MutableLiveData<String>().apply {
        value = "Choose your fillings and navigate to toppings, then open a new fragment"
    }

    val text: LiveData<String> = _text

    fun updateText(newText: String) {
        _text.value = newText
    }
}