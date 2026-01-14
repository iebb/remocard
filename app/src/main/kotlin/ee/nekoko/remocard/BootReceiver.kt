package ee.nekoko.remocard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i("RemoCardBoot", "Boot completed, checking auto-start preference")
            val prefs = context.getSharedPreferences("remocard_prefs", Context.MODE_PRIVATE)
            if (prefs.getBoolean("auto_start", true)) {
                Log.i("RemoCardBoot", "Starting RemoService...")
                val serviceIntent = Intent(context, RemoService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
        }
    }
}
