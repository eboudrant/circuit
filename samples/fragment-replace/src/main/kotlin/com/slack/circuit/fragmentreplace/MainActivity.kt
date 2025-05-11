// Copyright (C) 2022 Slack Technologies, LLC
// SPDX-License-Identifier: Apache-2.0
package com.slack.circuit.fragmentreplace

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.slack.circuit.foundation.Circuit
import com.slack.circuit.runtime.presenter.Presenter
import com.slack.circuit.runtime.ui.Ui
import com.slack.circuit.runtime.ui.ui
import com.slack.circuit.tacos.repository.IngredientsRepositoryImpl
import com.slack.circuit.tacos.step.FillingsProducerImpl
import com.slack.circuit.tacos.step.ToppingsProducerImpl
import com.slack.circuit.tacos.step.confirmationProducer
import com.slack.circuit.tacos.step.summaryProducer
import com.slack.circuit.tacos.OrderTacosPresenter
import com.slack.circuit.tacos.OrderTacosScreen
import com.slack.circuit.tacos.OrderTacosUi
import com.slack.circuit.tacos.R

class MainActivity : AppCompatActivity() {

  val circuit: Circuit =
    Circuit.Builder()
      .addPresenterFactory(buildPresenterFactory())
      .addUiFactory(buildUiFactory())
      .build()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContentView(R.layout.main)
    if (savedInstanceState == null) {
      supportFragmentManager.beginTransaction()
        .replace(R.id.fragment_container, MainFragment())
        .commitNow()
    }
  }

  internal fun newFragment() {
    val fragmentManager = supportFragmentManager
    val fragmentTransaction = fragmentManager.beginTransaction()
    fragmentTransaction.setCustomAnimations(
      R.anim.enter_from_right,
      R.anim.exit_to_left,
      R.anim.enter_from_left,
      R.anim.exit_to_right,
    )

    // Replace the fragment (calling `replace` instead of `add` on purpose)
    fragmentTransaction.replace(R.id.fragment_container, MainFragment())
    // Add to back stack
    fragmentTransaction.addToBackStack(null)
    // Commit the transaction
    fragmentTransaction.commit()
  }
}

private fun buildPresenterFactory(): Presenter.Factory =
  Presenter.Factory { _, _, _ ->
    OrderTacosPresenter(
      fillingsProducer = FillingsProducerImpl(IngredientsRepositoryImpl),
      toppingsProducer = ToppingsProducerImpl(IngredientsRepositoryImpl),
      confirmationProducer = { details, _ -> confirmationProducer(details) },
      summaryProducer = { _, sink -> summaryProducer(sink) },
    )
  }

private fun buildUiFactory(): Ui.Factory =
  Ui.Factory { _, _ ->
    ui<OrderTacosScreen.State> { state, modifier -> OrderTacosUi(state, modifier) }
  }
