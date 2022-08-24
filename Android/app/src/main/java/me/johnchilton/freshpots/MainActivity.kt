package me.johnchilton.freshpots

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Menu
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.*
import kotlin.time.Duration.Companion.seconds

const val POT_TAG = "Woot"

class MainActivity : AppCompatActivity() {

    private val brewerClient = BrewerClient(this)

    private val monitorHandler: Handler = Handler(Looper.getMainLooper())

    private lateinit var mapMeaningsToMedia: Map<BrewerClient.BrewerMeaning, Pair<Int, Int>>

    private lateinit var commandMenu: PopupMenu

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        commandMenu = PopupMenu(this, findViewById(R.id.menu_button))

        mapMeaningsToMedia = hashMapOf(
            Pair(BrewerClient.BrewerMeaning.BREW, Pair(R.drawable.on, R.string.status_on)),
            Pair(BrewerClient.BrewerMeaning.STOP, Pair(R.drawable.off, R.string.status_off)),
            Pair(BrewerClient.BrewerMeaning.DELAY, Pair(R.drawable.delaying, R.string.status_delaying)),
            Pair(BrewerClient.BrewerMeaning.WARM, Pair(R.drawable.warming, R.string.status_warming)),
            Pair(BrewerClient.BrewerMeaning.SCHEDULE, Pair(R.drawable.delaying, R.string.status_delaying)),
            Pair(BrewerClient.BrewerMeaning.UNKNOWN_OR_ERROR, Pair(R.drawable.nc, R.string.status_unknown))
        )

