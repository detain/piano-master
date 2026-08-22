package com.keyquest.app.notation

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * The P0.5 spike screen: a deliberately debug-ugly harness (plan §20 P0.0
 * anti-goal: no polish) for the scrolling-notation prototype.
 *
 * Top bar: skin toggle (note-bar <-> staff), tempo slider, play/pause, and the
 * stress-score size. The body is [ScrollingNotationPlayer] rendering the
 * deterministic stress score (>= 200 dense notes, plan §20 P0.5.2) by default.
 *
 * JankStats (P0.5.3) is attached to this screen's lifecycle: tracking runs
 * while the screen is resumed and prints a session summary on pause.
 */
@Composable
fun NotationPrototypeScreen() {
    val score = remember { ProtoScoreFactory.stressScore() }

    var skin by remember { mutableStateOf<NotationSkin>(NotationSkin.NoteBar) }
    var tempoMultiplier by remember { mutableFloatStateOf(1f) }
    var playing by remember { mutableStateOf(true) }

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            val activity = context as? Activity ?: return@LifecycleEventObserver
            when (event) {
                Lifecycle.Event.ON_RESUME -> JankTracker.start(activity.window)
                Lifecycle.Event.ON_PAUSE -> JankTracker.stop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            JankTracker.stop()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { skin = NotationSkin.NoteBar }, enabled = skin != NotationSkin.NoteBar) {
                Text("Note-bar")
            }
            TextButton(onClick = { skin = NotationSkin.Staff }, enabled = skin != NotationSkin.Staff) {
                Text("Staff")
            }
            Text(
                text = "♩=${(120 * tempoMultiplier).toInt()}",
                style = MaterialTheme.typography.bodySmall,
            )
            Slider(
                value = tempoMultiplier,
                onValueChange = { tempoMultiplier = it },
                valueRange = 0.5f..2.0f,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = { playing = !playing }) {
                Text(if (playing) "Pause" else "Play")
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
        ) {
            Text(
                text = "stress score: ${score.notes.size} notes · both hands · beam groups · Bravura loaded",
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Box(modifier = Modifier.weight(1f)) {
            ScrollingNotationPlayer(
                score = score,
                skin = skin,
                tempoBpm = 120.0 * tempoMultiplier,
                playing = playing,
            )
        }
    }
}