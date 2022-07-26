package me.johnchilton.freshpots

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider

class MainActivity : AppCompatActivity() {

    private lateinit var viewModel : CoffeeViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        viewModel = ViewModelProvider(this).get(CoffeeViewModel::class.java)
    }

    override fun onResume() {
        super.onResume()

        beginPotStatusDiscovery()
    }

    private fun beginPotStatusDiscovery() {
        CoffeePotClient().beginNetworkStatusDiscovery(applicationContext)
    }
}