        findViewById<Button>(R.id.menu_button).setOnClickListener {
            commandMenu.show()
        }
    }

    override fun onResume() {
        super.onResume()
        brewerClient.startServiceAwareness(this, ::onBrewerDiscovered, ::onBrewerLost)
    }

    override fun onPause() {
        super.onPause()
        brewerClient.stopServiceAwareness()
        stopMonitoring()
        updatePage(BrewerClient.BrewerMeaning.UNKNOWN_OR_ERROR)
    }

    private fun onBrewerDiscovered() {
        startMonitoring()
    }

    fun apiCallAndCallback(m: BrewerClient.BrewerMeaning, seconds1: Int? = null, seconds2: Int? = null) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                brewerClient.sendAndReceive(m, seconds1, seconds2) { m2, t1, t2 ->
                    run {
                        runOnUiThread { processReceivedMessage(m, m2, t1, t2) }
                    }
                }
            }
        }
    }

    private fun processReceivedMessage(sentMessage: BrewerClient.BrewerMeaning, receivedMessage: BrewerClient.BrewerMeaning, t1: Int?, t2: Int?) {
        if (sentMessage == BrewerClient.BrewerMeaning.MONITOR) {
            updatePage(receivedMessage, t1, t2)
        } else {
            if (receivedMessage == BrewerClient.BrewerMeaning.ACKNOWLEDGEMENT) {
                updatePage(sentMessage, t1, t2)
            } else {
                updatePage(BrewerClient.BrewerMeaning.UNKNOWN_OR_ERROR, t1, t2)
            }
        }
    }

    private fun onBrewerLost() {
        stopMonitoring()
        Log.d(POT_TAG, "Lost network connectivity to pot")
    }

    private fun rebuildMenu(active: BrewerClient.BrewerMeaning) {
        if (active == BrewerClient.BrewerMeaning.UNKNOWN_OR_ERROR) {
            findViewById<Button>(R.id.menu_button).visibility = View.GONE
            return
        }

        findViewById<Button>(R.id.menu_button).visibility = View.VISIBLE
        commandMenu.menu.clear()

        for (menuCommand in arrayOf(
            Pair(BrewerClient.BrewerMeaning.BREW, "Brew now"),
            Pair(BrewerClient.BrewerMeaning.STOP, "Turn off"))) {

            if (active != menuCommand.first) {
                commandMenu.menu.add(Menu.NONE, 1, 1, menuCommand.second)
                    .setOnMenuItemClickListener {
                        apiCallAndCallback(menuCommand.first)
                        true
                    }
            }
        }

        for (menuCommand in arrayOf(
            Pair(BrewerClient.BrewerMeaning.DELAY, "Start a brew timer"),
            Pair(BrewerClient.BrewerMeaning.WARM, "Start a warming timer"))) {

            commandMenu.menu.add(Menu.NONE, 1, 1, menuCommand.second)
                .setOnMenuItemClickListener {

                    getTimeFromTimePicker { hours, minutes ->
                        val seconds = getSecondsFromNow(hours, minutes)
                        if (seconds < 0) {
                            Snackbar.make(findViewById(R.id.menu_button), getString(
                                                            R.string.timer_len_warning), resources.getInteger(R.integer.snackbar_display_len_secs)).show()
                        } else {
                            apiCallAndCallback(menuCommand.first, seconds1 = seconds)
                        }
                    }

                    true
                }
        }
    }

    /**
     * getTimeFromTimePicker will show a TimePickerFragment and call its argument
     * "then" function with the resulting hours and minutes. It will only call then() if the user
     * selected a time (i.e. did not cancel).
     */
    private fun getTimeFromTimePicker(then: (Int, Int) -> Unit) {
        TimePickerFragment(then).show(supportFragmentManager, "timePicker")
    }

    private fun getSecondsFromNow(hours: Int, minutes: Int): Int {
        val hoursNow = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val minutesNow = Calendar.getInstance().get(Calendar.MINUTE)

        var hoursFromNow = hours - hoursNow
        if (hoursFromNow < 0) {
            hoursFromNow += 24
        }

        var minutesFromNow = minutes - minutesNow
        if (minutesFromNow < 0) {
            hoursFromNow -= 1
            minutesFromNow += 60
        }

        Log.d(POT_TAG, "reading user input $hours:$minutes from $hoursNow:$minutesNow as $hoursFromNow:$minutesFromNow")

        return (hoursFromNow * 60 * 60) + (minutesFromNow * 60)
    }

    private fun updatePage(message: BrewerClient.BrewerMeaning, timer1: Int? = null, timer2: Int? = null) {
        setImage(message)
        setStatusMessage(message, timer1, timer2)

        if (message == BrewerClient.BrewerMeaning.UNKNOWN_OR_ERROR) {
            findViewById<Button>(R.id.menu_button).visibility = View.GONE
        } else {
            rebuildMenu(message)
            findViewById<Button>(R.id.menu_button).visibility = View.VISIBLE
        }
    }

    private fun setStatusMessage(message: BrewerClient.BrewerMeaning, timer1: Int? = null, timer2: Int? = null) {
        val timer1Formatted = formatTime(timer1)
        val timer2Formatted = formatTime(timer2)

        findViewById<TextView>(R.id.status_text).text =
            getString(mapMeaningsToMedia[message]!!.second, timer1Formatted, timer2Formatted)
    }

    private fun formatTime(timer: Int?): String? {
        if (timer == null) return null

        val duration = timer.seconds

        return duration.toComponents { hours, minutes, seconds, _ ->
            if (hours > 0) {
                "$hours:$minutes:$seconds"
            } else {
                "${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
            }
        }
    }

    private fun setImage(message: BrewerClient.BrewerMeaning) {
        val imageId = mapMeaningsToMedia[message]!!.first

        findViewById<ImageView>(R.id.status_image).setImageResource(imageId)
    }

    private var statusChecker = object : Runnable {
        override fun run() {
            try {
                apiCallAndCallback(BrewerClient.BrewerMeaning.MONITOR) //this function can change value of mInterval.
            } finally {
                // 100% guarantee that this always happens, even if
                // your update method throws an exception
                Log.d(POT_TAG, "${resources.getInteger(R.integer.monitor_interval_duration_ms).toLong()}")
                monitorHandler.postDelayed(this, resources.getInteger(R.integer.monitor_interval_duration_ms).toLong())
            }
        }
    }

    private fun startMonitoring() {
        statusChecker.run()
    }

    private fun stopMonitoring() {
        monitorHandler.removeCallbacks(statusChecker)
    }
}