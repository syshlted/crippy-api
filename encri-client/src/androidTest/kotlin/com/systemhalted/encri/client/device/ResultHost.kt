package com.systemhalted.encri.client.device

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Starts one of encri's screens with `startActivityForResult` and hands back what it answered (L13).
 * encri's consent screen names the app asking from the system's record of who started it, which only
 * `startActivityForResult` sets (encri D114), and an instrumentation cannot start an Activity for a
 * result itself.
 */
class ResultHost : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            @Suppress("DEPRECATION") // getParcelableExtra(String, Class) is API 33; the floor is 27.
            val target: Intent = requireNotNull(intent.getParcelableExtra(TARGET))
            @Suppress("DEPRECATION") // The only way an Activity hears back at API 27.
            startActivityForResult(target, REQUEST)
        }
    }

    @Deprecated("The platform's, and the only way an Activity hears back at API 27.")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
    ) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        answers.offer(Answer(resultCode, data))
        finish()
    }

    /** What the screen answered. */
    data class Answer(
        val resultCode: Int,
        val data: Intent?,
    )

    companion object {
        private const val TARGET = "target"
        private const val REQUEST = 1
        private val answers = LinkedBlockingQueue<Answer>()

        /** Starts [target] for a result from a host of this app's own. Answer with [awaitAnswer]. */
        fun start(
            context: Context,
            target: Intent,
        ) {
            answers.clear()
            // A task of its own, cleared: a run that failed can leave this task behind with one of encri's
            // screens on top, started for a result by a host whose process is gone, and a new start would
            // otherwise only bring that task forward.
            context.startActivity(
                Intent(context, ResultHost::class.java)
                    .putExtra(TARGET, target)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            )
        }

        /** What the screen answered, or null if it did not answer within [seconds]. */
        fun awaitAnswer(seconds: Long): Answer? = answers.poll(seconds, TimeUnit.SECONDS)
    }
}
