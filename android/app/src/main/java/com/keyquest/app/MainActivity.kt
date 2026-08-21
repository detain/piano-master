package com.keyquest.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                KeyQuestPlaceholder()
            }
        }
    }
}

/**
 * v1 placeholder. Next steps, in order:
 *  - Lesson player UI: plan §7 (Compose Canvas staff / note-bar rendering, wait-mode, keyboard zone)
 *  - Onboarding flow: plan §12.2 (first-launch setup)
 *  - Navigation + ViewModel arrive with the first real screen; deliberately absent here (KISS).
 */
@Composable
private fun KeyQuestPlaceholder() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = "KeyQuest")
    }
}

@Preview(showBackground = true)
@Composable
private fun KeyQuestPlaceholderPreview() {
    MaterialTheme {
        KeyQuestPlaceholder()
    }
}