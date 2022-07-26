package me.johnchilton.freshpots

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class CoffeeViewModel(app: Application) : AndroidViewModel(app) {
    enum class PotState {
        NOT_CONNECTED, OFF, ON, BREWING, DELAYING, WARMING, SCHEDULE
    }

}