package work.portside.esim

import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.button_check_sim).setOnClickListener {
            Toast.makeText(this, R.string.sim_check_placeholder, Toast.LENGTH_SHORT).show()
        }
    }
}
